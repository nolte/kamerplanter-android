package io.github.nolte.kamerplanter.feature.microscope

import android.util.Log
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.uvc.UVCCamera

/**
 * The parts of the UVC engine that AUSBC keeps to itself.
 *
 * [CameraUVC] wraps the `UVCCamera` owning the status endpoint — the channel the buttons
 * on the microscope body report over — but never exposes its button callback, so the
 * engine object is read reflectively. Confined to `feature/microscope` by the ADR 0001
 * isolation rule, and the field is kept by the app's `proguard-rules.pro`.
 */
internal object UvcEngine {

    /** True when the device implements the UVC zoom control, so [CameraUVC.setZoom] bites. */
    fun zoomSupported(camera: CameraUVC?): Boolean =
        engineOf(camera)?.checkSupportFlag(UVCCamera.CTRL_ZOOM_ABS.toLong()) == true

    /**
     * Routes button presses to [onButton]. Both the button and the status callback stay
     * logged raw: bodies vary in how they number their buttons, and a device that does
     * route its zoom rocker over USB reports it here as a control change rather than a
     * button. Fires on a native thread.
     */
    fun observeButtons(camera: CameraUVC?, onButton: (MicroscopeButton) -> Unit) {
        val engine = engineOf(camera) ?: return
        engine.setButtonCallback { button, state ->
            Log.i(TAG, "microscope button event: button=$button state=$state")
            if (state == BUTTON_STATE_PRESSED) {
                onButton(button.toMicroscopeButton())
            }
        }
        engine.setStatusCallback { statusClass, event, selector, attribute, _ ->
            Log.i(
                TAG,
                "microscope status event: class=$statusClass event=$event " +
                    "selector=$selector attribute=$attribute",
            )
        }
    }

    private fun engineOf(camera: CameraUVC?): UVCCamera? {
        camera ?: return null
        return runCatching {
            CameraUVC::class.java.getDeclaredField(UVC_CAMERA_FIELD)
                .apply { isAccessible = true }
                .get(camera) as? UVCCamera
        }.onFailure { Log.w(TAG, "cannot reach the UVC engine behind CameraUVC", it) }
            .getOrNull()
    }

    private fun Int.toMicroscopeButton(): MicroscopeButton =
        if (this == BUTTON_SHUTTER) MicroscopeButton.Shutter else MicroscopeButton.Unknown(this)

    private const val TAG = "MicroscopeCamera"

    /** Private field of AUSBC's [CameraUVC] holding the engine. */
    private const val UVC_CAMERA_FIELD = "mUvcCamera"

    /** State 1 is the press; the reference device also emits a 0 on release. */
    private const val BUTTON_STATE_PRESSED = 1

    /** UVC's still-image button, confirmed on the reference device. */
    private const val BUTTON_SHUTTER = 1
}
