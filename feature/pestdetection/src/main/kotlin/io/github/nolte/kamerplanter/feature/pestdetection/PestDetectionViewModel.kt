package io.github.nolte.kamerplanter.feature.pestdetection

import android.content.Context
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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

    /** Starts USB monitoring; the screen calls this while it is visible. */
    fun start() = camera.start()

    fun stop() = camera.stop()

    fun createPreviewView(context: Context): View = camera.createPreviewView(context)

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
        if (ready.isUploading) return
        _state.value = ready.copy(isUploading = true)
        viewModelScope.launch {
            val frame = camera.captureFrame().getOrElse {
                _state.value = PestDetectionState.Failed(R.string.pest_failed_capture)
                return@launch
            }
            when (val outcome = detections.detect(frame.jpeg, plantKey = null, language = language)) {
                is DetectionOutcome.Completed ->
                    _state.value = PestDetectionState.Result(frame.jpeg, outcome.detection)
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

    /** Back to the viewfinder from a result, ready for the next frame. */
    fun captureAgain() {
        _state.value = PestDetectionState.Ready()
    }

    private fun RefusedReason.asFailure(): PestDetectionState.Failed = when (this) {
        // Both of these are dead ends rather than retries: the same frame refused for its type
        // will be refused again, and a credential that may not run detections does not gain
        // the permission by being asked twice.
        RefusedReason.UNSUPPORTED_TYPE ->
            PestDetectionState.Failed(R.string.pest_failed_unsupported_type, canRetry = false)
        RefusedReason.NOT_PERMITTED ->
            PestDetectionState.Failed(R.string.pest_failed_not_permitted, canRetry = false)
        // Retryable, because the next frame is a different frame: the microscope retunes per
        // capture, so another shot can come back smaller or decodable where this one did not.
        RefusedReason.TOO_LARGE -> PestDetectionState.Failed(R.string.pest_failed_too_large)
        RefusedReason.NOT_PROCESSABLE ->
            PestDetectionState.Failed(R.string.pest_failed_not_processable)
        // Handled before this function is reached; kept exhaustive so a new reason cannot be
        // added without deciding what it means here.
        RefusedReason.CONSENT_MISSING -> PestDetectionState.Failed(R.string.pest_failed_consent)
    }
}
