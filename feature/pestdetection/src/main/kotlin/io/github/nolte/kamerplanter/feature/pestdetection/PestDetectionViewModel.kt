package io.github.nolte.kamerplanter.feature.pestdetection

import android.content.Context
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nolte.kamerplanter.core.camera.PhoneCameraShutter
import io.github.nolte.kamerplanter.core.network.ConsentOutcome
import io.github.nolte.kamerplanter.core.network.DetectionOutcome
import io.github.nolte.kamerplanter.core.network.DetectionReadiness
import io.github.nolte.kamerplanter.core.network.PestDetectionClient
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
) : ViewModel() {

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
        _state.update { current ->
            if (current is PestDetectionState.Ready && !current.isUploading) {
                chosenSource = source
                current.copy(source = source)
            } else {
                current
            }
        }
    }

    /** Starts USB monitoring; the screen calls this while it is visible. */
    fun start() = camera.start()

    fun stop() = camera.stop()

    fun createPreviewView(context: Context): View = camera.createPreviewView(context)

    /**
     * Restarts USB monitoring after a refused grant or a failed stream.
     *
     * Those two states are the only ones nothing leaves on its own: a denied USB dialogue is
     * not asked again, and a stream that failed to open is not retried. Everything else —
     * attaching a device, answering the dialogue — arrives as an event.
     */
    fun retryCamera() = camera.restart()

    /** Asks the instance whether it can run a detection, and what stands in the way if not. */
    fun checkInstance() {
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
        chosenSource = ready.source
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
            when (val outcome = detections.detect(jpeg, plantKey = null, language = language)) {
                is DetectionOutcome.Completed ->
                    _state.value = PestDetectionState.Result(jpeg, outcome.detection)
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
     * Back to the viewfinder from a result, ready for the next frame.
     *
     * Keeps the source: someone who just photographed one leaf through the microscope is
     * almost certainly about to photograph another, and sending them back to the picker each
     * time would be a step they did not ask for.
     */
    fun captureAgain() {
        _state.value = PestDetectionState.Ready(source = chosenSource, phoneReady = shutter != null)
    }

    /**
     * The source in play, which outlives [PestDetectionState.Ready].
     *
     * A result carries no source of its own, so this is what tells the screen whether USB
     * monitoring is still wanted while one is on display — and it is what [captureAgain]
     * returns to.
     */
    @Volatile
    var chosenSource: CaptureSource? = null
        private set

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
