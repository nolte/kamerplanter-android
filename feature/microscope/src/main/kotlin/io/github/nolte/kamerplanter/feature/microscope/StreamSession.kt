package io.github.nolte.kamerplanter.feature.microscope

import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.util.Log
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera

/** What a restart has to hand the engine again after `stopPreview` releases it. */
private class StreamBinding(
    val surface: SurfaceTexture,
    val frames: IFrameCallback,
    val frameFormat: Int,
)

/**
 * One open UVC stream: the device, the engine holding it, and the mode it runs right now.
 *
 * Every method must be called on the single camera thread — the engine's calls are
 * synchronized natively and block.
 */
internal class StreamSession private constructor(
    val deviceName: String,
    private val camera: UVCCamera,
    private val controlBlock: USBMonitor.UsbControlBlock,
    private val binding: StreamBinding,
    val modes: List<Size>,
    private val previewMode: Size,
) {
    var width: Int = previewMode.width
        private set
    var height: Int = previewMode.height
        private set

    /**
     * Switches the running stream to [newWidth] x [newHeight] without releasing the
     * device, and reports whether the switch took. On failure it puts the previous mode
     * back, so the caller carries on at a mode that is actually live.
     */
    fun retune(newWidth: Int, newHeight: Int): Boolean {
        if (newWidth == width && newHeight == height) {
            return false
        }
        val previousWidth = width
        val previousHeight = height
        return runCatching {
            restart(newWidth, newHeight)
            width = newWidth
            height = newHeight
            true
        }.getOrElse {
            Log.w(TAG, "cannot retune to ${newWidth}x$newHeight; back to ${previousWidth}x$previousHeight", it)
            runCatching { restart(previousWidth, previousHeight) }
            false
        }
    }

    /**
     * Returns to the mode the preview negotiated when it opened — not necessarily 1080p.
     * Restoring a hard-coded size would strand a device that cannot do it at whatever
     * resolution the still used, leaving the preview at a few frames per second.
     */
    fun restorePreview(): Boolean = retune(previewMode.width, previewMode.height)

    fun close() {
        runCatching { camera.stopPreview() }
        runCatching { camera.destroy() }
        // UVCCamera works on a *clone* of the control block, so destroying the camera
        // leaves the original — and the UsbDeviceConnection it opened — behind.
        runCatching { controlBlock.close() }
    }

    private fun restart(modeWidth: Int, modeHeight: Int) {
        camera.stopPreview()
        camera.setPreviewSize(
            modeWidth,
            modeHeight,
            MIN_FPS,
            MAX_FPS,
            binding.frameFormat,
            UVCCamera.DEFAULT_BANDWIDTH,
        )
        // stopPreview() releases the native preview window, so the surface and the frame
        // callback have to be handed over again — without this, startPreview() logs
        // "window does not exist" and the stream silently never resumes.
        camera.setFrameCallback(binding.frames, UVCCamera.PIXEL_FORMAT_YUV420SP)
        camera.setPreviewTexture(binding.surface)
        camera.startPreview()
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
         * Claims the device and starts a preview on [surface]. Throws when the device
         * cannot be opened or offers no usable mode; the caller turns that into an error
         * state. Nothing stays open on the way out.
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
            return runCatching {
                // Inside the guard: open() clones the control block before it can throw,
                // so a failure here would otherwise strand a second USB connection.
                camera.open(controlBlock)
                camera.startOn(device, controlBlock, surface, frames, onButton)
            }.getOrElse {
                runCatching { camera.destroy() }
                runCatching { controlBlock.close() }
                throw it
            }
        }

        private fun UVCCamera.startOn(
            device: UsbDevice,
            controlBlock: USBMonitor.UsbControlBlock,
            surface: SurfaceTexture,
            frames: IFrameCallback,
            onButton: (MicroscopeButton, Boolean) -> Unit,
        ): StreamSession {
            val format = negotiate(this)
            // Ask for this format's own list: the no-argument overload reports whatever
            // `mCurrentFrameFormat` holds, which the constructor leaves at MJPEG — so a
            // YUYV-only device would look as though it had no modes at all.
            val modes = getSupportedSizeList(format).orEmpty()
            val preview = modes.bestFitting(PREVIEW_WIDTH, PREVIEW_HEIGHT)
                ?: error("the device reports no preview mode for format $format")
            setPreviewSize(preview.width, preview.height, MIN_FPS, MAX_FPS, format, UVCCamera.DEFAULT_BANDWIDTH)
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
            return StreamSession(
                deviceName = device.deviceName,
                camera = this,
                controlBlock = controlBlock,
                binding = StreamBinding(surface, frames, format),
                modes = modes,
                previewMode = preview,
            )
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
