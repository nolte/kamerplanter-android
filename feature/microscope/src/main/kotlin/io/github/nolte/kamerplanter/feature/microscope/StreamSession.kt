package io.github.nolte.kamerplanter.feature.microscope

import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.util.Log
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera

/**
 * One open UVC stream: the device, the engine holding it, and the mode it currently runs.
 *
 * Every method must be called on the single camera thread — the engine's calls are
 * synchronized natively and block.
 */
internal class StreamSession private constructor(
    val deviceName: String,
    private val camera: UVCCamera,
    private val surface: SurfaceTexture,
    private val frames: IFrameCallback,
    val modes: List<Size>,
    private val frameFormat: Int,
) {
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    /**
     * Switches the running stream to [newWidth] x [newHeight] without releasing the
     * device, and reports whether the switch took. Returns false unchanged, so a caller
     * can carry on at the mode that is actually live.
     */
    fun retune(newWidth: Int, newHeight: Int): Boolean {
        if (newWidth == width && newHeight == height) {
            return false
        }
        return runCatching {
            camera.stopPreview()
            camera.setPreviewSize(newWidth, newHeight, MIN_FPS, MAX_FPS, frameFormat, UVCCamera.DEFAULT_BANDWIDTH)
            // stopPreview() releases the native preview window, so the surface and the
            // frame callback have to be handed over again — without this, startPreview()
            // logs "window does not exist" and the stream silently never resumes.
            camera.setFrameCallback(frames, UVCCamera.PIXEL_FORMAT_YUV420SP)
            camera.setPreviewTexture(surface)
            camera.startPreview()
            width = newWidth
            height = newHeight
            true
        }.getOrElse {
            Log.w(TAG, "cannot retune to ${newWidth}x$newHeight; staying at ${width}x$height", it)
            runCatching {
                camera.setPreviewSize(width, height, MIN_FPS, MAX_FPS, frameFormat, UVCCamera.DEFAULT_BANDWIDTH)
                camera.setFrameCallback(frames, UVCCamera.PIXEL_FORMAT_YUV420SP)
                camera.setPreviewTexture(surface)
                camera.startPreview()
            }
            false
        }
    }

    fun close() {
        runCatching { camera.stopPreview() }
        camera.destroy()
    }

    companion object {
        private const val TAG = "MicroscopeCamera"

        /**
         * A floor of 1 rather than the 10 AUSBC hard-codes: the reference device delivers
         * ~4.7 fps at 4K, and demanding ten would reject an honest descriptor.
         */
        private const val MIN_FPS = 1
        private const val MAX_FPS = 60

        private const val PREVIEW_WIDTH = 1920
        private const val PREVIEW_HEIGHT = 1080

        /**
         * Claims the device and starts a preview on [surface].
         *
         * MJPEG first because that is what these bodies stream; uncompressed YUYV is the
         * fallback for the rare device that offers nothing else. Throws when neither
         * negotiates, which the caller turns into an error state.
         */
        fun open(
            monitor: USBMonitor,
            device: UsbDevice,
            surface: SurfaceTexture,
            frames: IFrameCallback,
            onButton: (MicroscopeButton, Boolean) -> Unit,
        ): StreamSession {
            val controlBlock = monitor.openDevice(device)
            val camera = UVCCamera()
            camera.open(controlBlock)
            return runCatching {
                val session = camera.startOn(device, surface, frames, onButton)
                session
            }.getOrElse {
                camera.destroy()
                throw it
            }
        }

        private fun UVCCamera.startOn(
            device: UsbDevice,
            surface: SurfaceTexture,
            frames: IFrameCallback,
            onButton: (MicroscopeButton, Boolean) -> Unit,
        ): StreamSession {
            val format = negotiate(this)
            val modes = supportedSizeList.orEmpty()
            val chosen = modes.bestFitting(PREVIEW_WIDTH, PREVIEW_HEIGHT)
                ?: error("the device reports no preview modes")
            setPreviewSize(chosen.width, chosen.height, MIN_FPS, MAX_FPS, format, UVCCamera.DEFAULT_BANDWIDTH)
            setFrameCallback(frames, UVCCamera.PIXEL_FORMAT_YUV420SP)
            setPreviewTexture(surface)
            // Public on UVCCamera — the wrapper is what hid this behind a private field.
            setButtonCallback { button, state ->
                // Logged raw: an unfamiliar body is mapped from evidence, never guessed at.
                Log.i(TAG, "microscope button event: button=$button state=$state")
                onButton(button.toMicroscopeButton(), state == PRESSED)
            }
            setStatusCallback { statusClass, event, selector, attribute, _ ->
                Log.i(TAG, "status event: class=$statusClass event=$event selector=$selector attr=$attribute")
            }
            startPreview()
            updateCameraParams()
            return StreamSession(device.deviceName, this, surface, frames, modes, format).apply {
                width = chosen.width
                height = chosen.height
            }
        }

        /** MJPEG when the device lists any mode for it, otherwise uncompressed. */
        private fun negotiate(camera: UVCCamera): Int =
            if (camera.getSupportedSizeList(UVCCamera.FRAME_FORMAT_MJPEG).orEmpty().isNotEmpty()) {
                UVCCamera.FRAME_FORMAT_MJPEG
            } else {
                UVCCamera.FRAME_FORMAT_YUYV
            }

        /** UVC's still-image button is index 1; anything else is logged, never guessed at. */
        private fun Int.toMicroscopeButton(): MicroscopeButton =
            if (this == SHUTTER) MicroscopeButton.Shutter else MicroscopeButton.Unknown(this)

        private const val SHUTTER = 1
        private const val PRESSED = 1
    }
}
