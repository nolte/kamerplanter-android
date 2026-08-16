package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.ConnectionRequest
import io.github.nolte.kamerplanter.core.connection.ConnectionResult
import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.connection.Credential
import io.github.nolte.kamerplanter.core.connection.InMemoryCredentialStore
import io.github.nolte.kamerplanter.core.connection.Tenant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the client against canned HTTP over a real socket, because the failure modes that
 * matter here are protocol-level: a wrong header name, a redeemed code that comes back 400,
 * an instance that is not kamerplanter at all. A stubbed seam would confirm the code calls
 * itself correctly and nothing about whether the requests are right.
 */
class NetworkConnectionClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: NetworkConnectionClient

    private val fixedNow = 1_760_000_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NetworkConnectionClient(
            // No refresh path here: connecting is what produces a session, so there is
            // never one to renew during it.
            apis = InstanceApiFactory(
                httpClient = OkHttpClient(),
                json = NetworkModule.provideJson(),
                tokenRefresh = TokenRefreshAuthenticator(
                    SessionRefresher(
                        OkHttpClient(),
                        NetworkModule.provideJson(),
                        InMemoryCredentialStore(),
                        NeverConnectedStore,
                    ),
                ),
            ),
            clock = { fixedNow },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString()

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private fun enqueueHealth(mode: String = "full") =
        enqueueJson("""{"status":"healthy","version":"1.0.0","mode":"$mode"}""")

    private fun enqueueTenants() =
        enqueueJson(
            """[{"key":"t1","slug":"demo","name":"Demo garden","description":null,
               "is_active":true,"role":"lead","tenant_type":"personal"}]""",
        )

    private fun requestsMade(): List<RecordedRequest> =
        List(server.requestCount) { server.takeRequest() }

    // --- light mode -------------------------------------------------------------------

    /**
     * Light mode has no credential and no identity — and tenants all the same.
     *
     * This asserted `emptyList()` until a real light-mode instance showed otherwise: it serves
     * `/api/v1/tenants` unauthenticated and answers with its system tenant, while every plant
     * route is scoped to a slug. The app connected happily and then had nothing to ask about,
     * which reached the user as a permanently empty plant list.
     */
    @Test
    fun `light mode connects with no credential, but with its tenants`() = runTest {
        enqueueHealth(mode = "light")
        enqueueTenants()

        val result = client.connect(ConnectionRequest.LightMode(baseUrl()))

        val verified = result as ConnectionResult.Verified
        assertEquals(Credential.None, verified.credential)
        assertEquals(listOf(Tenant(slug = "demo", displayName = "Demo garden")), verified.tenants)
        assertNull(verified.identity)
    }

    /** No identity call: light mode has no accounts, so `/api/v1/users/me` would 404. */
    @Test
    fun `light mode asks only about health and tenants`() = runTest {
        enqueueHealth(mode = "light")
        enqueueTenants()

        client.connect(ConnectionRequest.LightMode(baseUrl()))

        assertEquals(
            listOf("/api/health", "/api/v1/tenants"),
            requestsMade().map { it.path },
        )
    }

    /** A full instance has accounts, so connecting to it credential-free would be a lie. */
    @Test
    fun `light mode refuses an instance that is not in light mode`() = runTest {
        enqueueHealth(mode = "full")

        val result = client.connect(ConnectionRequest.LightMode(baseUrl()))

        assertTrue((result as ConnectionResult.Failure).reason.contains("not in light mode"))
    }

    /**
     * The backend labels only the deviation, so a health payload without `mode` is a full
     * instance — reading a missing field as "light" would connect credential-free to an
     * instance that expects credentials.
     */
    @Test
    fun `a health payload without a mode field counts as a full instance`() = runTest {
        enqueueJson("""{"status":"healthy","version":"1.0.0"}""")

        val result = client.connect(ConnectionRequest.LightMode(baseUrl()))

        assertTrue(result is ConnectionResult.Failure)
    }

    // --- QR pairing -------------------------------------------------------------------

    @Test
    fun `pairing redeems the code and reports identity, tenants and session`() = runTest {
        enqueueHealth()
        enqueueJson(
            """{"access_token":"at-1","refresh_token":"rt-1","expires_in":900,"token_type":"bearer"}""",
        )
        enqueueJson(
            """{"key":"u1","email":"gardener@example.org","display_name":"Gardener",
               "email_verified":true,"is_active":true,"created_at":null,"last_login_at":null,
               "avatar_url":null,"locale":"de"}""",
        )
        enqueueJson(
            """[{"key":"t1","slug":"demo","name":"Demo garden","description":null,
               "is_active":true,"role":"lead","tenant_type":"personal"}]""",
        )

        val result = client.connect(ConnectionRequest.QrPairing(baseUrl(), code = "ABC123"))

        val verified = result as ConnectionResult.Verified
        assertEquals("gardener@example.org", verified.identity)
        assertEquals(listOf(Tenant(slug = "demo", displayName = "Demo garden")), verified.tenants)
        assertEquals(
            Credential.Session(
                accessToken = "at-1",
                refreshToken = "rt-1",
                accessTokenExpiresAtEpochMillis = fixedNow + 900_000L,
            ),
            verified.credential,
        )
    }

    /** The token has to be presented as a bearer, and only after it has been redeemed. */
    @Test
    fun `pairing redeems unauthenticated and authenticates every call after it`() = runTest {
        enqueueHealth()
        enqueueJson("""{"access_token":"at-1","refresh_token":"rt-1","expires_in":900}""")
        enqueueJson(
            """{"key":"u1","email":"g@example.org","display_name":"G","email_verified":true,
               "is_active":true,"created_at":null,"last_login_at":null,"avatar_url":null,
               "locale":"de"}""",
        )
        enqueueJson("[]")

        client.connect(ConnectionRequest.QrPairing(baseUrl(), code = "ABC123"))

        val requests = requestsMade()
        val redeem = requests.single { it.path?.endsWith("/device-pairing/redeem") == true }
        assertNull(redeem.getHeader("Authorization"))
        val profile = requests.single { it.path?.endsWith("/users/me") == true }
        assertEquals("Bearer at-1", profile.getHeader("Authorization"))
    }

    /**
     * F-6 acceptance-6. The endpoint's own description distinguishes these, and the obvious
     * reading of each is wrong: 423 is the source address locked out and 429 is a rate
     * limit — neither means the code expired, and telling the user it did sends them to
     * generate codes that will be refused just as fast.
     */
    @Test
    fun `a locked-out address is not reported as an expired code`() = runTest {
        enqueueHealth()
        enqueueJson("""{"detail":"locked"}""", code = 423)

        val result = client.connect(ConnectionRequest.QrPairing(baseUrl(), code = "ABC123"))

        val reason = (result as ConnectionResult.Failure).reason
        assertTrue(reason, reason.contains("locked"))
        assertFalse(reason, reason.contains("expires within two minutes"))
    }

    @Test
    fun `a rate-limited pairing attempt is not reported as an expired code`() = runTest {
        enqueueHealth()
        enqueueJson("""{"detail":"slow down"}""", code = 429)

        val result = client.connect(ConnectionRequest.QrPairing(baseUrl(), code = "ABC123"))

        val reason = (result as ConnectionResult.Failure).reason
        assertTrue(reason, reason.contains("in a short time"))
        assertFalse(reason, reason.contains("expires within two minutes"))
    }

    /** 401 is the one status that really does mean the code is unusable. */
    @Test
    fun `an unknown or spent code is reported as such`() = runTest {
        enqueueHealth()
        enqueueJson("""{"detail":"unknown"}""", code = 401)

        val result = client.connect(ConnectionRequest.QrPairing(baseUrl(), code = "ABC123"))

        assertTrue((result as ConnectionResult.Failure).reason.contains("expires within two minutes"))
    }

    @Test
    fun `pairing refuses a light-mode instance instead of failing deeper in`() = runTest {
        enqueueHealth(mode = "light")

        val result = client.connect(ConnectionRequest.QrPairing(baseUrl(), code = "ABC123"))

        assertTrue((result as ConnectionResult.Failure).reason.contains("light mode"))
        // Only the health probe was attempted — no pairing call went out.
        assertEquals(1, server.requestCount)
    }

    // --- API key ----------------------------------------------------------------------

    /** R15: the tenant comes from the key's own scope where the instance reports it. */
    @Test
    fun `an api key takes its tenant from the key's own scope`() = runTest {
        enqueueHealth()
        enqueueJson(
            """[{"key":"t1","slug":"demo","name":"Demo garden","description":null,
               "is_active":true,"role":"lead","tenant_type":"personal"}]""",
        )
        enqueueJson(
            """{"display_name":"CI robot","service_account_key":"sa1",
               "tenants":[{"tenant_key":"t1","tenant_slug":"scoped","role":"editor",
               "mcp_permissions":[]}]}""",
        )
        enqueueJson(
            """{"key":"u1","email":"robot@example.org","display_name":"CI robot",
               "email_verified":true,"is_active":true,"created_at":null,"last_login_at":null,
               "avatar_url":null,"locale":"de"}""",
        )

        val result = client.connect(ConnectionRequest.ApiKey(baseUrl(), key = "kp_sk_secret"))

        val verified = result as ConnectionResult.Verified
        assertEquals(listOf(Tenant(slug = "scoped", displayName = "scoped")), verified.tenants)
        assertEquals(Credential.ApiKey("kp_sk_secret"), verified.credential)
        // The tenant listing still goes out — it is the authenticated call R13 requires —
        // but the scope answered, so its "scoped" wins over the listing's "demo".
        assertTrue(requestsMade().any { it.path?.endsWith("/api/v1/tenants") == true })
    }

    /**
     * The scope route sits behind the instance's MCP flag and 404s when MCP is off. A valid
     * key must not read as rejected there, so the tenant list stands in.
     */
    @Test
    fun `an api key falls back to the tenant list when MCP is switched off`() = runTest {
        enqueueHealth()
        enqueueJson(
            """[{"key":"t1","slug":"demo","name":"Demo garden","description":null,
               "is_active":true,"role":"lead","tenant_type":"personal"}]""",
        )
        enqueueJson("""{"detail":"not found"}""", code = 404)
        enqueueJson(
            """{"key":"u1","email":"robot@example.org","display_name":"CI robot",
               "email_verified":true,"is_active":true,"created_at":null,"last_login_at":null,
               "avatar_url":null,"locale":"de"}""",
        )

        val result = client.connect(ConnectionRequest.ApiKey(baseUrl(), key = "kp_sk_secret"))

        val verified = result as ConnectionResult.Verified
        assertEquals(listOf(Tenant(slug = "demo", displayName = "Demo garden")), verified.tenants)
    }

    /**
     * R9: a `kp_sk_…` key travels in the same Authorization: Bearer header the JWT uses.
     * The schema's other scheme, X-API-Key, is accepted by six routes, all under
     * /api/v1/mcp — sending the key there would leave 738 operations unauthenticated.
     */
    @Test
    fun `an api key is presented as a bearer token`() = runTest {
        enqueueHealth()
        enqueueJson(
            """[{"key":"t1","slug":"demo","name":"Demo garden","description":null,
               "is_active":true,"role":"lead","tenant_type":"personal"}]""",
        )
        enqueueJson(
            """{"display_name":"CI robot","service_account_key":"sa1",
               "tenants":[{"tenant_key":"t1","tenant_slug":"demo","role":"editor",
               "mcp_permissions":[]}]}""",
        )
        enqueueJson("""{"detail":"no profile"}""", code = 404)

        client.connect(ConnectionRequest.ApiKey(baseUrl(), key = "kp_sk_secret"))

        // Asserted on /api/v1/tenants, a route the schema actually guards with BearerAuth.
        // The scope route carries `security: []` and would ignore the header entirely, so
        // checking it there would prove nothing.
        val authenticated = requestsMade().single { it.path?.endsWith("/api/v1/tenants") == true }
        assertEquals("Bearer kp_sk_secret", authenticated.getHeader("Authorization"))
        assertNull(authenticated.getHeader("X-API-Key"))
    }

    /** A 401 is the instance rejecting the key, not the route being absent — no fallback. */
    @Test
    fun `an api key rejected by the instance is a failure`() = runTest {
        enqueueHealth()
        enqueueJson("""{"detail":"invalid"}""", code = 401)

        val result = client.connect(ConnectionRequest.ApiKey(baseUrl(), key = "kp_sk_wrong"))

        val reason = (result as ConnectionResult.Failure).reason
        assertTrue(reason, reason.contains("refused this credential"))
        // The authenticated call failed, so nothing past it was attempted (R13).
        assertEquals(2, server.requestCount)
    }

    /** A service-account key has no user profile; that must not read as a failed connection. */
    @Test
    fun `an api key without a readable profile still connects`() = runTest {
        enqueueHealth()
        enqueueJson(
            """[{"key":"t1","slug":"demo","name":"Demo garden","description":null,
               "is_active":true,"role":"lead","tenant_type":"personal"}]""",
        )
        enqueueJson(
            """{"display_name":"CI robot","service_account_key":"sa1",
               "tenants":[{"tenant_key":"t1","tenant_slug":"demo","role":"editor",
               "mcp_permissions":[]}]}""",
        )
        enqueueJson("""{"detail":"service accounts have no profile"}""", code = 404)

        val result = client.connect(ConnectionRequest.ApiKey(baseUrl(), key = "kp_sk_secret"))

        val verified = result as ConnectionResult.Verified
        assertNull(verified.identity)
        assertEquals(1, verified.tenants.size)
    }

    /**
     * A 503 says the instance is unwell, not that the credential is wrong. Reporting it as a
     * refusal sends the user to replace a key that works.
     */
    @Test
    fun `a server error while listing tenants is not blamed on the credential`() = runTest {
        enqueueHealth()
        enqueueJson("""{"detail":"starting"}""", code = 503)

        val result = client.connect(ConnectionRequest.ApiKey(baseUrl(), key = "kp_sk_secret"))

        val reason = (result as ConnectionResult.Failure).reason
        assertTrue(reason, reason.contains("503"))
        assertFalse(reason, reason.contains("refused"))
    }

    /**
     * Once the tenant listing has succeeded the key has authenticated. The scope route is
     * then only a second source of tenants — and it returns the same masked 401 for a valid
     * non-service key — so its failure must not sink a connection that already works.
     */
    @Test
    fun `a failing scope route does not sink an already-authenticated key`() = runTest {
        enqueueHealth()
        enqueueJson(
            """[{"key":"t1","slug":"demo","name":"Demo garden","description":null,
               "is_active":true,"role":"lead","tenant_type":"personal"}]""",
        )
        enqueueJson("""{"detail":"masked"}""", code = 401)
        enqueueJson("""{"detail":"no profile"}""", code = 404)

        val result = client.connect(ConnectionRequest.ApiKey(baseUrl(), key = "kp_sk_secret"))

        val verified = result as ConnectionResult.Verified
        assertEquals(listOf(Tenant(slug = "demo", displayName = "Demo garden")), verified.tenants)
        // Pinned by path, not by position: the canned responses are matched to calls purely
        // by order, so reordering the client's calls would otherwise keep this green while
        // handing each response to the wrong request.
        assertEquals(
            listOf("/api/health", "/api/v1/tenants", "/api/v1/auth/service-accounts/validate", "/api/v1/users/me"),
            requestsMade().map { it.path },
        )
    }

    // --- diagnostics that used to be wrong --------------------------------------------

    /**
     * The code is single-use with a 60-120 second life. Once redeemed it is spent, so a
     * failure after that point cannot be retried by tapping again — and must not read like
     * the code was wrong, which would send the user to re-scan the same dead code.
     */
    @Test
    fun `a failure after redemption says the code is spent`() = runTest {
        enqueueHealth()
        enqueueJson("""{"access_token":"at-1","refresh_token":"rt-1","expires_in":900}""")
        // Both post-redemption calls have to be answered: the profile read tolerates a
        // failure, so only the tenant call can carry the error being tested here.
        enqueueJson("""{"detail":"boom"}""", code = 500)
        enqueueJson("""{"detail":"boom"}""", code = 500)

        val result = client.connect(ConnectionRequest.QrPairing(baseUrl(), code = "ABC123"))

        val reason = (result as ConnectionResult.Failure).reason
        assertTrue(reason, reason.contains("now spent"))
        assertTrue(reason, reason.contains("500"))
        assertFalse(reason, reason.contains("expires within two minutes"))
    }

    /**
     * A 503 from an instance that is still starting, or a 502 from a proxy in front of a
     * healthy one, is not "wrong address" — reporting it as such sends the user to check a
     * URL that is perfectly correct.
     */
    @Test
    fun `an instance that answers with an error keeps its status in the reason`() = runTest {
        enqueueJson("""{"detail":"starting"}""", code = 503)

        val result = client.connect(ConnectionRequest.LightMode(baseUrl()))

        assertTrue((result as ConnectionResult.Failure).reason.contains("503"))
    }

    /** R-HEALTH-3: a reachable but unhealthy instance must not be connected to silently. */
    @Test
    fun `an instance reporting a non-healthy status is refused`() = runTest {
        enqueueJson("""{"status":"degraded","version":"1.0.0","mode":"full"}""")

        val result = client.connect(ConnectionRequest.QrPairing(baseUrl(), code = "ABC123"))

        val reason = (result as ConnectionResult.Failure).reason
        assertTrue(reason, reason.contains("degraded"))
        // Nothing was attempted past the probe, so the code is still unspent.
        assertEquals(1, server.requestCount)
    }

    // --- unreachable and secret hygiene -----------------------------------------------

    @Test
    fun `an address that answers nothing kamerplanter-shaped is a failure`() = runTest {
        enqueueJson("<html>not an api</html>", code = 404)

        val result = client.connect(ConnectionRequest.LightMode(baseUrl()))

        assertTrue(result is ConnectionResult.Failure)
    }

    /**
     * R19: a diagnostic reason is written to logs and test reports. A failure that echoes
     * the pairing code or API key would put the secret in both.
     */
    @Test
    fun `a failure reason never echoes the secret it failed on`() = runTest {
        val url = baseUrl()
        server.shutdown()

        val pairing = client.connect(ConnectionRequest.QrPairing(url, code = "SUPERSECRET"))
        val apiKey = client.connect(ConnectionRequest.ApiKey(url, key = "kp_sk_SUPERSECRET"))

        assertFalse((pairing as ConnectionResult.Failure).reason.contains("SUPERSECRET"))
        assertFalse((apiKey as ConnectionResult.Failure).reason.contains("SUPERSECRET"))
    }
}

/** There is no connection while one is being established, which is all these tests do. */
private object NeverConnectedStore : ConnectionStore {
    override val connection: Flow<Connection?> = flowOf(null)
    override suspend fun save(connection: Connection) = Unit
    override suspend fun clear() = Unit
}
