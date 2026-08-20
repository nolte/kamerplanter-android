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
import io.github.nolte.kamerplanter.core.network.DIARY_PAGE_SIZE
import io.github.nolte.kamerplanter.core.network.DIARY_PHOTOS_MAX
import io.github.nolte.kamerplanter.core.network.DIARY_TEXT_MAX
import io.github.nolte.kamerplanter.core.network.DIARY_TITLE_MAX
import io.github.nolte.kamerplanter.core.network.Detection
import io.github.nolte.kamerplanter.core.network.DetectionFeedback
import io.github.nolte.kamerplanter.core.network.DetectionHistoryOutcome
import io.github.nolte.kamerplanter.core.network.DetectionOutcome
import io.github.nolte.kamerplanter.core.network.DetectionReadiness
import io.github.nolte.kamerplanter.core.network.DiaryDraft
import io.github.nolte.kamerplanter.core.network.DiaryEntry
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

    // --- the diary (#12) --------------------------------------------------------------

    /** A full page means there may be more; the control that says so is the reader's. */
    @Test
    fun `a full page of entries offers older ones`() = runTest(dispatcher) {
        actions.pages = mapOf(0 to DiaryOutcome.Loaded(page(DIARY_PAGE_SIZE), hasMore = true))
        val model = viewModel()
        advanceUntilIdle()

        assertTrue(model.state.value.diaryHasMore)
    }

    /**
     * Offset from what is on screen rather than from a page number, and deduplicated: a diary
     * written into while the reader scrolls shifts the window, and the same entry arriving
     * twice would break the list's own keys.
     */
    @Test
    fun `older entries are appended, not repeated`() = runTest(dispatcher) {
        val first = page(DIARY_PAGE_SIZE)
        actions.pages = mapOf(
            0 to DiaryOutcome.Loaded(first, hasMore = true),
            // The last of the first page comes back again, as a diary written into meanwhile
            // would produce.
            DIARY_PAGE_SIZE to DiaryOutcome.Loaded(listOf(first.last(), ENTRY.copy(key = "older")), hasMore = false),
        )
        val model = viewModel()
        advanceUntilIdle()

        model.loadOlderDiary()
        advanceUntilIdle()

        val shown = model.state.value.diary.valueOrNull.orEmpty()
        assertEquals(DIARY_PAGE_SIZE + 1, shown.size)
        assertEquals(shown.size, shown.distinctBy { it.key }.size)
        assertEquals(listOf(0, DIARY_PAGE_SIZE), actions.offsets)
        assertFalse(model.state.value.diaryHasMore)
    }

    /** Nothing to append when the instance has already said there is nothing more. */
    @Test
    fun `no more entries means no request`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        val calls = actions.diaryCalls

        model.loadOlderDiary()
        advanceUntilIdle()

        assertEquals(calls, actions.diaryCalls)
    }

    @Test
    fun `an entry is written as its kind, with its title trimmed`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.saveEntry(
            DiaryDraft(entryType = "problem", title = "  Spider mites  ", text = "  Under the leaves  "),
        )
        advanceUntilIdle()

        val sent = actions.added.single()
        assertEquals("problem", sent.entryType)
        assertEquals("Spider mites", sent.title)
        assertEquals("Under the leaves", sent.text)
    }

    /**
     * The endpoint's own limits, applied before the request. An entry refused by the instance
     * costs a round trip and comes back naming a field; one stopped here is still on screen.
     */
    @Test
    fun `an entry past the endpoint's limits is not sent`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.saveEntry(DiaryDraft(text = ""))
        model.saveEntry(DiaryDraft(text = "x".repeat(DIARY_TEXT_MAX + 1)))
        model.saveEntry(DiaryDraft(title = "t".repeat(DIARY_TITLE_MAX + 1), text = "fine"))
        model.saveEntry(DiaryDraft(text = "fine", newPhotos = List(DIARY_PHOTOS_MAX + 1) { byteArrayOf(1) }))
        advanceUntilIdle()

        assertTrue(actions.added.isEmpty())
    }

    /**
     * `PUT` replaces what it is given, so an edit has to carry the photos the entry already
     * had — otherwise saving a typo fix would silently drop every picture with it.
     */
    @Test
    fun `an edit carries the photos the entry already had`() = runTest(dispatcher) {
        val existing = ENTRY.copy(photoRefs = listOf("a1", "a2"))
        actions.pages = mapOf(0 to DiaryOutcome.Loaded(listOf(existing)))
        val model = viewModel()
        advanceUntilIdle()

        model.saveEntry(DiaryDraft(text = "Fixed a typo", photoRefs = existing.photoRefs), editing = existing.key)
        advanceUntilIdle()

        val (key, draft) = actions.updated.single()
        assertEquals("d1", key)
        assertEquals(listOf("a1", "a2"), draft.photoRefs)
        assertTrue(actions.added.isEmpty())
    }

    @Test
    fun `deleting and analysing name the entry they act on`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.deleteEntry("d1")
        advanceUntilIdle()
        model.requestAnalysis("d1")
        advanceUntilIdle()

        assertEquals(listOf("d1"), actions.deleted)
        assertEquals(listOf("d1"), actions.analysed)
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

    /** Pages, keyed by the offset they answer; the default is one full page and nothing more. */
    var pages: Map<Int, DiaryOutcome> = mapOf(0 to DiaryOutcome.Loaded(listOf(ENTRY)))

    var diaryCalls = 0
        private set
    val offsets = mutableListOf<Int>()
    val added = mutableListOf<DiaryDraft>()
    val updated = mutableListOf<Pair<String, DiaryDraft>>()
    val deleted = mutableListOf<String>()
    val analysed = mutableListOf<String>()

    override suspend fun diary(plantKey: String, offset: Int, limit: Int): DiaryOutcome {
        diaryCalls++
        offsets += offset
        return pages[offset] ?: DiaryOutcome.Loaded(emptyList())
    }

    override suspend fun addEntry(plantKey: String, draft: DiaryDraft): ActionOutcome {
        added += draft
        return ActionOutcome.Done
    }

    override suspend fun updateEntry(
        plantKey: String,
        entryKey: String,
        draft: DiaryDraft,
    ): ActionOutcome {
        updated += entryKey to draft
        return ActionOutcome.Done
    }

    override suspend fun deleteEntry(plantKey: String, entryKey: String): ActionOutcome {
        deleted += entryKey
        return ActionOutcome.Done
    }

    override suspend fun requestAnalysis(plantKey: String, entryKey: String): ActionOutcome {
        analysed += entryKey
        return ActionOutcome.Done
    }

    override suspend fun confirmCare(plantKey: String, kind: String): ActionOutcome =
        ActionOutcome.Done
}

/** A page of distinct entries, for the paging tests. */
private fun page(size: Int) = List(size) { ENTRY.copy(key = "d$it") }

private val ENTRY = DiaryEntry(
    key = "d1",
    kind = "note",
    title = null,
    text = "Repotted.",
    createdAt = "2026-08-01",
    photoUrls = emptyList(),
    environment = emptyList(),
    environmentStatus = null,
)

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
