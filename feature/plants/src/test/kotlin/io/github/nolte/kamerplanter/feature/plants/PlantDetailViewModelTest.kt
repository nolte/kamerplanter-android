package io.github.nolte.kamerplanter.feature.plants

import android.content.Context
import android.view.View
import androidx.lifecycle.SavedStateHandle
import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.FakeConnectionStore
import io.github.nolte.kamerplanter.core.connection.InMemoryCredentialStore
import io.github.nolte.kamerplanter.core.network.ActionOutcome
import io.github.nolte.kamerplanter.core.network.AuthenticatedImageClient
import io.github.nolte.kamerplanter.core.network.CareAction
import io.github.nolte.kamerplanter.core.network.ConsentOutcome
import io.github.nolte.kamerplanter.core.network.Detection
import io.github.nolte.kamerplanter.core.network.DetectionFeedback
import io.github.nolte.kamerplanter.core.network.DetectionHistoryOutcome
import io.github.nolte.kamerplanter.core.network.DetectionOutcome
import io.github.nolte.kamerplanter.core.network.DetectionReadiness
import io.github.nolte.kamerplanter.core.network.DiaryOutcome
import io.github.nolte.kamerplanter.core.network.FeedbackOutcome
import io.github.nolte.kamerplanter.core.network.InspectionOutcome
import io.github.nolte.kamerplanter.core.network.PestDetectionClient
import io.github.nolte.kamerplanter.core.network.PlantActionsClient
import io.github.nolte.kamerplanter.core.network.PlantDataChanges
import io.github.nolte.kamerplanter.core.network.PlantDetail
import io.github.nolte.kamerplanter.core.network.PlantPageClient
import io.github.nolte.kamerplanter.core.network.PlantPhase
import io.github.nolte.kamerplanter.core.network.PlantPhoto
import io.github.nolte.kamerplanter.core.network.SectionOutcome
import io.github.nolte.kamerplanter.feature.microscope.CapturedFrame
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeButton
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeCamera
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The plant page, section by section.
 *
 * What these tests are really about is independence: six endpoints answer this page, they fail
 * separately, and the whole point of #11 is that one of them failing costs its own section and
 * nothing else. The two exceptions — a refused credential and a plant that is gone — are true
 * of every section at once, and are asserted as such.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlantDetailViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val page = FakePageClient()
    private val actions = FakeActionsClient()
    private val detections = FakeDetectionClient()
    private val changes = PlantDataChanges()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): PlantDetailViewModel {
        val store = FakeConnectionStore(
            Connection.ApiKey(baseUrl = "https://x", tenantSlug = "demo", keyHint = "…x"),
        )
        return PlantDetailViewModel(
            imageClient = AuthenticatedImageClient(OkHttpClient(), InMemoryCredentialStore(), store),
            sources = PlantPageSources(page = page, actions = actions, detections = detections),
            camera = FakeCamera(),
            changes = changes,
            savedStateHandle = SavedStateHandle(mapOf(PlantDetailViewModel.PLANT_KEY_ARG to "p1")),
        )
    }

    @Test
    fun `every section loads on its own`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(PLANT, state.plant)
        assertEquals(listOf(COVER), state.photos.valueOrNull)
        assertEquals(listOf(PHASE), state.phases.valueOrNull)
        assertEquals(listOf(TASK), state.care.valueOrNull)
        assertEquals(1, state.diary.valueOrNull?.size)
        assertEquals(listOf(CHECK), state.pestChecks.valueOrNull)
    }

    /**
     * The failure that used to blank the page. A phase history the instance will not answer
     * says so where the phases would be, and the plant, its photos and its diary stay put.
     */
    @Test
    fun `a failing section costs only itself`() = runTest(dispatcher) {
        page.phases = SectionOutcome.Unavailable("the instance answered HTTP 500")
        val model = viewModel()
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(
            SectionState.Failed("the instance answered HTTP 500"),
            state.phases,
        )
        assertEquals(PLANT, state.plant)
        assertEquals(listOf(COVER), state.photos.valueOrNull)
        assertFalse(state.credentialRefused)
    }

    /** And it can be asked again on its own, without re-fetching the five that worked. */
    @Test
    fun `retrying one section reloads only that section`() = runTest(dispatcher) {
        page.phases = SectionOutcome.Unavailable("nope")
        val model = viewModel()
        advanceUntilIdle()
        val photoLoads = page.photoCalls

        page.phases = SectionOutcome.Loaded(listOf(PHASE))
        model.reload(PlantSection.PHASES)
        advanceUntilIdle()

        assertEquals(listOf(PHASE), model.state.value.phases.valueOrNull)
        assertEquals("the photos should not have been fetched again", photoLoads, page.photoCalls)
    }

    /** True of every section at once: no section will load, and no retry will change that. */
    @Test
    fun `a refused credential takes the page, not one section`() = runTest(dispatcher) {
        page.plant = SectionOutcome.Unauthorized
        val model = viewModel()
        advanceUntilIdle()

        assertTrue(model.state.value.credentialRefused)
    }

    /**
     * A 404 on the plant itself means it is gone. A 404 from a section endpoint means the
     * instance does not keep that kind of record — taking the page down for it would be a lie
     * about a plant that is perfectly present.
     */
    @Test
    fun `a missing plant ends the page while a missing section does not`() = runTest(dispatcher) {
        page.phases = SectionOutcome.NotFound
        val model = viewModel()
        advanceUntilIdle()

        assertFalse(model.state.value.isGone)
        assertEquals(emptyList<PlantPhase>(), model.state.value.phases.valueOrNull)

        page.plant = SectionOutcome.NotFound
        val gone = viewModel()
        advanceUntilIdle()

        assertTrue(gone.state.value.isGone)
    }

    @Test
    fun `the pest action follows what the instance offers`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.NotOffered
        val hidden = viewModel()
        advanceUntilIdle()
        assertFalse(hidden.state.value.detectionAvailable)

        // A consent that has not been given yet still counts: the detection flow is where it
        // is granted, so hiding the way in would leave no way to grant it.
        detections.readiness = DetectionReadiness.ConsentRequired("pest_detection_cloud")
        val offered = viewModel()
        advanceUntilIdle()
        assertTrue(offered.state.value.detectionAvailable)
    }

    /**
     * The page is still on the back stack while a pest check runs in front of it, holding a
     * section that was accurate when it loaded. Coming back to a list of checks that does not
     * include the one just made reads as the check having gone nowhere.
     */
    @Test
    fun `a completed pest check refreshes the checks and the care, not the photos`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            val photoLoads = page.photoCalls
            val careLoads = page.careCalls
            detections.history = DetectionHistoryOutcome.Loaded(listOf(CHECK, CHECK.copy(key = "det-2")))

            changes.notifyChanged()
            advanceUntilIdle()

            assertEquals(2, model.state.value.pestChecks.valueOrNull?.size)
            assertEquals(careLoads + 1, page.careCalls)
            assertEquals(photoLoads, page.photoCalls)
        }

    /**
     * A write reloads what a write can have changed. Reloading everything would re-fetch the
     * photos and the phase history for a confirmed watering, and blank both while it did.
     */
    @Test
    fun `confirming a task reloads the care and the diary, not the whole page`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            val photoLoads = page.photoCalls
            val careLoads = page.careCalls
            val diaryLoads = actions.diaryCalls

            model.water()
            advanceUntilIdle()

            assertEquals(careLoads + 1, page.careCalls)
            assertEquals(diaryLoads + 1, actions.diaryCalls)
            assertEquals(photoLoads, page.photoCalls)
        }
}

// --- doubles -------------------------------------------------------------------------

private val PLANT = PlantDetail(
    key = "p1",
    displayName = "Monstera",
    species = "Swiss cheese plant",
    location = "Living room",
    plantedOn = "2026-03-14",
    removal = null,
    phase = PlantPhase(name = "vegetative", startedAt = "2026-04-01"),
    containerVolumeLiters = 12.0,
    substrate = "soil",
    cultivationCycle = "perennial",
    motherKey = null,
)

private val COVER = PlantPhoto(url = "https://x/photo.jpg", isCover = true)
private val PHASE = PlantPhase(name = "seedling", startedAt = "2026-03-14", endedAt = "2026-04-01")
private val TASK = CareAction(kind = "watering", urgency = "overdue", dueDate = "2026-08-11")
private val CHECK = Detection(
    key = "det-1",
    isConfident = true,
    findings = emptyList(),
    disclaimer = "Only an estimate.",
    suggestedNextStep = "Look again",
    tilesProcessed = 4,
    recordedAt = "2026-08-01T10:00:00Z",
)

private class FakePageClient : PlantPageClient {

    var plant: SectionOutcome<PlantDetail> = SectionOutcome.Loaded(PLANT)
    var photos: SectionOutcome<List<PlantPhoto>> = SectionOutcome.Loaded(listOf(COVER))
    var phases: SectionOutcome<List<PlantPhase>> = SectionOutcome.Loaded(listOf(PHASE))
    var care: SectionOutcome<List<CareAction>> = SectionOutcome.Loaded(listOf(TASK))

    var photoCalls = 0
        private set
    var careCalls = 0
        private set

    override suspend fun plant(key: String): SectionOutcome<PlantDetail> = plant

    override suspend fun photos(key: String): SectionOutcome<List<PlantPhoto>> {
        photoCalls++
        return photos
    }

    override suspend fun phaseHistory(key: String): SectionOutcome<List<PlantPhase>> = phases

    override suspend fun care(key: String): SectionOutcome<List<CareAction>> {
        careCalls++
        return care
    }
}

private class FakeActionsClient : PlantActionsClient {

    var diaryOutcome: DiaryOutcome = DiaryOutcome.Loaded(
        listOf(
            io.github.nolte.kamerplanter.core.network.DiaryEntry(
                key = "d1",
                kind = "note",
                title = null,
                text = "Repotted.",
                createdAt = "2026-08-01",
                photoUrls = emptyList(),
                environment = emptyList(),
                environmentStatus = null,
            ),
        ),
    )

    var diaryCalls = 0
        private set

    override suspend fun diary(plantKey: String): DiaryOutcome {
        diaryCalls++
        return diaryOutcome
    }

    override suspend fun addNote(
        plantKey: String,
        text: String,
        photos: List<ByteArray>,
        captureEnvironment: Boolean,
    ): ActionOutcome = ActionOutcome.Done

    override suspend fun confirmCare(plantKey: String, kind: String): ActionOutcome =
        ActionOutcome.Done
}

private class FakeDetectionClient : PestDetectionClient {

    var readiness: DetectionReadiness = DetectionReadiness.Ready
    var history: DetectionHistoryOutcome = DetectionHistoryOutcome.Loaded(listOf(CHECK))

    override suspend fun readiness(): DetectionReadiness = readiness

    override suspend fun grantConsent(purpose: String): ConsentOutcome = ConsentOutcome.Granted

    override suspend fun detect(
        jpeg: ByteArray,
        plantKey: String?,
        language: String,
    ): DetectionOutcome = DetectionOutcome.Unavailable("not used here")

    override suspend fun submitFeedback(
        detectionKey: String,
        feedback: DetectionFeedback,
    ): FeedbackOutcome = FeedbackOutcome.Failed("not used here")

    override suspend fun createInspection(
        detectionKey: String,
        plantKey: String,
    ): InspectionOutcome = InspectionOutcome.Failed("not used here")

    override suspend fun history(plantKey: String, limit: Int): DetectionHistoryOutcome = history
}

/** Enough of a microscope for a page that never opens one in these tests. */
private class FakeCamera : MicroscopeCamera {

    override val state: StateFlow<MicroscopeState> =
        MutableStateFlow(MicroscopeState.Unavailable(reason()))

    override val buttonPresses: SharedFlow<MicroscopeButton> = MutableSharedFlow()

    override fun createPreviewView(context: Context): View = error("no preview in a JVM test")

    override fun start() = Unit

    override fun stop() = Unit

    override suspend fun captureFrame(): Result<CapturedFrame> =
        Result.failure(IllegalStateException("no device"))

    override fun zoomBy(deltaPercent: Int) = Unit

    private fun reason() =
        io.github.nolte.kamerplanter.feature.microscope.UnavailableReason.NO_DEVICE_ATTACHED
}
