package io.github.nolte.kamerplanter.feature.plants

import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.connection.InMemoryCredentialStore
import io.github.nolte.kamerplanter.core.network.AuthenticatedImageClient
import io.github.nolte.kamerplanter.core.network.PlantListOutcome
import io.github.nolte.kamerplanter.core.network.PlantSummary
import io.github.nolte.kamerplanter.core.network.PlantsClient
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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlantListViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        client: PlantsClient = StubPlantsClient(PlantListOutcome.Loaded(listOf(MONSTERA))),
        store: ConnectionStore = FakeConnectionStore(CONNECTED),
    ) = PlantListViewModel(
        plants = client,
        // Not exercised by these tests — they assert state transitions, not image loading —
        // but the ViewModel hands it to the screen, so it has to be real.
        imageClient = AuthenticatedImageClient(OkHttpClient(), InMemoryCredentialStore()),
        connections = store,
    )

    @Test
    fun `shows the plants of a connected instance`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(PlantListState.Content(listOf(MONSTERA)), viewModel.state.value)
    }

    /**
     * The gate this screen exists behind. Nothing about a plant may be fetched or shown while
     * no instance is connected — an empty list would look like "you have no plants".
     */
    @Test
    fun `shows the not-connected state and fetches nothing while disconnected`() = runTest(dispatcher) {
        val client = StubPlantsClient(PlantListOutcome.Loaded(listOf(MONSTERA)))
        val viewModel = viewModel(client = client, store = FakeConnectionStore(null))
        advanceUntilIdle()

        assertEquals(PlantListState.NotConnected, viewModel.state.value)
        assertEquals(0, client.loads)
    }

    /** Disconnecting has to take the list with it, not leave the last tenant's plants up. */
    @Test
    fun `drops the list when the instance disconnects`() = runTest(dispatcher) {
        val store = FakeConnectionStore(CONNECTED)
        val viewModel = viewModel(store = store)
        advanceUntilIdle()

        store.set(null)
        advanceUntilIdle()

        assertEquals(PlantListState.NotConnected, viewModel.state.value)
    }

    @Test
    fun `an instance with no plants reads differently from a failure`() = runTest(dispatcher) {
        val viewModel = viewModel(client = StubPlantsClient(PlantListOutcome.Loaded(emptyList())))
        advanceUntilIdle()

        assertEquals(PlantListState.Empty, viewModel.state.value)
    }

    @Test
    fun `an unreachable instance offers a retry`() = runTest(dispatcher) {
        val viewModel = viewModel(client = StubPlantsClient(PlantListOutcome.Unavailable("boom")))
        advanceUntilIdle()

        assertEquals(PlantListState.Failed(credentialRejected = false), viewModel.state.value)
    }

    /**
     * A refused credential is the one failure a retry cannot fix, so the screen has to be
     * able to tell it apart and send the user to Settings instead.
     */
    @Test
    fun `a rejected credential is distinguishable from an unreachable instance`() = runTest(dispatcher) {
        val viewModel = viewModel(client = StubPlantsClient(PlantListOutcome.Unauthorized))
        advanceUntilIdle()

        assertEquals(PlantListState.Failed(credentialRejected = true), viewModel.state.value)
    }

    @Test
    fun `retry loads again`() = runTest(dispatcher) {
        val client = StubPlantsClient(PlantListOutcome.Unavailable("boom"))
        val viewModel = viewModel(client = client)
        advanceUntilIdle()

        client.outcome = PlantListOutcome.Loaded(listOf(MONSTERA))
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(PlantListState.Content(listOf(MONSTERA)), viewModel.state.value)
    }

    /**
     * DataStore re-emits on every write, including writes that change nothing this screen
     * cares about. Reloading on those would blank the list mid-scroll.
     */
    @Test
    fun `an unrelated connection write does not reload`() = runTest(dispatcher) {
        val client = StubPlantsClient(PlantListOutcome.Loaded(listOf(MONSTERA)))
        val store = FakeConnectionStore(CONNECTED)
        viewModel(client = client, store = store)
        advanceUntilIdle()

        // Same instance, same tenant, different displayable identity.
        store.set(CONNECTED.copy(identity = "someone@example.org"))
        advanceUntilIdle()

        assertEquals(1, client.loads)
    }

    /**
     * A slow load against the previous instance must not land after the new one and put the
     * old tenant's plants on screen.
     */
    @Test
    fun `switching instance cancels the load in flight`() = runTest(dispatcher) {
        val client = GatedPlantsClient()
        val store = FakeConnectionStore(CONNECTED)
        val viewModel = viewModel(client = client, store = store)
        advanceUntilIdle()

        store.set(CONNECTED.copy(tenantSlug = "other"))
        advanceUntilIdle()
        assertEquals("the switch should have started a second load", 2, client.calls)

        // Answer the first load — the one that was cancelled — with plants belonging to the
        // instance the user has just left. They must not reach the screen.
        client.release(call = 0, outcome = PlantListOutcome.Loaded(listOf(MONSTERA)))
        advanceUntilIdle()

        assertTrue(viewModel.state.value is PlantListState.Loading)
    }
}

// --- doubles -------------------------------------------------------------------------

private val CONNECTED = Connection.QrPairing(
    baseUrl = "https://plants.example.org",
    tenantSlug = "demo",
)

private val MONSTERA = PlantSummary(
    key = "plant-7",
    displayName = "Monstera",
    species = "Swiss cheese plant",
    location = "Living room",
    thumbnailUrl = null,
    careAction = null,
)

private class StubPlantsClient(var outcome: PlantListOutcome) : PlantsClient {
    var loads: Int = 0
        private set

    override suspend fun loadPlants(): PlantListOutcome {
        loads++
        return outcome
    }
}

/**
 * Gives every call its own gate, so the loads can be answered individually. A single shared
 * gate could not tell a cancelled load from a live one — releasing it would answer both.
 */
private class GatedPlantsClient : PlantsClient {
    private val gates = mutableListOf<CompletableDeferred<PlantListOutcome>>()

    override suspend fun loadPlants(): PlantListOutcome {
        val gate = CompletableDeferred<PlantListOutcome>()
        gates += gate
        return gate.await()
    }

    val calls: Int get() = gates.size

    fun release(call: Int, outcome: PlantListOutcome) {
        gates[call].complete(outcome)
    }
}

private class FakeConnectionStore(initial: Connection?) : ConnectionStore {
    private val flow = MutableStateFlow(initial)
    override val connection: Flow<Connection?> = flow

    fun set(connection: Connection?) {
        flow.value = connection
    }

    override suspend fun save(connection: Connection) {
        flow.value = connection
    }

    override suspend fun clear() {
        flow.value = null
    }
}
