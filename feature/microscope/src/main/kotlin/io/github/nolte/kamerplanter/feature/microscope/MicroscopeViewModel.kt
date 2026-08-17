package io.github.nolte.kamerplanter.feature.microscope

import android.content.Context
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MicroscopeViewModel @Inject constructor(
    private val camera: MicroscopeCamera,
) : ViewModel() {

    private val lastCapture = MutableStateFlow<CapturedFrame?>(null)
    private val captureError = MutableStateFlow<String?>(null)
    private val isCapturing = MutableStateFlow(false)

    /**
     * The device's shutter ring, for the screen to act on **while it is visible**.
     *
     * Collected by the screen rather than here. In `init` it ran for as long as this ViewModel
     * existed, and a tab's ViewModel outlives its visit: pressing the ring from anywhere else
     * in the app captured a frame into a screen nobody was looking at. Harmless while this was
     * the only consumer — and not once a plant's diary grew a capture surface of its own, since
     * the camera drops a second capture for one press (`captureLock.tryLock`). Whichever
     * collector lost the race got nothing back, so the ring appeared to do nothing at all.
     */
    val shutterPresses: Flow<Unit> = camera.buttonPresses
        .filter { it is MicroscopeButton.Shutter }
        .map { }

    val uiState: StateFlow<MicroscopeUiState> = combine(
        camera.state,
        lastCapture,
        captureError,
        isCapturing,
    ) { cameraState, capture, error, capturing ->
        MicroscopeUiState(cameraState, capture, error, capturing)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = MicroscopeUiState(),
    )

    fun createPreviewView(context: Context): View = camera.createPreviewView(context)

    fun start() = camera.start()

    fun stop() = camera.stop()

    fun capture() {
        if (isCapturing.value) {
            return
        }
        isCapturing.value = true
        viewModelScope.launch {
            camera.captureFrame()
                .onSuccess {
                    lastCapture.value = it
                    captureError.value = null
                }
                .onFailure { captureError.value = it.message ?: "capture failed" }
            isCapturing.value = false
        }
    }

    /** Recovers from an engine error without making the user unplug the microscope. */
    fun retry() {
        camera.stop()
        camera.start()
    }

    fun zoomIn() = camera.zoomBy(ZOOM_STEP_PERCENT)

    fun zoomOut() = camera.zoomBy(-ZOOM_STEP_PERCENT)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val ZOOM_STEP_PERCENT = 10
    }
}

data class MicroscopeUiState(
    val camera: MicroscopeState = MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED),
    val lastCapture: CapturedFrame? = null,
    val captureError: String? = null,
    val isCapturing: Boolean = false,
)
