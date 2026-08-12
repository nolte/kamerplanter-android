package io.github.nolte.kamerplanter.feature.settings

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val validQr = "kamerplanter://pair?url=https%3A%2F%2Fplants.example.org&code=ABC123"
    private val payload = PairingPayload(baseUrl = "https://plants.example.org", code = "ABC123")
    private val failQr = "kamerplanter://pair?url=https%3A%2F%2Fx&code=fail"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        client: PairingClient = FakePairingClient(),
        store: PairingStore = FakePairingStore(),
    ) = SettingsViewModel(client, store)

    @Test
    fun `starts paired when a pairing is already persisted`() = runTest(dispatcher) {
        val viewModel = viewModel(store = FakePairingStore(initial = payload))

        assertEquals(PairingState.Paired(payload), viewModel.state.value)
    }

    @Test
    fun `starts idle when nothing is persisted`() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertEquals(PairingState.Idle, viewModel.state.value)
    }

    @Test
    fun `startScan moves from idle to scanning`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startScan()

        assertEquals(PairingState.Scanning, viewModel.state.value)
    }

    @Test
    fun `a valid qr pairs and persists the payload`() = runTest(dispatcher) {
        val store = FakePairingStore()
        val viewModel = viewModel(store = store)

        viewModel.startScan()
        viewModel.onQrDetected(validQr)
        advanceUntilIdle()

        assertEquals(PairingState.Paired(payload), viewModel.state.value)
        assertEquals(payload, store.saved)
    }

    @Test
    fun `the sentinel fail code ends in failed and persists nothing`() = runTest(dispatcher) {
        val store = FakePairingStore()
        val viewModel = viewModel(store = store)

        viewModel.startScan()
        viewModel.onQrDetected(failQr)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is PairingState.Failed)
        assertNull(store.saved)
    }

    @Test
    fun `an unparseable qr is ignored while scanning`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startScan()
        viewModel.onQrDetected("just some scanned text")

        assertEquals(PairingState.Scanning, viewModel.state.value)
    }

    @Test
    fun `qr detection is ignored when not scanning`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onQrDetected(validQr)

        assertEquals(PairingState.Idle, viewModel.state.value)
    }

    @Test
    fun `verifying is entered before the backend responds`() = runTest(dispatcher) {
        val client = GatedPairingClient(PairingResult.Success)
        val viewModel = viewModel(client = client)

        viewModel.startScan()
        viewModel.onQrDetected(validQr)

        assertEquals(PairingState.Verifying(payload), viewModel.state.value)

        client.release()
        advanceUntilIdle()

        assertEquals(PairingState.Paired(payload), viewModel.state.value)
    }

    @Test
    fun `unpair clears persistence and returns to idle`() = runTest(dispatcher) {
        val store = FakePairingStore(initial = payload)
        val viewModel = viewModel(store = store)

        viewModel.unpair()
        advanceUntilIdle()

        assertEquals(PairingState.Idle, viewModel.state.value)
        assertTrue(store.cleared)
    }

    @Test
    fun `a scanner error moves from scanning to camera-unavailable`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startScan()
        viewModel.onScannerError()

        assertEquals(PairingState.CameraUnavailable, viewModel.state.value)
    }

    @Test
    fun `retry from camera-unavailable reopens the scanner`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startScan()
        viewModel.onScannerError()
        viewModel.startScan()

        assertEquals(PairingState.Scanning, viewModel.state.value)
    }

    @Test
    fun `a scanner error is ignored when not scanning`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onScannerError()

        assertEquals(PairingState.Idle, viewModel.state.value)
    }

    @Test
    fun `cancel returns from scanning to idle`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startScan()
        viewModel.cancelScan()

        assertEquals(PairingState.Idle, viewModel.state.value)
    }

    @Test
    fun `retry from failed reopens the scanner`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startScan()
        viewModel.onQrDetected(failQr)
        advanceUntilIdle()
        assertTrue(viewModel.state.value is PairingState.Failed)

        viewModel.startScan()

        assertEquals(PairingState.Scanning, viewModel.state.value)
    }
}

private class FakePairingStore(initial: PairingPayload? = null) : PairingStore {
    private val flow = MutableStateFlow(initial)
    override val pairing: Flow<PairingPayload?> = flow

    var saved: PairingPayload? = null
        private set
    var cleared: Boolean = false
        private set

    override suspend fun save(payload: PairingPayload) {
        saved = payload
        flow.value = payload
    }

    override suspend fun clear() {
        cleared = true
        flow.value = null
    }
}

/** A [PairingClient] whose response is held until [release], so [PairingState.Verifying] is observable. */
private class GatedPairingClient(private val result: PairingResult) : PairingClient {
    private val gate = CompletableDeferred<Unit>()

    override suspend fun pair(payload: PairingPayload): PairingResult {
        gate.await()
        return result
    }

    fun release() {
        gate.complete(Unit)
    }
}
