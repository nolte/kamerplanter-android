package io.github.nolte.kamerplanter.feature.plants

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nolte.kamerplanter.core.camera.JpegDownscale
import io.github.nolte.kamerplanter.core.network.ActionOutcome
import io.github.nolte.kamerplanter.core.network.AuthenticatedImageClient
import io.github.nolte.kamerplanter.core.network.CareAction
import io.github.nolte.kamerplanter.core.network.Detection
import io.github.nolte.kamerplanter.core.network.DetectionHistoryOutcome
import io.github.nolte.kamerplanter.core.network.DetectionReadiness
import io.github.nolte.kamerplanter.core.network.DiaryEntry
import io.github.nolte.kamerplanter.core.network.DiaryOutcome
import io.github.nolte.kamerplanter.core.network.PestDetectionClient
import io.github.nolte.kamerplanter.core.network.PlantActionsClient
import io.github.nolte.kamerplanter.core.network.PlantDataChanges
import io.github.nolte.kamerplanter.core.network.PlantDetail
import io.github.nolte.kamerplanter.core.network.PlantPageClient
import io.github.nolte.kamerplanter.core.network.PlantPhase
import io.github.nolte.kamerplanter.core.network.PlantPhoto
import io.github.nolte.kamerplanter.core.network.SectionOutcome
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * How one section of the page is doing.
 *
 * Per section rather than per page, because the page loads six things from six endpoints and
 * they fail independently: a phase history the instance will not answer must cost the phase
 * section a line of text, not the plant's master data, its photos and its diary (#11).
 */
sealed interface SectionState<out T> {

    data object Loading : SectionState<Nothing>

    data class Loaded<T>(val value: T) : SectionState<T>

    /** [reason] is the instance's own words, shown under a retry for this section alone. */
    data class Failed(val reason: String) : SectionState<Nothing>
}

/** What is in it, for a section that loaded; `null` while it has not. */
val <T> SectionState<T>.valueOrNull: T? get() = (this as? SectionState.Loaded)?.value

/** The sections a reader can ask to be loaded again. */
enum class PlantSection { HEADER, CARE, PHOTOS, PHASES, DIARY, PEST_CHECKS }

/** What the plant's page is showing. */
data class PlantDetailUiState(
    /**
     * The plant itself. Its failure is the page's failure — everything else hangs off the
     * plant, and six sections retrying against a plant that could not be read would be six
     * ways of saying the same thing.
     */
    val header: SectionState<PlantDetail> = SectionState.Loading,
    val care: SectionState<List<CareAction>> = SectionState.Loading,
    val photos: SectionState<List<PlantPhoto>> = SectionState.Loading,
    val phases: SectionState<List<PlantPhase>> = SectionState.Loading,
    val diary: SectionState<List<DiaryEntry>> = SectionState.Loading,
    val pestChecks: SectionState<List<Detection>> = SectionState.Loading,
    /**
     * Whether the instance offers pest detection at all.
     *
     * Asked here rather than left to the detection screen, so the action is hidden instead of
     * leading somewhere that says "your instance does not offer this" — the same
     * `GET /pest-detection/status` gate, read once, at the point where the button is drawn.
     */
    val detectionAvailable: Boolean = false,
    /** No such plant on this instance: a stale link, or one removed since it was opened. */
    val isGone: Boolean = false,
    /** The credential was refused; nothing on this page will load until it is replaced. */
    val credentialRefused: Boolean = false,
    /** Set while a confirmation or a diary write is in flight; the buttons wait on it. */
    val isWorking: Boolean = false,
    /**
     * An action failed, on a page that is otherwise fine.
     *
     * Separate from a section failure because they need opposite treatment: a section states
     * its own problem in place, while a failed confirmation must leave everything visible —
     * the user is about to try again, and blanking the plant they were looking at is not help.
     */
    val actionError: String? = null,
    /** A completed action, for the confirmation the user needs in order to stop pressing. */
    val actionDone: PlantAction? = null,
) {

    /** The plant, once it is known. */
    val plant: PlantDetail? get() = header.valueOrNull

    /** The photo the plant is recognised by, where it has one. */
    val cover: PlantPhoto? get() = photos.valueOrNull?.firstOrNull { it.isCover }

    /** The most pressing open task, which is the one the page offers to clear. */
    val openTask: CareAction? get() = care.valueOrNull?.firstOrNull()
}

/** The actions this page offers, so the screen can report which of them just finished. */
enum class PlantAction { WATERED, CARE_CONFIRMED, NOTE_ADDED }

/**
 * Everything one plant's page reads from, in one place.
 *
 * Three clients rather than one: the page's own sections, the writes it offers, and the
 * detection feature it gates a button on. Bundled because they are handed to this ViewModel
 * together and mean nothing apart from it.
 */
class PlantPageSources @Inject constructor(
    val page: PlantPageClient,
    val actions: PlantActionsClient,
    /**
     * Asked for two things only: whether the pest-check action is worth offering at all, and
     * what this plant has been checked for before.
     */
    val detections: PestDetectionClient,
)

/**
 * One plant, and the things that can be done to it.
 *
 * The page loads itself from the plant key — `GET /plant-instances/{key}` and the five
 * endpoints around it — rather than picking its plant out of the list the tab loaded. That is
 * what lets it survive process death and a deep link, and it is the only way to reach the
 * master data, the phase history and the removal fields, none of which a list row carries.
 *
 * Each section loads on its own and fails on its own (#11). The two answers that end the whole
 * page are the two that are true of every section at once: a credential nothing will load
 * with, and a plant that is not there.
 */
@HiltViewModel
class PlantDetailViewModel @Inject constructor(
    /** Signs thumbnail requests; the page renders the same photo the list row showed. */
    val imageClient: AuthenticatedImageClient,
    private val sources: PlantPageSources,
    /**
     * The USB microscope, behind the app-owned interface (ADR 0001). This module never sees
     * the UVC engine — only what a diary photo needs: a preview to aim with and a frame.
     */
    camera: MicroscopeCamera,
    changes: PlantDataChanges,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val plantKey: String = checkNotNull(savedStateHandle[PLANT_KEY_ARG]) {
        "a plant page needs the key of a plant"
    }

    /**
     * The microscope, ready for a diary photo.
     *
     * A frame is re-encoded like every other diary photo: the engine hands back whatever the
     * sensor produced, and five of those in one entry is how an upload times out.
     */
    internal val microscope = MicroscopeAccess(
        state = camera.state,
        buttonPresses = camera.buttonPresses,
        createPreviewView = camera::createPreviewView,
        start = camera::start,
        stop = camera::stop,
        capture = {
            // Off the main thread: decode, scale and re-encode of a full frame is tens of
            // milliseconds, and on the caller's dispatcher — the composition's — it freezes
            // the very spinner that is meant to show the capture running.
            withContext(Dispatchers.Default) {
                camera.captureFrame().getOrNull()?.let {
                    JpegDownscale.toUploadable(it.jpeg, MAX_PHOTO_BYTES)
                }
            }
        },
    )

    private val _state = MutableStateFlow(PlantDetailUiState())
    val state: StateFlow<PlantDetailUiState> = _state.asStateFlow()

    /**
     * The loads in flight, one per section.
     *
     * Held per section so a retry cancels only its own: a reader pressing "try again" on the
     * phase history must not restart a diary load that is halfway through.
     */
    private val work = mutableMapOf<PlantSection, Job>()

    init {
        load()
        // What a completed pest check changed, this page has to show. Only the sections a
        // check can touch are reloaded: the photos and the master data are the same as they
        // were, and re-fetching them would blank half the page for news about the other half.
        viewModelScope.launch {
            changes.changes.collect {
                reload(PlantSection.PEST_CHECKS)
                reload(PlantSection.CARE)
            }
        }
    }

    /** Loads every section. Each lands on its own, so the page fills in as answers arrive. */
    fun load() {
        PlantSection.entries.forEach(::reload)
        // Not a section: nothing is rendered for it, and it decides only whether one button
        // exists. A failure here hides the button, which is what an unavailable feature means.
        viewModelScope.launch {
            val readiness = sources.detections.readiness()
            _state.update { it.copy(detectionAvailable = readiness.offersDetection()) }
        }
    }

    /** Loads one section again, from the button that section shows when it failed. */
    fun reload(section: PlantSection) {
        work[section]?.cancel()
        work[section] = viewModelScope.launch {
            _state.update { it.withSection(section, SectionState.Loading) }
            when (section) {
                PlantSection.HEADER -> load(section) { sources.page.plant(plantKey) }
                PlantSection.CARE -> load(section) { sources.page.care(plantKey) }
                PlantSection.PHOTOS -> load(section) { sources.page.photos(plantKey) }
                PlantSection.PHASES -> load(section) { sources.page.phaseHistory(plantKey) }
                PlantSection.DIARY -> loadDiary()
                PlantSection.PEST_CHECKS -> loadPestChecks()
            }
        }
    }

    /**
     * Runs one section's call and files what came back.
     *
     * The two page-wide answers are lifted out of the section: a refused credential and a
     * plant that is gone are true of every section at once, and six copies of the same
     * sentence with six retry buttons would be the page shouting.
     */
    private suspend fun <T : Any> load(
        section: PlantSection,
        block: suspend () -> SectionOutcome<T>,
    ) {
        when (val outcome = block()) {
            is SectionOutcome.Loaded ->
                _state.update {
                    it.withSection(section, SectionState.Loaded<Any>(outcome.value))
                }
            SectionOutcome.Unauthorized ->
                _state.update { it.copy(credentialRefused = true) }
            SectionOutcome.NotFound ->
                // Only the plant itself being gone means the page is: a 404 from the phase
                // endpoint is an instance that does not keep phase history, not a missing
                // plant, and taking the page down for it would be a lie about the plant.
                if (section == PlantSection.HEADER) {
                    _state.update { it.copy(isGone = true) }
                } else {
                    _state.update { it.withSection(section, SectionState.Loaded(emptyList<Any>())) }
                }
            is SectionOutcome.Unavailable ->
                _state.update { it.withSection(section, SectionState.Failed(outcome.reason)) }
        }
    }

    private suspend fun loadDiary() {
        val outcome = sources.actions.diary(plantKey)
        _state.update {
            when (outcome) {
                is DiaryOutcome.Loaded -> it.copy(diary = SectionState.Loaded(outcome.entries))
                DiaryOutcome.Unauthorized -> it.copy(credentialRefused = true)
                is DiaryOutcome.Unavailable -> it.copy(diary = SectionState.Failed(outcome.reason))
            }
        }
    }

    private suspend fun loadPestChecks() {
        val outcome = sources.detections.history(plantKey)
        _state.update {
            when (outcome) {
                is DetectionHistoryOutcome.Loaded ->
                    it.copy(pestChecks = SectionState.Loaded(outcome.detections))
                DetectionHistoryOutcome.Unauthorized -> it.copy(credentialRefused = true)
                // A permission that does not reach past detections is not a broken page, and
                // the section says so rather than offering a retry that cannot change it.
                DetectionHistoryOutcome.NotPermitted ->
                    it.copy(pestChecks = SectionState.Loaded(emptyList()))
                is DetectionHistoryOutcome.Failed ->
                    it.copy(pestChecks = SectionState.Failed(outcome.reason))
            }
        }
    }

    /** Records watering, whether or not a reminder for it is showing. */
    fun water() = act(PlantAction.WATERED) { sources.actions.confirmCare(plantKey, KIND_WATERING) }

    /** Clears whichever care task the badge is showing. */
    fun confirmCare(kind: String) =
        act(PlantAction.CARE_CONFIRMED) { sources.actions.confirmCare(plantKey, kind) }

    /**
     * Writes a diary entry.
     *
     * Text is required, and the guard is here as well as in the dialogue's save button because
     * the endpoint declares `minLength: 1`: an entry with photos and no words is a reasonable
     * thing to want and a 422 from the instance. The button is what tells the user; this is
     * what keeps a caller from finding out the hard way.
     */
    fun addNote(text: String, photos: List<ByteArray>, captureEnvironment: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        act(PlantAction.NOTE_ADDED) {
            sources.actions.addNote(plantKey, trimmed, photos, captureEnvironment)
        }
    }

    /** Dismisses whichever of the two messages is showing, so it does not outlive its moment. */
    fun clearMessages() = _state.update { it.copy(actionError = null, actionDone = null) }

    /**
     * Runs one action and reloads.
     *
     * The reload is what makes the page honest: confirming a watering clears a reminder on the
     * instance, and a page that kept showing "overdue" after the user cleared it would teach
     * them that the button does not work.
     */
    private fun act(action: PlantAction, block: suspend () -> ActionOutcome) {
        if (_state.value.isWorking) return
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true, actionError = null, actionDone = null) }
            when (val outcome = block()) {
                ActionOutcome.Done -> {
                    _state.update { it.copy(isWorking = false, actionDone = action) }
                    // Only what a write can have changed. Reloading the whole page would
                    // re-fetch the photos and the phase history for a confirmed watering, and
                    // blank both of them for as long as that took.
                    reload(PlantSection.CARE)
                    reload(PlantSection.DIARY)
                }
                is ActionOutcome.Failed ->
                    _state.update { it.copy(isWorking = false, actionError = outcome.reason) }
            }
        }
    }

    companion object {

        /** The navigation argument this page is opened with. */
        const val PLANT_KEY_ARG = "plantKey"

        private const val KIND_WATERING = "watering"

        /** The same ceiling the phone-camera and library sources use. */
        private const val MAX_PHOTO_BYTES = 4 * 1024 * 1024
    }
}

/**
 * Puts a section's new state where it belongs.
 *
 * A `when` rather than six copies of `copy(...)` at every call site, so adding a section is
 * one branch here instead of a search through the loader.
 */
@Suppress("UNCHECKED_CAST")
private fun PlantDetailUiState.withSection(
    section: PlantSection,
    state: SectionState<Any>,
): PlantDetailUiState = when (section) {
    PlantSection.HEADER -> copy(header = state as SectionState<PlantDetail>)
    PlantSection.CARE -> copy(care = state as SectionState<List<CareAction>>)
    PlantSection.PHOTOS -> copy(photos = state as SectionState<List<PlantPhoto>>)
    PlantSection.PHASES -> copy(phases = state as SectionState<List<PlantPhase>>)
    PlantSection.DIARY -> copy(diary = state as SectionState<List<DiaryEntry>>)
    PlantSection.PEST_CHECKS -> copy(pestChecks = state as SectionState<List<Detection>>)
}

/**
 * Whether the pest-check action is worth offering.
 *
 * A consent that has not been given yet counts as available: the detection flow asks for it,
 * and hiding the way in would leave the user with no way to grant it. Everything else — the
 * feature switched off, no adapter, a scope that does not reach detections, an answer this
 * build cannot read — hides the action rather than leading to a screen that only says no.
 */
private fun DetectionReadiness.offersDetection(): Boolean = when (this) {
    DetectionReadiness.Ready, is DetectionReadiness.ConsentRequired -> true
    DetectionReadiness.NotOffered,
    DetectionReadiness.NotConnected,
    DetectionReadiness.Unauthorized,
    DetectionReadiness.NotPermitted,
    DetectionReadiness.NotUnderstood,
    is DetectionReadiness.Unavailable,
    -> false
}
