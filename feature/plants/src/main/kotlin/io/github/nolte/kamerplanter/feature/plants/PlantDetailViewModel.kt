package io.github.nolte.kamerplanter.feature.plants

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nolte.kamerplanter.core.camera.JpegDownscale
import io.github.nolte.kamerplanter.core.network.ActionOutcome
import io.github.nolte.kamerplanter.core.network.AuthenticatedImageClient
import io.github.nolte.kamerplanter.core.network.DiaryEntry
import io.github.nolte.kamerplanter.core.network.DiaryOutcome
import io.github.nolte.kamerplanter.core.network.PlantActionsClient
import io.github.nolte.kamerplanter.core.network.PlantListOutcome
import io.github.nolte.kamerplanter.core.network.PlantSummary
import io.github.nolte.kamerplanter.core.network.PlantsClient
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

/** What the plant's page is showing. */
data class PlantDetailUiState(
    val plant: PlantSummary? = null,
    val diary: List<DiaryEntry> = emptyList(),
    val isLoading: Boolean = true,
    /** Set while a confirmation or a diary write is in flight; the buttons wait on it. */
    val isWorking: Boolean = false,
    /** The page could not be shown at all — a wrong key, a lost connection. */
    val loadError: String? = null,
    /**
     * An action failed, on a page that is otherwise fine.
     *
     * Separate from [loadError] because they need opposite treatment: a load error replaces
     * the page, while a failed confirmation must leave everything visible — the user is about
     * to try again, and blanking the plant they were looking at is not help.
     */
    val actionError: String? = null,
    /** A completed action, for the confirmation the user needs in order to stop pressing. */
    val actionDone: PlantAction? = null,
)

/** The actions this page offers, so the screen can report which of them just finished. */
enum class PlantAction { WATERED, CARE_CONFIRMED, NOTE_ADDED }

/**
 * One plant, and the things that can be done to it.
 *
 * The plant itself comes from the same list load the Plants tab uses, selected by key, rather
 * than from `GET /plant-instances/{key}`: the list has already resolved the location name, the
 * cover photo and the open care action, and the single-plant endpoint returns none of those
 * joined — reading it would mean repeating three joins to show the same four fields. The cost
 * is that opening a plant loads the tenant's list; the benefit is that this page and the row
 * it was opened from can never disagree.
 */
@HiltViewModel
class PlantDetailViewModel @Inject constructor(
    /** Signs thumbnail requests; the page renders the same photo the list row showed. */
    val imageClient: AuthenticatedImageClient,
    private val plants: PlantsClient,
    private val actions: PlantActionsClient,
    /**
     * The USB microscope, behind the app-owned interface (ADR 0001). This module never sees
     * the UVC engine — only what a diary photo needs: a preview to aim with and a frame.
     */
    camera: MicroscopeCamera,
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

    private var work: Job? = null

    init {
        load()
    }

    fun load() {
        work?.cancel()
        work = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadError = null) }
            val plant = when (val outcome = plants.loadPlants()) {
                is PlantListOutcome.Loaded -> outcome.plants.firstOrNull { it.key == plantKey }
                PlantListOutcome.Unauthorized ->
                    return@launch fail("the instance refused the stored credential")
                is PlantListOutcome.Unavailable -> return@launch fail(outcome.reason)
            }
            if (plant == null) return@launch fail("this plant is no longer on the instance")

            // The diary is loaded second and its failure is not fatal: a plant whose diary
            // cannot be read is still a plant whose care can be confirmed.
            val diary = (actions.diary(plantKey) as? DiaryOutcome.Loaded)?.entries.orEmpty()
            _state.update {
                it.copy(plant = plant, diary = diary, isLoading = false, loadError = null)
            }
        }
    }

    /** Records watering, whether or not a reminder for it is showing. */
    fun water() = act(PlantAction.WATERED) { actions.confirmCare(plantKey, KIND_WATERING) }

    /** Clears whichever care task the badge is showing. */
    fun confirmCare(kind: String) =
        act(PlantAction.CARE_CONFIRMED) { actions.confirmCare(plantKey, kind) }

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
            actions.addNote(plantKey, trimmed, photos, captureEnvironment)
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
                    load()
                }
                is ActionOutcome.Failed ->
                    _state.update { it.copy(isWorking = false, actionError = outcome.reason) }
            }
        }
    }

    private fun fail(reason: String) =
        _state.update { it.copy(isLoading = false, loadError = reason) }

    companion object {

        /** The navigation argument this page is opened with. */
        const val PLANT_KEY_ARG = "plantKey"

        private const val KIND_WATERING = "watering"

        /** The same ceiling the phone-camera and library sources use. */
        private const val MAX_PHOTO_BYTES = 4 * 1024 * 1024
    }
}
