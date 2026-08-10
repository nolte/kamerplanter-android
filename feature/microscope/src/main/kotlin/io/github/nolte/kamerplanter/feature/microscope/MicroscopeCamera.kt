package io.github.nolte.kamerplanter.feature.microscope

import kotlinx.coroutines.flow.StateFlow

/**
 * App-owned seam in front of the UVC engine (ADR 0001, isolation rule).
 *
 * The implementation wraps a libuvc-based library (currently AndroidUSBCamera/AUSBC)
 * and is the only place in the codebase allowed to touch it, so the engine can be
 * swapped without changing callers. Reference device: Generalplus `1b3f:2002`,
 * MJPEG, captured at 1920x1080 (issue #1).
 */
interface MicroscopeCamera {

    val state: StateFlow<MicroscopeState>

    /** Detects an attached UVC device and requests USB-host permission if needed. */
    suspend fun connect(): Result<Unit>

    /** Grabs a single frame from the running stream as a JPEG. */
    suspend fun captureFrame(): Result<CapturedFrame>

    fun disconnect()
}

sealed interface MicroscopeState {
    /** No UVC device attached, or the device lacks USB-host/OTG support. */
    data class Unavailable(val reason: UnavailableReason) : MicroscopeState

    /** Device detected, waiting for the user to grant USB permission. */
    data object AwaitingPermission : MicroscopeState

    /** Stream is open; a live preview can be rendered. */
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
