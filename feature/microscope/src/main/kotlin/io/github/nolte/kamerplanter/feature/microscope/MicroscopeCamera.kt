package io.github.nolte.kamerplanter.feature.microscope

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App-owned seam in front of the UVC engine (ADR 0001, isolation rule).
 *
 * The implementation drives `libuvc` directly and is the only place in the codebase
 * allowed to touch it, so the engine can be swapped without changing callers. Reference
 * device: Generalplus `1b3f:2002`, MJPEG, captured at 1920x1080 (issue #1).
 *
 * Lifecycle: [createPreviewView] once per UI composition, [start] when the screen
 * becomes visible, [stop] when it leaves. Everything in between is event-driven
 * (USB attach/detach, permission grants) and reported through [state].
 */
interface MicroscopeCamera {

    val state: StateFlow<MicroscopeState>

    /**
     * Presses of the physical buttons on the microscope body, delivered over the UVC
     * status endpoint. Silent on devices whose buttons only drive their own firmware.
     */
    val buttonPresses: SharedFlow<MicroscopeButton>

    /**
     * Creates the live-preview surface the stream renders into. The returned view is
     * meant to be embedded via Compose `AndroidView`; the stream starts on it as soon
     * as both the surface and a permitted UVC device are available.
     */
    fun createPreviewView(context: Context): View

    /** Starts USB monitoring: detects attached UVC devices and requests USB permission. */
    fun start()

    /** Stops the stream and USB monitoring. */
    fun stop()

    /** Grabs a single frame from the running stream as a JPEG. */
    suspend fun captureFrame(): Result<CapturedFrame>

    /**
     * Magnifies the preview and subsequent captures by [deltaPercent] more (or less).
     *
     * Digital, because neither hardware path is available on the reference device: its
     * zoom rocker is wired to its own firmware rather than to USB, and it does not
     * implement the UVC zoom control either (issue #1). Optical magnification stays with
     * the microscope's lens; this only frames the detail more tightly.
     */
    fun zoomBy(deltaPercent: Int)
}

/**
 * A physical button on the microscope body.
 *
 * Only the shutter has a meaning in UVC. Measured on the reference device (issue #1):
 * its zoom rocker sends nothing over USB at all — those keys drive the microscope's own
 * firmware for its standalone WiFi mode — so zoom is offered on screen instead.
 */
sealed interface MicroscopeButton {
    /** The shutter — UVC's standard still-image button, index 1. */
    data object Shutter : MicroscopeButton

    /** A button this device numbers differently; logged so it can be mapped later. */
    data class Unknown(val index: Int) : MicroscopeButton
}

sealed interface MicroscopeState {
    /** No UVC device attached, or the device lacks USB-host/OTG support. */
    data class Unavailable(val reason: UnavailableReason) : MicroscopeState

    /** Device detected, waiting for the user to grant USB permission. */
    data object AwaitingPermission : MicroscopeState

    /**
     * A permitted device is attached but no stream is open on it — the state between
     * closing a stream and opening the next one, which is what a backgrounded screen
     * passes through. Distinct from [Unavailable] because the device is still there.
     */
    data object Connecting : MicroscopeState

    /** Stream is open; the live preview is rendering. */
    data object Streaming : MicroscopeState

    data class Error(val message: String) : MicroscopeState
}

enum class UnavailableReason {
    NO_DEVICE_ATTACHED,
    NO_USB_HOST_SUPPORT,
    PERMISSION_DENIED,
}

class CapturedFrame(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
)
