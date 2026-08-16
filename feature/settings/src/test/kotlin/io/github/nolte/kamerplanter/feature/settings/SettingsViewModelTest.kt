package io.github.nolte.kamerplanter.feature.settings

import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.ConnectionClient
import io.github.nolte.kamerplanter.core.connection.ConnectionMethod
import io.github.nolte.kamerplanter.core.connection.ConnectionRequest
import io.github.nolte.kamerplanter.core.connection.ConnectionResult
import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.connection.Credential
import io.github.nolte.kamerplanter.core.connection.CredentialStore
import io.github.nolte.kamerplanter.core.connection.DiscoveryLink
import io.github.nolte.kamerplanter.core.connection.InMemoryCredentialStore
import io.github.nolte.kamerplanter.core.connection.PendingDiscovery
import io.github.nolte.kamerplanter.core.connection.Tenant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    // The payload a real instance encodes: the web UI writes JSON.stringify({ v, url, code }).
    private val validQr = """{"v":1,"url":"https://plants.example.org","code":"ABC123"}"""
    private val request = ConnectionRequest.QrPairing(baseUrl = "https://plants.example.org", code = "ABC123")
    private val connection = Connection.QrPairing(
        baseUrl = "https://plants.example.org",
        tenantSlug = CANNED_TENANT.slug,
        identity = CANNED_IDENTITY,
    )
    private val failQr = """{"v":1,"url":"https://x","code":"$CANNED_FAIL_CODE"}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val discoveries = PendingDiscovery()

    private fun viewModel(
        client: ConnectionClient = CannedConnectionClient(),
        store: ConnectionStore = FakeConnectionStore(),
        credentials: CredentialStore = InMemoryCredentialStore(),
    ) = SettingsViewModel(client, store, credentials, discoveries)

    @Test
    fun `starts connected when a connection is already persisted`() = runTest(dispatcher) {
        // Both halves have to be there: a QR-paired connection is only whole with the
        // session that authenticates it, and startup checks exactly that.
        val viewModel = viewModel(
            store = FakeConnectionStore(initial = connection),
            credentials = InMemoryCredentialStore(initial = SESSION),
        )
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected(connection), viewModel.state.value)
    }

    @Test
    fun `a connection whose credential cannot be read starts disconnected`() = runTest(dispatcher) {
        // What a cloud-backup restore or a device transfer produces: the connection file
        // comes back, the Keystore key does not, so every decrypt fails and the store
        // reports no credential. Showing `Connected` there would offer the user an instance
        // the app can never authenticate against, with nothing to click that repairs it.
        val store = FakeConnectionStore(initial = connection)
        val viewModel = viewModel(store = store, credentials = InMemoryCredentialStore())
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, viewModel.state.value)
        // But the record is NOT deleted: "cannot be read" and "is not there" are the same
        // observation from here, and a transient Keystore failure must not cost the user
        // their pairing. Reconnecting wipes it before writing anyway.
        assertFalse(store.cleared)
    }

    @Test
    fun `a credential left behind without its connection is erased on startup`() = runTest(dispatcher) {
        // The mirror case, and the one that is unambiguous: establish() writes the secret
        // first, so process death between the two writes leaves a decryptable refresh token
        // with no connection record. Nothing else ever removes it — Disconnect is not
        // reachable from Disconnected — so startup has to (R25).
        val credentials = InMemoryCredentialStore(initial = SESSION)
        val viewModel = viewModel(store = FakeConnectionStore(), credentials = credentials)
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, viewModel.state.value)
        assertTrue(credentials.cleared)
        assertEquals(Credential.None, credentials.stored)
    }

    @Test
    fun `starts disconnected when nothing is persisted`() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertEquals(ConnectionState.Disconnected, viewModel.state.value)
    }

    @Test
    fun `starting the qr method moves from disconnected to scanning`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)

        assertEquals(ConnectionState.Collecting.ScanningQr(), viewModel.state.value)
    }

    @Test
    fun `each method has its own collection state`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startConnecting(ConnectionMethod.API_KEY)
        assertEquals(ConnectionState.Collecting.ApiKeyEntry(), viewModel.state.value)

        viewModel.startConnecting(ConnectionMethod.LIGHT_MODE)
        assertEquals(ConnectionState.Collecting.LightModeEntry(), viewModel.state.value)
    }

    @Test
    fun `a valid qr connects and persists the connection`() = runTest(dispatcher) {
        val store = FakeConnectionStore()
        val viewModel = viewModel(store = store)

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected(validQr)
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected(connection), viewModel.state.value)
        assertEquals(connection, store.saved)
    }

    @Test
    fun `the sentinel fail code ends in failed and persists nothing`() = runTest(dispatcher) {
        val store = FakeConnectionStore()
        val viewModel = viewModel(store = store)

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected(failQr)
        advanceUntilIdle()

        assertEquals(
            ConnectionMethod.QR_PAIRING,
            (viewModel.state.value as ConnectionState.Failed).method,
        )
        assertNull(store.saved)
    }

    @Test
    fun `an unparseable qr is ignored while scanning`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected("just some scanned text")

        assertEquals(ConnectionState.Collecting.ScanningQr(), viewModel.state.value)
    }

    @Test
    fun `a foreign qr is reported as seen so the scanner can say so`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)

        // The distinction the scanner's badge rests on: this is not the same as seeing
        // nothing, and reporting it as such is what makes a payload-format mismatch visible
        // instead of looking like a camera that never focused.
        assertEquals(QrReading.FOREIGN, viewModel.onQrDetected("just some scanned text"))
    }

    @Test
    fun `an accepted qr is reported as accepted`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)

        assertEquals(QrReading.ACCEPTED, viewModel.onQrDetected(validQr))
    }

    @Test
    fun `a qr decoded after the scan ended is stale, not foreign`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected(validQr)

        // The frames still carrying the accepted code keep arriving. Calling those foreign
        // would put "not a kamerplanter code" on screen over a pairing already under way.
        assertEquals(QrReading.STALE, viewModel.onQrDetected(validQr))
    }

    @Test
    fun `qr detection is ignored when not scanning`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onQrDetected(validQr)

        assertEquals(ConnectionState.Disconnected, viewModel.state.value)
    }

    @Test
    fun `verifying is entered before the backend responds`() = runTest(dispatcher) {
        val client = GatedConnectionClient(verified(tenants = listOf(CANNED_TENANT)))
        val viewModel = viewModel(client = client)

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected(validQr)

        assertEquals(ConnectionState.Verifying(ConnectionMethod.QR_PAIRING), viewModel.state.value)

        client.release()
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected(connection), viewModel.state.value)
    }

    @Test
    fun `an api key connects with its scoped tenant and a masked hint`() = runTest(dispatcher) {
        val store = FakeConnectionStore()
        val viewModel = viewModel(store = store)

        viewModel.startConnecting(ConnectionMethod.API_KEY)
        viewModel.submit(ConnectionRequest.ApiKey(baseUrl = "https://plants.example.org", key = "kp_sk_abcdef"))
        advanceUntilIdle()

        val expected = Connection.ApiKey(
            baseUrl = "https://plants.example.org",
            tenantSlug = CANNED_TENANT.slug,
            keyHint = "…cdef",
        )
        assertEquals(ConnectionState.Connected(expected), viewModel.state.value)
        assertEquals(expected, store.saved)
    }

    @Test
    fun `light mode connects without a tenant`() = runTest(dispatcher) {
        val store = FakeConnectionStore()
        val viewModel = viewModel(store = store)

        viewModel.startConnecting(ConnectionMethod.LIGHT_MODE)
        viewModel.submit(ConnectionRequest.LightMode(baseUrl = "https://light.example.org"))
        advanceUntilIdle()

        val expected = Connection.LightMode(baseUrl = "https://light.example.org")
        assertEquals(ConnectionState.Connected(expected), viewModel.state.value)
        assertEquals(expected, store.saved)
    }

    @Test
    fun `a submission that does not match the collected method is ignored`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startConnecting(ConnectionMethod.LIGHT_MODE)
        viewModel.submit(ConnectionRequest.ApiKey(baseUrl = "https://x", key = "kp_sk_1"))
        advanceUntilIdle()

        assertEquals(ConnectionState.Collecting.LightModeEntry(), viewModel.state.value)
    }

    @Test
    fun `several tenants pause the flow until the user picks one`() = runTest(dispatcher) {
        val tenants = listOf(Tenant("balcony"), Tenant("greenhouse"))
        val store = FakeConnectionStore()
        val viewModel = viewModel(client = StubConnectionClient(verified(tenants)), store = store)

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected(validQr)
        advanceUntilIdle()

        assertEquals(
            ConnectionState.SelectingTenant(ConnectionMethod.QR_PAIRING, tenants, CANNED_IDENTITY),
            viewModel.state.value,
        )
        assertNull(store.saved)

        viewModel.selectTenant(tenants[1])
        advanceUntilIdle()

        val expected = connection.copy(tenantSlug = "greenhouse")
        assertEquals(ConnectionState.Connected(expected), viewModel.state.value)
        assertEquals(expected, store.saved)
    }

    @Test
    fun `a tenant that was never offered is not adopted`() = runTest(dispatcher) {
        val tenants = listOf(Tenant("balcony"), Tenant("greenhouse"))
        val viewModel = viewModel(client = StubConnectionClient(verified(tenants)))

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected(validQr)
        advanceUntilIdle()
        viewModel.selectTenant(Tenant("someone-elses-garden"))
        advanceUntilIdle()

        assertTrue(viewModel.state.value is ConnectionState.SelectingTenant)
    }

    @Test
    fun `a credential without a tenant fails instead of connecting`() = runTest(dispatcher) {
        val store = FakeConnectionStore()
        val viewModel = viewModel(client = StubConnectionClient(verified(tenants = emptyList())), store = store)

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected(validQr)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is ConnectionState.Failed)
        assertNull(store.saved)
    }

    @Test
    fun `a failed change leaves the previous connection in place`() = runTest(dispatcher) {
        val store = FakeConnectionStore(initial = connection)
        val viewModel = viewModel(store = store, credentials = InMemoryCredentialStore(initial = SESSION))
        advanceUntilIdle()

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected(failQr)
        advanceUntilIdle()
        assertTrue(viewModel.state.value is ConnectionState.Failed)
        assertNull(store.saved)

        viewModel.cancel()

        assertEquals(ConnectionState.Connected(connection), viewModel.state.value)
    }

    @Test
    fun `disconnect clears persistence and returns to disconnected`() = runTest(dispatcher) {
        val store = FakeConnectionStore(initial = connection)
        val viewModel = viewModel(store = store)

        viewModel.disconnect()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, viewModel.state.value)
        assertTrue(store.cleared)
    }

    @Test
    fun `a scanner error moves from scanning to camera-unavailable`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onScannerError()

        assertEquals(ConnectionState.CameraUnavailable, viewModel.state.value)
    }

    @Test
    fun `retry from camera-unavailable reopens the scanner`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onScannerError()
        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)

        assertEquals(ConnectionState.Collecting.ScanningQr(), viewModel.state.value)
    }

    @Test
    fun `a scanner error is ignored when not scanning`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onScannerError()

        assertEquals(ConnectionState.Disconnected, viewModel.state.value)
    }

    @Test
    fun `cancel returns from scanning to disconnected`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.cancel()

        assertEquals(ConnectionState.Disconnected, viewModel.state.value)
    }

    @Test
    fun `retry from failed reopens the collection step of the same method`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected(failQr)
        advanceUntilIdle()
        val failed = viewModel.state.value as ConnectionState.Failed

        viewModel.startConnecting(failed.method)

        assertEquals(ConnectionState.Collecting.ScanningQr(), viewModel.state.value)
    }

    // --- the secret half (R17, R19, R25) ---

    @Test
    fun `a verified qr connection stores its session next to the connection`() = runTest(dispatcher) {
        val credentials = InMemoryCredentialStore()
        val viewModel = viewModel(credentials = credentials)

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected(validQr)
        advanceUntilIdle()

        val session = credentials.stored as Credential.Session
        assertEquals(SESSION.accessToken, session.accessToken)
        assertEquals(SESSION.refreshToken, session.refreshToken)
    }

    @Test
    fun `an api key is stored whole while the connection keeps only its mask`() = runTest(dispatcher) {
        val store = FakeConnectionStore()
        val credentials = InMemoryCredentialStore()
        val viewModel = viewModel(store = store, credentials = credentials)

        viewModel.startConnecting(ConnectionMethod.API_KEY)
        viewModel.submit(ConnectionRequest.ApiKey(baseUrl = "https://plants.example.org", key = "kp_sk_abcdef"))
        advanceUntilIdle()

        assertEquals(Credential.ApiKey("kp_sk_abcdef"), credentials.stored)
        assertEquals("…cdef", (store.saved as Connection.ApiKey).keyHint)
    }

    @Test
    fun `light mode stores no credential at all`() = runTest(dispatcher) {
        val credentials = InMemoryCredentialStore(initial = Credential.ApiKey("kp_sk_previous"))
        val viewModel = viewModel(credentials = credentials)

        viewModel.startConnecting(ConnectionMethod.LIGHT_MODE)
        viewModel.submit(ConnectionRequest.LightMode(baseUrl = "https://light.example.org"))
        advanceUntilIdle()

        assertEquals(Credential.None, credentials.stored)
    }

    @Test
    fun `the tenant choice carries the credential without exposing it`() = runTest(dispatcher) {
        val tenants = listOf(Tenant("balcony"), Tenant("greenhouse"))
        val credentials = InMemoryCredentialStore()
        val viewModel = viewModel(client = StubConnectionClient(verified(tenants)), credentials = credentials)

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected(validQr)
        advanceUntilIdle()

        // Nothing is stored yet, and the observable state holds no secret (R19) — neither
        // rendered nor in a field a collector could read. `SelectingTenant` carries only the
        // method, the tenants and the identity; masking `toString` alone would not do, since
        // `state.value` is public and a request field would be readable straight off it.
        assertEquals(Credential.None, credentials.stored)
        val selecting = viewModel.state.value as ConnectionState.SelectingTenant
        val rendered = selecting.toString()
        assertFalse(rendered.contains(SESSION.refreshToken))
        assertFalse(rendered.contains(SESSION.accessToken))
        assertFalse(rendered.contains(request.code))

        viewModel.selectTenant(tenants[1])
        advanceUntilIdle()

        assertEquals(SESSION, credentials.stored)
    }

    @Test
    fun `abandoning the tenant choice drops the credential it was holding`() = runTest(dispatcher) {
        val tenants = listOf(Tenant("balcony"), Tenant("greenhouse"))
        val credentials = InMemoryCredentialStore()
        val viewModel = viewModel(client = StubConnectionClient(verified(tenants)), credentials = credentials)

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected(validQr)
        advanceUntilIdle()
        viewModel.cancel()
        viewModel.selectTenant(tenants[1])
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, viewModel.state.value)
        assertEquals(Credential.None, credentials.stored)
    }

    @Test
    fun `a failed verification stores no credential`() = runTest(dispatcher) {
        val credentials = InMemoryCredentialStore()
        val viewModel = viewModel(credentials = credentials)

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected(failQr)
        advanceUntilIdle()

        assertEquals(Credential.None, credentials.stored)
    }

    @Test
    fun `a credential that cannot be stored leaves no half connection behind`() = runTest(dispatcher) {
        val store = FakeConnectionStore()
        val credentials = InMemoryCredentialStore().apply { failOnSave = true }
        val viewModel = viewModel(store = store, credentials = credentials)

        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)
        viewModel.onQrDetected(validQr)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is ConnectionState.Failed)
        assertNull(store.saved)
        assertEquals(Credential.None, credentials.stored)
        // The write is a single transaction, so a failure wrote nothing and there is nothing
        // to erase. Erasing anyway is the bug the next test pins down.
        assertFalse(credentials.cleared)
    }

    @Test
    fun `a failed credential write leaves an existing connection intact`() = runTest(dispatcher) {
        val store = FakeConnectionStore(initial = connection)
        val credentials = InMemoryCredentialStore(initial = SESSION)
        val viewModel = viewModel(store = store, credentials = credentials)
        advanceUntilIdle()

        // The Keystore key goes away between connecting and re-connecting — a lock-screen
        // change or a restored device invalidates it, and every encrypt from here throws.
        credentials.failOnSave = true
        viewModel.startConnecting(ConnectionMethod.API_KEY)
        viewModel.submit(ConnectionRequest.ApiKey(baseUrl = "https://other.example.org", key = "kp_sk_zzzz"))
        advanceUntilIdle()

        // R14: a failed change is a no-op. The user still has the connection they arrived
        // with — losing it here would punish them for a key the system dropped.
        assertTrue(viewModel.state.value is ConnectionState.Failed)
        assertFalse(credentials.cleared)
        assertEquals(SESSION, credentials.stored)
        assertFalse(store.cleared)
        assertEquals(connection, store.saved ?: connection)

        viewModel.cancel()
        assertEquals(ConnectionState.Connected(connection), viewModel.state.value)
    }

    @Test
    fun `disconnect removes the credential as well as the connection`() = runTest(dispatcher) {
        val store = FakeConnectionStore(initial = connection)
        val credentials = InMemoryCredentialStore(initial = SESSION)
        val viewModel = viewModel(store = store, credentials = credentials)

        viewModel.disconnect()
        advanceUntilIdle()

        assertTrue(credentials.cleared)
        assertEquals(Credential.None, credentials.stored)
        assertTrue(store.cleared)
    }

    private fun verified(tenants: List<Tenant>) = ConnectionResult.Verified(
        identity = CANNED_IDENTITY,
        tenants = tenants,
        credential = SESSION,
    )

    // ── /connect deep link (#13) ────────────────────────────────────────────────────

    private val discovered = DiscoveryLink("https://plants.example")

    private fun connectedTo(baseUrl: String) = viewModel(
        store = FakeConnectionStore(Connection.QrPairing(baseUrl = baseUrl, tenantSlug = "demo")),
        credentials = InMemoryCredentialStore(Credential.Session("at", "rt", 0L)),
    )

    /**
     * The same link, scanned instead of followed, reaches the same offer.
     *
     * It used to start a connection attempt outright. The web UI shows the discovery QR beside
     * the pairing QR, so a camera aimed at the pairing code can pick up the other one first —
     * and a scan that silently replaced a working connection with another instance is the exact
     * outcome the `Discovered` offer exists to prevent.
     */
    @Test
    fun `a scanned discovery link becomes an offer, not an attempt`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.startConnecting(ConnectionMethod.QR_PAIRING)

        val reading = viewModel.onQrDetected("https://plants.example/connect?v=1")
        advanceUntilIdle()

        assertEquals(QrReading.ACCEPTED, reading)
        assertEquals(
            ConnectionState.Discovered("https://plants.example", DiscoveredInstance.NEW),
            viewModel.state.value,
        )
    }

    /** Nothing is connected, so the link is simply an offer. */
    @Test
    fun `a discovered instance with nothing connected is new`() = runTest(dispatcher) {
        discoveries.offer(discovered)

        val model = viewModel()
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Discovered("https://plants.example", DiscoveredInstance.NEW),
            model.state.value,
        )
    }

    /**
     * Scanning the code on the instance you are already connected to should say so rather than
     * walk you through pairing again — and a trailing slash must not make it look like a
     * different instance.
     */
    @Test
    fun `a discovered instance already connected says so`() = runTest(dispatcher) {
        val model = connectedTo("https://plants.example/")
        advanceUntilIdle()

        discoveries.offer(discovered)
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Discovered("https://plants.example", DiscoveredInstance.ALREADY_CONNECTED),
            model.state.value,
        )
    }

    /** Continuing from here replaces a working connection, which the user learns before. */
    @Test
    fun `a discovered instance elsewhere warns that it replaces the connection`() =
        runTest(dispatcher) {
            val model = connectedTo("https://other.example")
            advanceUntilIdle()

            discoveries.offer(discovered)
            advanceUntilIdle()

            assertEquals(
                ConnectionState.Discovered(
                    "https://plants.example",
                    DiscoveredInstance.REPLACES_ANOTHER,
                ),
                model.state.value,
            )
        }

    /**
     * A link can arrive at any moment — the user switches to their camera app mid-pairing and
     * scans the poster again. Discarding a verification for it would be the wrong answer to the
     * more deliberate action, so it waits.
     */
    @Test
    fun `a link arriving mid-verification waits and is offered afterwards`() = runTest(dispatcher) {
        val client = GatedConnectionClient(verified(listOf(CANNED_TENANT)))
        val model = viewModel(client = client)
        advanceUntilIdle()
        model.startConnecting(ConnectionMethod.QR_PAIRING)
        model.onQrDetected(validQr)
        advanceUntilIdle()

        discoveries.offer(discovered)
        advanceUntilIdle()

        assertTrue(model.state.value.toString(), model.state.value is ConnectionState.Verifying)

        // And the other half, which is the one that goes wrong: nothing about a waiting link
        // changes while it waits, so a collector watching only the link would never fire again
        // — the offer would be lost until the process died. The verification is let finish
        // rather than cancelled, because a verification in flight is deliberately not
        // cancellable, and that is exactly the window the link has to survive.
        client.release()
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Discovered("https://plants.example", DiscoveredInstance.REPLACES_ANOTHER),
            model.state.value,
        )
    }

    /** The address the link carried travels into the method the user picks. */
    @Test
    fun `continuing from a discovered instance carries its address along`() = runTest(dispatcher) {
        discoveries.offer(discovered)
        val model = viewModel()
        advanceUntilIdle()

        model.startConnecting(ConnectionMethod.API_KEY)

        assertEquals(
            ConnectionState.Collecting.ApiKeyEntry("https://plants.example"),
            model.state.value,
        )
    }

    /** Dismissing returns to whatever the machine was resting on, having started nothing. */
    @Test
    fun `dismissing a discovered instance leaves nothing behind`() = runTest(dispatcher) {
        discoveries.offer(discovered)
        val model = viewModel()
        advanceUntilIdle()

        model.cancel()

        assertEquals(ConnectionState.Disconnected, model.state.value)
    }

    /** Consumed on arrival, so leaving Settings and returning does not restart the offer. */
    @Test
    fun `a link is acted on once`() = runTest(dispatcher) {
        discoveries.offer(discovered)
        val first = viewModel()
        advanceUntilIdle()
        // Asserted, not assumed: without it a collector that offered the link to nobody would
        // satisfy the second half by doing nothing at all.
        assertTrue(first.state.value.toString(), first.state.value is ConnectionState.Discovered)

        val second = viewModel()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, second.state.value)
    }
}

// --- the canned instance these tests connect to ---
//
// Owned by this test source set rather than shared with the real client. The debug fake this
// used to contrast with is gone — NetworkConnectionClient in :core:network now serves every
// variant — which makes the separation matter more, not less: these tests are about the state
// machine's decisions, so they must not start depending on what a live instance answers or on
// the HTTP client changing underneath them.

private const val CANNED_FAIL_CODE = "fail"
private const val CANNED_IDENTITY = "demo@kamerplanter.local"
private val CANNED_TENANT = Tenant(slug = "demo", displayName = "Demo garden")
private val SESSION = Credential.Session(
    accessToken = "access-token",
    refreshToken = "refresh-token",
    accessTokenExpiresAtEpochMillis = 1_700_000_000_000L,
)

private class FakeConnectionStore(initial: Connection? = null) : ConnectionStore {
    private val flow = MutableStateFlow(initial)
    override val connection: Flow<Connection?> = flow

    var saved: Connection? = null
        private set
    var cleared: Boolean = false
        private set

    override suspend fun save(connection: Connection) {
        saved = connection
        flow.value = connection
    }

    override suspend fun clear() {
        cleared = true
        flow.value = null
    }
}

/**
 * A [ConnectionClient] answering from a canned instance: [CANNED_FAIL_CODE] fails, anything
 * else verifies against the single [CANNED_TENANT], and light mode verifies with neither
 * tenant nor credential. No delay — the tests drive the scheduler themselves.
 */
private class CannedConnectionClient : ConnectionClient {
    override suspend fun connect(request: ConnectionRequest): ConnectionResult = when (request) {
        is ConnectionRequest.QrPairing -> verify(request.code, SESSION)
        is ConnectionRequest.ApiKey -> verify(request.key, Credential.ApiKey(request.key))
        is ConnectionRequest.LightMode -> ConnectionResult.Verified(
            identity = null,
            tenants = emptyList(),
            credential = Credential.None,
        )
    }

    private fun verify(secret: String, credential: Credential): ConnectionResult =
        if (secret.equals(CANNED_FAIL_CODE, ignoreCase = true)) {
            ConnectionResult.Failure("canned instance rejected the credential")
        } else {
            ConnectionResult.Verified(
                identity = CANNED_IDENTITY,
                tenants = listOf(CANNED_TENANT),
                credential = credential,
            )
        }
}

/** A [ConnectionClient] that answers with a fixed result, without any delay. */
private class StubConnectionClient(private val result: ConnectionResult) : ConnectionClient {
    override suspend fun connect(request: ConnectionRequest): ConnectionResult = result
}

/** A [ConnectionClient] whose answer is held until [release], so [ConnectionState.Verifying] is observable. */
private class GatedConnectionClient(private val result: ConnectionResult) : ConnectionClient {
    private val gate = CompletableDeferred<Unit>()

    override suspend fun connect(request: ConnectionRequest): ConnectionResult {
        gate.await()
        return result
    }

    fun release() {
        gate.complete(Unit)
    }
}
