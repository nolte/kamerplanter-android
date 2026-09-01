package io.github.nolte.kamerplanter.feature.pestdetection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nolte.kamerplanter.core.camera.PhoneCameraShutter
import io.github.nolte.kamerplanter.core.network.ActionOutcome
import io.github.nolte.kamerplanter.core.network.ConsentOutcome
import io.github.nolte.kamerplanter.core.network.DetectionFeedback
import io.github.nolte.kamerplanter.core.network.DetectionHistoryOutcome
import io.github.nolte.kamerplanter.core.network.DetectionOutcome
import io.github.nolte.kamerplanter.core.network.DetectionReadiness
import io.github.nolte.kamerplanter.core.network.FeedbackOutcome
import io.github.nolte.kamerplanter.core.network.InspectionOutcome
import io.github.nolte.kamerplanter.core.network.PestDetectionClient
import io.github.nolte.kamerplanter.core.network.PlantActionsClient
import io.github.nolte.kamerplanter.core.network.PlantDataChanges
import io.github.nolte.kamerplanter.core.network.RefusedReason
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeCamera
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives one pass of "photograph something small, ask the instance what it is".
 *
 * The order of operations is the design. Gating comes first, then consent, then the frame: a
 * captured image is a thing the user expects to be used, so nothing is captured until the
 * instance has said it will look at it and — where a cloud adapter is active — until the user
 * has agreed to it leaving the instance. No recognition happens here or anywhere else in the
 * app; the frame is uploaded and the answer is rendered.
 */
@HiltViewModel
class PestDetectionViewModel @Inject constructor(
    private val detections: PestDetectionClient,
    private val camera: MicroscopeCamera,
    /**
     * Announces a completed check to whatever is showing the plant.
     *
     * The plant's page is still on the back stack behind this screen, holding a pest-check
     * section that was accurate when it loaded and is not any more. Told through the same bus
     * a watering uses, it refreshes on return rather than waiting for the user to leave and
     * come back (#11).
     */
    private val changes: PlantDataChanges,
    /**
     * Where a kept frame goes (F-3). The detection endpoint deliberately never persists the
     * image, so keeping it is a second, explicit upload into the plant's photo gallery —
     * plant business, not detection business, which is why it is this client and not
     * [PestDetectionClient].
     */
    private val plants: PlantActionsClient,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * The plant a finding belongs to, or `null` when the flow was entered without one.
     *
     * Read from the navigation arguments rather than passed in, so the two entry points — the
     * Capture tab and a plant's own page — differ only in the route they push.
     */
    private val plantKey: String? = savedStateHandle[PLANT_KEY_ARG]

    private val _state = MutableStateFlow<PestDetectionState>(PestDetectionState.CheckingInstance)
    val state: StateFlow<PestDetectionState> = _state.asStateFlow()

    /**
     * Whether a microscope is attached and streaming.
     *
     * Separate from [state] because it changes on its own — a cable comes loose mid-flow —
     * while [state] follows what the user did. The screen needs both: a [PestDetectionState.Ready]
     * with no device attached is a shutter that cannot fire.
     */
    val cameraState: StateFlow<MicroscopeState> = camera.state

    private val _history = MutableStateFlow<DetectionHistoryState>(DetectionHistoryState.Hidden)

    /**
     * The plant's past detections, when the user has asked for them.
     *
     * Beside [state] rather than in it: the list is opened over whatever the flow is currently
     * showing and closed again without disturbing it, and a capture in progress must not be
     * thrown away because somebody looked something up.
     */
    val history: StateFlow<DetectionHistoryState> = _history.asStateFlow()

    /** Whether past checks and an inspection can be offered at all — both need a plant. */
    val isPlantBound: Boolean = plantKey != null

    private val _chosenSource = MutableStateFlow<CaptureSource?>(null)

    /**
     * The source in play, which outlives [PestDetectionState.Ready].
     *
     * A result carries no source of its own, so this is what tells the screen whether USB
     * monitoring is still wanted while one is on display — and it is what [captureAgain]
     * returns to. A flow rather than a field because the screen reads it: a plain read from a
     * composition registers nothing, so a change would move nothing until some unrelated
     * recomposition happened to notice.
     */
    val chosenSource: StateFlow<CaptureSource?> = _chosenSource.asStateFlow()

    // Declared before the init below, which reaches it through checkInstance(): a property
    // initialised further down the class would still be null when that runs.
    init {
        checkInstance()
    }

    /**
     * The phone camera's shutter, handed over by the composition that binds it.
     *
     * Held here rather than driven from the screen so both sources have one entry point:
     * [capture] decides which to use, and the caller does not have to know that one of them
     * only exists while a particular composable is on screen.
     */
    @Volatile
    private var shutter: PhoneCameraShutter? = null

    /**
     * Handed over by the composition that binds the phone camera, and `null` when it unbinds.
     *
     * Mirrored into the state because the shutter is what the button needs: offering it before
     * binding finishes ends the flow on an error screen rather than doing nothing.
     */
    var phoneShutter: PhoneCameraShutter?
        get() = shutter
        set(value) {
            shutter = value
            _state.update { current ->
                if (current is PestDetectionState.Ready) {
                    current.copy(phoneReady = value != null)
                } else {
                    current
                }
            }
        }

    /** Chooses where the next frame comes from. A no-op once one is being uploaded. */
    fun chooseSource(source: CaptureSource?) {
        // Read, guard, then write both — rather than a side effect inside `update`, whose
        // lambda re-runs on a lost compare-and-set. Moving the assignment out of that lambda
        // also moved it out of the guard once, and the two must not drift: the screen decides
        // whether USB monitoring runs from `chosenSource`, so a change accepted there but
        // refused in the state pops a USB dialogue over a phone capture that is mid-upload.
        val current = _state.value
        if (current !is PestDetectionState.Ready || current.isUploading) return
        _chosenSource.value = source
        // Cleared alongside the source: keeping a stale "the phone is bound" across a switch
        // would offer the shutter for a camera that is not the chosen one.
        _state.value = current.copy(
            source = source,
            phoneReady = source == CaptureSource.PHONE && shutter != null,
        )
    }

    /**
     * The microscope, for the composables that monitor and preview it.
     *
     * Handed over rather than wrapped in pass-through methods: starting monitoring, binding a
     * preview and restarting after a refused USB grant are the camera's own vocabulary, and
     * four methods here that only forwarded them made this class look as if it had a say in
     * any of it. The app-owned interface is what crosses the boundary, so ADR 0001's rule
     * still holds — no UVC engine type leaves `:feature:microscope`.
     */
    val microscope: MicroscopeCamera get() = camera

    /** Asks the instance whether it can run a detection, and what stands in the way if not. */
    fun checkInstance() {
        // The source goes with it. Re-asking the instance lands back on the picker, and a
        // source left over from the last attempt would keep USB monitoring switched off — so
        // the microscope option would sit there frozen at whatever it last reported, and a
        // device plugged in since would never be noticed.
        _chosenSource.value = null
        _state.value = PestDetectionState.CheckingInstance
        viewModelScope.launch {
            _state.value = when (val readiness = detections.readiness()) {
                DetectionReadiness.Ready -> PestDetectionState.Ready()
                is DetectionReadiness.ConsentRequired ->
                    PestDetectionState.ConsentRequired(readiness.purpose, readiness.terms)
                DetectionReadiness.NotOffered -> PestDetectionState.NotOffered
                DetectionReadiness.NotConnected -> PestDetectionState.NotConnected
                DetectionReadiness.Unauthorized -> PestDetectionState.Unauthorized
                DetectionReadiness.NotPermitted -> PestDetectionState.NotPermitted
                DetectionReadiness.NotUnderstood -> PestDetectionState.NotUnderstood
                is DetectionReadiness.Unavailable ->
                    PestDetectionState.Failed(R.string.pest_failed_unreachable)
            }
        }
    }

    /**
     * Records the consent the active adapter needs, then re-checks.
     *
     * Re-checking rather than assuming success: the grant answers *this* adapter's
     * requirement, and an instance whose active adapter changed in between would otherwise be
     * treated as ready on the strength of a consent that no longer applies to it.
     */
    fun grantConsent() {
        val pending = _state.value as? PestDetectionState.ConsentRequired ?: return
        if (pending.isGranting) return
        _state.value = pending.copy(isGranting = true)
        viewModelScope.launch {
            when (detections.grantConsent(pending.purpose)) {
                ConsentOutcome.Granted -> checkInstance()
                ConsentOutcome.Unauthorized -> _state.value = PestDetectionState.Unauthorized
                ConsentOutcome.NotPermitted -> _state.value = PestDetectionState.NotPermitted
                is ConsentOutcome.Failed ->
                    _state.value = PestDetectionState.Failed(R.string.pest_failed_consent)
            }
        }
    }

    /**
     * Takes one frame from the microscope and asks the instance about it.
     *
     * @param language the language the labels and the disclaimer come back in — the UI's own,
     *   passed in by the screen so what the user reads matches what the app is written in.
     */
    fun capture(language: String) {
        val ready = _state.value as? PestDetectionState.Ready ?: return
        if (ready.isUploading || ready.source == null) return
        _state.value = ready.copy(isUploading = true)
        viewModelScope.launch {
            val jpeg = when (ready.source) {
                CaptureSource.MICROSCOPE -> camera.captureFrame().getOrNull()?.jpeg
                // The phone's own downscale: a modern sensor produces several megabytes where
                // the microscope produces a few hundred kilobytes, and a photo the instance
                // would refuse is not worth the upload it takes to find that out.
                CaptureSource.PHONE -> shutter?.capture(MAX_UPLOAD_BYTES)
            }
            if (jpeg == null) {
                _state.value = PestDetectionState.Failed(ready.source.captureFailure())
                return@launch
            }
            // The plant this was opened for, where it was opened from one. Entered from the
            // Capture tab there is none, and the instance files the finding without a plant.
            when (val outcome = detections.detect(jpeg, plantKey = plantKey, language = language)) {
                is DetectionOutcome.Completed -> {
                    _state.value = PestDetectionState.Result(
                        frame = jpeg,
                        detection = outcome.detection,
                        plantBound = isPlantBound,
                    )
                    // Only a plant-bound check changes anything another screen is showing:
                    // a standalone detection is filed against no plant and no page holds it.
                    if (isPlantBound) changes.notifyChanged()
                }
                DetectionOutcome.Unauthorized -> _state.value = PestDetectionState.Unauthorized
                is DetectionOutcome.Unavailable ->
                    _state.value = PestDetectionState.Failed(R.string.pest_failed_unreachable)
                is DetectionOutcome.Refused ->
                    // A missing consent is reachable despite the pre-flight check: it can be
                    // revoked in the web UI between asking and uploading. Re-checking puts the
                    // user back in front of the consent question instead of a retry button
                    // that would send the same frame into the same refusal.
                    if (outcome.reason == RefusedReason.CONSENT_MISSING) {
                        checkInstance()
                    } else {
                        _state.value = outcome.reason.asFailure()
                    }
            }
        }
    }

    /**
     * Records what the user says about one finding.
     *
     * The instance answers with the detection as it now holds it, and that is what goes on
     * screen: a verdict shown because the app assumed the POST worked is a verdict that
     * disagrees with the instance the moment anything went wrong.
     *
     * Ignored for a detection the instance did not persist — no key, nothing to comment on —
     * and while another verdict is in flight, so two taps cannot race into one row.
     */
    fun recordFeedback(findingLabel: String, verdict: FeedbackVerdict) {
        val shown = _state.value as? PestDetectionState.Result ?: return
        val detectionKey = shown.detection.key ?: return
        if (shown.recordingFor != null) return
        _state.value = shown.copy(recordingFor = findingLabel, notice = null)
        viewModelScope.launch {
            val feedback = DetectionFeedback(
                findingLabel = findingLabel,
                confirmed = verdict == FeedbackVerdict.CORRECT,
                // Never invented: the app knows the recognizer was wrong, not what the animal
                // was, and filling this in from the category would teach the instance a label
                // nobody looked at.
                actualLabel = null,
                wasBeneficial = verdict == FeedbackVerdict.BENEFICIAL,
            )
            val outcome = detections.submitFeedback(detectionKey, feedback)
            // Re-read rather than reusing `shown`: an upload or a "capture again" may have
            // replaced the whole state while the POST was out, and writing a stale result back
            // would put a finished detection on top of a viewfinder.
            val current = _state.value as? PestDetectionState.Result ?: return@launch
            if (current.detection.key != detectionKey) return@launch
            _state.value = when (outcome) {
                is FeedbackOutcome.Recorded -> current.copy(
                    detection = outcome.detection,
                    recordingFor = null,
                    notice = R.string.pest_feedback_recorded,
                )
                FeedbackOutcome.NotPermitted ->
                    current.copy(recordingFor = null, notice = R.string.pest_feedback_not_permitted)
                FeedbackOutcome.Unauthorized -> PestDetectionState.Unauthorized
                is FeedbackOutcome.Failed ->
                    current.copy(recordingFor = null, notice = R.string.pest_feedback_failed)
            }
        }
    }

    /**
     * Files this detection as an IPM inspection on the plant it was run for.
     *
     * Offered only on the plant-bound path, because that is the only one the endpoint accepts.
     * Creating one needs a permission that running a detection does not, so a refusal is an
     * ordinary answer here and reads as a sentence beside the findings.
     */
    fun fileInspection() {
        val shown = _state.value as? PestDetectionState.Result ?: return
        val detectionKey = shown.detection.key ?: return
        val plant = plantKey ?: return
        if (shown.filingInspection || shown.inspectionFiled) return
        _state.value = shown.copy(filingInspection = true, notice = null)
        viewModelScope.launch {
            val outcome = detections.createInspection(detectionKey, plant)
            val current = _state.value as? PestDetectionState.Result ?: return@launch
            if (current.detection.key != detectionKey) return@launch
            _state.value = when (outcome) {
                is InspectionOutcome.Created -> current.copy(
                    filingInspection = false,
                    inspectionFiled = true,
                    notice = R.string.pest_inspection_created,
                )
                InspectionOutcome.NotPermitted -> current.copy(
                    filingInspection = false,
                    notice = R.string.pest_inspection_not_permitted,
                )
                InspectionOutcome.Unauthorized -> PestDetectionState.Unauthorized
                is InspectionOutcome.Failed ->
                    current.copy(filingInspection = false, notice = R.string.pest_inspection_failed)
            }
        }
    }

    /**
     * Keeps the captured frame as a photo of the plant (F-3).
     *
     * Offered only on the plant-bound path and only as an explicit action: the detection
     * upload does not store the image, so this second upload is the one and only way the
     * frame is ever persisted — nothing is kept without the user asking for it.
     */
    fun keepPhoto() {
        val shown = _state.value as? PestDetectionState.Result ?: return
        val plant = plantKey ?: return
        if (shown.keepingPhoto || shown.photoKept) return
        _state.value = shown.copy(keepingPhoto = true, notice = null)
        viewModelScope.launch {
            val outcome = plants.addPhoto(plant, shown.frame)
            val current = _state.value as? PestDetectionState.Result ?: return@launch
            // A new capture may have replaced the result while the upload ran; its frame is
            // not the one that was kept, and it must not inherit this one's verdict.
            if (!current.frame.contentEquals(shown.frame)) return@launch
            _state.value = when (outcome) {
                ActionOutcome.Done -> current.copy(
                    keepingPhoto = false,
                    photoKept = true,
                    notice = R.string.pest_photo_kept,
                )
                // Same three answers as filing an inspection beside it: a refused credential
                // ends the flow in Settings, a missing role is a sentence and no retry, and
                // only a transient failure leaves the offer open.
                ActionOutcome.Unauthorized -> PestDetectionState.Unauthorized
                ActionOutcome.NotPermitted -> current.copy(
                    keepingPhoto = false,
                    photoKept = true,
                    notice = R.string.pest_photo_keep_not_permitted,
                )
                is ActionOutcome.Failed -> current.copy(
                    keepingPhoto = false,
                    notice = R.string.pest_photo_keep_failed,
                )
            }
        }
    }

    /** Loads and shows the plant's past detections. A no-op without a plant to ask about. */
    fun showHistory() {
        val plant = plantKey ?: return
        if (_history.value is DetectionHistoryState.Loading) return
        _history.value = DetectionHistoryState.Loading
        viewModelScope.launch {
            _history.value = when (val outcome = detections.history(plant)) {
                is DetectionHistoryOutcome.Loaded -> DetectionHistoryState.Shown(outcome.detections)
                DetectionHistoryOutcome.Unauthorized ->
                    DetectionHistoryState.Failed(R.string.pest_history_unauthorized)
                DetectionHistoryOutcome.NotPermitted ->
                    DetectionHistoryState.Failed(R.string.pest_history_not_permitted)
                is DetectionHistoryOutcome.Failed ->
                    DetectionHistoryState.Failed(R.string.pest_history_failed)
            }
        }
    }

    fun hideHistory() {
        _history.value = DetectionHistoryState.Hidden
    }

    /**
     * Back to the viewfinder from a result, ready for the next frame.
     *
     * Keeps the source: someone who just photographed one leaf through the microscope is
     * almost certainly about to photograph another, and sending them back to the picker each
     * time would be a step they did not ask for.
     */
    fun captureAgain() {
        _state.update {
            PestDetectionState.Ready(
                source = _chosenSource.value,
                phoneReady = _chosenSource.value == CaptureSource.PHONE && shutter != null,
            )
        }
    }

    private companion object {
        /**
         * What the app will upload, mirroring the instance's own default limit. The phone path
         * re-encodes down to this; the microscope's frames are already well inside it.
         */
        const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024
    }
}

/** Which capture failed, in the words that name the camera the user chose. */
private fun CaptureSource.captureFailure() = when (this) {
    CaptureSource.MICROSCOPE -> R.string.pest_failed_capture
    CaptureSource.PHONE -> R.string.pest_failed_phone_capture
}

private fun RefusedReason.asFailure(): PestDetectionState.Failed = when (this) {
    // Both of these are dead ends rather than retries: the same frame refused for its type
    // will be refused again, and a credential that may not run detections does not gain the
    // permission by being asked twice.
    RefusedReason.UNSUPPORTED_TYPE ->
        PestDetectionState.Failed(R.string.pest_failed_unsupported_type, canRetry = false)
    RefusedReason.NOT_PERMITTED ->
        PestDetectionState.Failed(R.string.pest_failed_not_permitted, canRetry = false)
    // No retry: a proxy's body cap is a fixed number, and every capture is over it. The
    // message names what has to change and who can change it.
    RefusedReason.REFUSED_BY_PROXY ->
        PestDetectionState.Failed(R.string.pest_failed_proxy_limit, canRetry = false)
    // Retryable, because the next frame is a different frame: the microscope retunes per
    // capture, so another shot can come back smaller or decodable where this one did not.
    RefusedReason.TOO_LARGE -> PestDetectionState.Failed(R.string.pest_failed_too_large)
    RefusedReason.NOT_PROCESSABLE ->
        PestDetectionState.Failed(R.string.pest_failed_not_processable)
    // Handled before this function is reached; kept exhaustive so a new reason cannot be
    // added without deciding what it means here.
    RefusedReason.CONSENT_MISSING -> PestDetectionState.Failed(R.string.pest_failed_consent)
}

/** The optional navigation argument naming the plant a detection is filed against. */
const val PLANT_KEY_ARG: String = "plantKey"
