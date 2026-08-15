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
 * device: Generalplus `1b3f:2002`, MJPEG (issue #1).
 *
 * Preview and capture need **not** share a resolution. The preview runs in whichever mode the
 * stream negotiated — 1080p on the reference device, since 4K delivers only ~4.7 fps there —
 * while [captureFrame] tries to retune to a larger mode for the shutter moment. A pest
 * photograph is worth that latency; a live preview is not. On a device whose largest mode
 * already fits the preview budget the two coincide, and nothing is retuned.
 *
 * Lifecycle: [createPreviewView] once per UI composition, [start] when the screen
 * becomes visible, [stop] when it leaves. Everything in between is event-driven
 * (USB attach/detach, permission grants) and reported through [state].
 *
 * **This is a shared singleton, and the calls are reference-counted.** More than one screen
 * may hold it at once — Compose composes an incoming destination before disposing the
 * outgoing one, so during a handover both do. Only the first [start] reaches the hardware and
 * only the last [stop] releases it, which is why a screen may call [stop] and still see
 * frames: another screen is holding it. Every [start] therefore needs exactly one [stop], and
 * the preview surface the stream renders into is decided per surface rather than per screen.
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

    /**
     * Takes a hold on the camera. The first one starts USB monitoring — detecting attached UVC
     * devices and requesting USB permission; later ones only register interest.
     */
    fun start()

    /**
     * Releases this caller's hold. Stops the stream and USB monitoring only when it was the
     * last one — see the reference-counting note on the interface.
     */
    fun stop()

    /**
     * Grabs a single frame as a JPEG, **preferring the largest mode the device offers for the
     * negotiated format**, and restores the preview mode afterwards.
     *
     * Both halves of that are attempts, not guarantees, and callers should not promise their
     * users otherwise:
     *
     * - The retune is skipped when no larger mode exists, and the device may refuse the
     *   switch (bandwidth, an unsupported combination). Either way the frame is taken in the
     *   mode that is running, so the capture can come back at preview resolution. "Largest
     *   offered" also means largest *within the negotiated format* — a mode that exists only
     *   under a format the stream did not negotiate is not considered.
     * - Restoring the preview can fail too. It is attempted after every capture, but a failed
     *   restart can leave the stream in the capture mode, which on the reference device means
     *   a preview at ~4.7 fps until the stream is rebuilt.
     *
     * The call therefore takes noticeably longer than one frame interval: retune, await one
     * frame, restore.
     *
     * The zoom set through [zoomBy] applies to the captured region as well, but preview and
     * capture do **not** frame identically. The preview letterboxes the stream into the view
     * before scaling, while the capture crops a centred `1/zoom` of *both* axes — so a
     * zoomed capture can lose content along the axis the preview was still showing, and a
     * capture mode with a different aspect ratio shifts the framing further.
     */
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
