package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.connection.Credential
import io.github.nolte.kamerplanter.core.connection.CredentialStore
import io.github.nolte.kamerplanter.core.connection.InMemoryCredentialStore
import io.github.nolte.kamerplanter.core.network.generated.apis.TenantsApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the fifteen-minute cliff.
 *
 * Access tokens expire that fast, so without renewal the app stops working a quarter of an
 * hour after pairing — every screen reporting a refused credential. These tests drive the
 * whole path over a socket, because what has to be right is protocol-level: which call
 * carries which token, and what happens to the stored pair afterwards.
 */
class SessionRefreshTest {

    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()

    /** Rejects any token but this one, so "expired" is a real server behaviour here. */
    private var acceptedToken = "at-new"
    private var refreshResponse: MockResponse = tokenPair("at-new", "rt-new")
    private val refreshCalls = AtomicInteger()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                val path = request.path.orEmpty().substringBefore('?')
                return when {
                    path.endsWith("/auth/refresh") -> {
                        refreshCalls.incrementAndGet()
                        refreshResponse
                    }
                    request.getHeader("Authorization") == "Bearer $acceptedToken" ->
                        json("[]")
                    else -> json("""{"detail":"expired"}""", code = 401)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun tokenPair(access: String, refresh: String) = json(
        """{"access_token":"$access","refresh_token":"$refresh","expires_in":900,"token_type":"bearer"}""",
    )

    private fun credentials(initial: Credential = STALE_SESSION) = InMemoryCredentialStore(initial)

    private fun connections() = FakeConnectionStore(
        Connection.QrPairing(baseUrl = server.url("/").toString(), tenantSlug = "demo"),
    )

    private fun factory(
        credentials: CredentialStore,
        connections: ConnectionStore,
    ): InstanceApiFactory {
        val http = OkHttpClient()
        val json = NetworkModule.provideJson()
        return InstanceApiFactory(
            httpClient = http,
            json = json,
            tokenRefresh = TokenRefreshAuthenticator(
                SessionRefresher(http, json, credentials, connections, clock = { FIXED_NOW }),
            ),
        )
    }

    /** The call that 401s is retried with the new token, and the caller never sees the 401. */
    @Test
    fun `an expired access token is renewed and the call repeated`() = runTest {
        val credentials = credentials()
        val api = factory(credentials, connections())
            .create(server.url("/").toString()) { runCatchingBlocking(credentials) }

        val response = api.create(TenantsApi::class.java).listMyTenantsApiV1TenantsGet()

        assertTrue("expected the retried call to succeed", response.isSuccessful)
        val tenantCalls = requests.filter { it.path?.endsWith("/api/v1/tenants") == true }
        assertEquals("the call should have been made twice", 2, tenantCalls.size)
        assertEquals("Bearer at-stale", tenantCalls[0].getHeader("Authorization"))
        assertEquals("Bearer at-new", tenantCalls[1].getHeader("Authorization"))
    }

    /** R22: the refresh token rotates, and the old one is dead the moment it is spent. */
    @Test
    fun `the rotated refresh token replaces the stored one`() = runTest {
        val credentials = credentials()
        val api = factory(credentials, connections())
            .create(server.url("/").toString()) { runCatchingBlocking(credentials) }

        api.create(TenantsApi::class.java).listMyTenantsApiV1TenantsGet()

        assertEquals(
            // Asserted against a known instant, not against the stored value: comparing the
            // expiry with itself would wave through a unit mix-up — seconds where the field
            // holds milliseconds — in the one field the refresh cycle consults.
            Credential.Session("at-new", "rt-new", FIXED_NOW + 900_000L),
            credentials.stored,
        )
    }

    /** R21: the refresh goes out as a JSON body, unauthenticated — the header is expired. */
    @Test
    fun `the refresh call carries the token in its body and no authorization header`() = runTest {
        val credentials = credentials()
        val api = factory(credentials, connections())
            .create(server.url("/").toString()) { runCatchingBlocking(credentials) }

        api.create(TenantsApi::class.java).listMyTenantsApiV1TenantsGet()

        val refresh = requests.single { it.path?.endsWith("/auth/refresh") == true }
        assertNull(refresh.getHeader("Authorization"))
        assertTrue(refresh.body.readUtf8().contains("rt-stale"))
    }

    /**
     * R23: a refresh that fails clears both halves. Leaving the connection behind would show
     * a connected-looking Settings screen whose every request fails.
     */
    @Test
    fun `a failed refresh clears the credential and the connection`() = runTest {
        refreshResponse = json("""{"detail":"revoked"}""", code = 401)
        val credentials = credentials()
        val connections = connections()
        val api = factory(credentials, connections)
            .create(server.url("/").toString()) { runCatchingBlocking(credentials) }

        val response = api.create(TenantsApi::class.java).listMyTenantsApiV1TenantsGet()

        assertEquals(401, response.code())
        assertEquals(Credential.None, credentials.stored)
        assertNull(connections.current)
    }

    /**
     * Several calls hit 401 at the same moment routinely — the plant list alone fires a
     * handful in parallel. Each refreshing on its own would spend the same single-use token,
     * so the first would succeed and the rest would invalidate the session just restored.
     */
    @Test
    fun `concurrent failures refresh once, not once each`() = runTest {
        val credentials = credentials()
        val api = factory(credentials, connections())
            .create(server.url("/").toString()) { runCatchingBlocking(credentials) }
        val tenants = api.create(TenantsApi::class.java)

        val responses = (1..5).map { async { tenants.listMyTenantsApiV1TenantsGet() } }.awaitAll()

        assertTrue("every call should have succeeded", responses.all { it.isSuccessful })
        assertEquals("the refresh token is single-use", 1, refreshCalls.get())
    }

    /** An API key never expires, so a 401 on one is the server's verdict, not a stale token. */
    @Test
    fun `an api key is never refreshed`() = runTest {
        val credentials = credentials(Credential.ApiKey("kp_sk_wrong"))
        val connections = connections()
        val api = factory(credentials, connections)
            .create(server.url("/").toString()) { runCatchingBlocking(credentials) }

        val response = api.create(TenantsApi::class.java).listMyTenantsApiV1TenantsGet()

        assertEquals(401, response.code())
        assertEquals(0, refreshCalls.get())
        // The key is left alone: nothing about it was proven wrong by one endpoint's 401.
        assertEquals(Credential.ApiKey("kp_sk_wrong"), credentials.stored)
    }

    /**
     * R23 clears the stored connection, so it may only fire when the instance has actually
     * refused the token. A network drop or a restarting instance would otherwise send the
     * user to generate a new pairing code for a server that is merely busy.
     */
    @Test
    fun `an unreachable instance does not clear the stored connection`() = runTest {
        refreshResponse = json("""{"detail":"bad gateway"}""", code = 502)
        val credentials = credentials()
        val connections = connections()
        val api = factory(credentials, connections)
            .create(server.url("/").toString()) { runCatchingBlocking(credentials) }

        val response = api.create(TenantsApi::class.java).listMyTenantsApiV1TenantsGet()

        assertEquals(401, response.code())
        assertEquals("the session must survive a server-side hiccup", STALE_SESSION, credentials.stored)
        assertTrue("the connection must survive it too", connections.current != null)
    }

    /**
     * The authenticator is a singleton on every session-bearing client, including the one
     * the pairing flow builds for an address the user has just typed in. A 401 from that
     * instance must not be answered with the token belonging to the one already stored.
     */
    @Test
    fun `a 401 from a different instance is never answered with the stored token`() = runTest {
        val other = MockWebServer()
        other.start()
        try {
            other.enqueue(json("""{"detail":"nope"}""", code = 401))
            val credentials = credentials()
            // Stored connection points at `server`; the call goes to `other`.
            val api = factory(credentials, connections())
                .create(other.url("/").toString()) { runCatchingBlocking(credentials) }

            val response = api.create(TenantsApi::class.java).listMyTenantsApiV1TenantsGet()

            assertEquals(401, response.code())
            assertEquals("no refresh may be attempted for a foreign host", 0, refreshCalls.get())
            assertEquals(1, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    /** A token the server keeps rejecting must not put the client in a refresh loop. */
    @Test
    fun `a token the server keeps rejecting is not retried forever`() = runTest {
        // The refresh succeeds but hands back a token this server still refuses.
        refreshResponse = tokenPair("at-also-rejected", "rt-new")
        val credentials = credentials()
        val api = factory(credentials, connections())
            .create(server.url("/").toString()) { runCatchingBlocking(credentials) }

        val response = api.create(TenantsApi::class.java).listMyTenantsApiV1TenantsGet()

        assertEquals(401, response.code())
        assertEquals("one refresh, then give up", 1, refreshCalls.get())
    }
}

private const val FIXED_NOW = 1_760_000_000_000L

private val STALE_SESSION = Credential.Session(
    accessToken = "at-stale",
    refreshToken = "rt-stale",
    accessTokenExpiresAtEpochMillis = 0L,
)

/** The credential lookup the factory takes; blocking is what the interceptor does too. */
private fun runCatchingBlocking(store: CredentialStore): Credential =
    kotlinx.coroutines.runBlocking { store.load() }

private class FakeConnectionStore(initial: Connection?) : ConnectionStore {
    private val flow = MutableStateFlow(initial)
    override val connection: Flow<Connection?> = flow
    val current: Connection? get() = flow.value

    override suspend fun save(connection: Connection) {
        flow.value = connection
    }

    override suspend fun clear() {
        flow.value = null
    }
}
