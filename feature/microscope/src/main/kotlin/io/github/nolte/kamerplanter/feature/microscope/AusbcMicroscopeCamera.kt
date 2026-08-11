package io.github.nolte.kamerplanter.feature.microscope

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import android.view.View
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.usb.USBMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [MicroscopeCamera] backed by AndroidUSBCamera (AUSBC). Event flow:
 * [start] registers the USB monitor -> `onAttachDev` (also fired for devices already
 * plugged in) -> USB permission request -> `onConnectDev` hands over the control
 * block -> the stream opens once the preview surface exists too.
 *
 * Capture bypasses AUSBC's `captureImage` (it insists on WRITE_EXTERNAL_STORAGE and
 * writes to DCIM): a one-shot preview-data callback grabs the next NV21 frame and
 * compresses it to JPEG in-process, which is what the upload pipeline needs anyway.
 */
@Singleton
internal class AusbcMicroscopeCamera @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : MicroscopeCamera {

    private val mutableState =
        MutableStateFlow<MicroscopeState>(MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED))
    override val state: StateFlow<MicroscopeState> = mutableState.asStateFlow()

    private val mutableButtonPresses = MutableSharedFlow<MicroscopeButton>(extraBufferCapacity = 4)
    override val buttonPresses: SharedFlow<MicroscopeButton> = mutableButtonPresses.asSharedFlow()

    private var client: MultiCameraClient? = null
    private var camera: CameraUVC? = null
    private var previewView: AspectRatioTextureView? = null
    private var surfaceReady = false

    /**
     * Guards against a second [CameraUVC.openCamera] while the first is still starting:
     * AUSBC only flips `isCameraOpened()` at the very end of its open sequence, so the
     * USB-connect and surface-available callbacks can both pass that check. The loser
     * then fails to claim the busy device and reports a bogus "unsupported preview size".
     */
    private val opening = AtomicBoolean(false)

    /** 1.0 shows the full frame; higher values crop towards the centre. */
    @Volatile
    private var zoomFactor = MIN_ZOOM

    /** A capture retunes the stream, so two of them must never overlap. */
    private val captureLock = Mutex()

    private val connectCallback = object : IDeviceConnectCallBack {
        override fun onAttachDev(device: UsbDevice?) {
            device ?: return
            mutableState.value = MicroscopeState.AwaitingPermission
            client?.requestPermission(device)
        }

        override fun onDetachDec(device: UsbDevice?) {
            teardownCamera()
            mutableState.value = MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED)
        }

        override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            device ?: return
            ctrlBlock ?: return
            camera = CameraUVC(context, device).apply {
                setUsbControlBlock(ctrlBlock)
                setCameraStateCallBack(cameraStateCallback)
            }
            openIfReady()
        }

        override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            teardownCamera()
            mutableState.value = MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED)
        }

        override fun onCancelDev(device: UsbDevice?) {
            mutableState.value = MicroscopeState.Unavailable(UnavailableReason.PERMISSION_DENIED)
        }
    }

    private val cameraStateCallback = object : ICameraStateCallBack {
        override fun onCameraState(
            self: MultiCameraClient.ICamera,
            code: ICameraStateCallBack.State,
            msg: String?,
        ) {
            opening.set(false)
            when (code) {
                ICameraStateCallBack.State.OPENED -> {
                    // `self`, not the field: a resolution switch reopens the stream, and
                    // the field can be cleared by a concurrent detach.
                    UvcEngine.observeButtons(self as? CameraUVC) { mutableButtonPresses.tryEmit(it) }
                    mutableState.value = MicroscopeState.Streaming
                }
                ICameraStateCallBack.State.ERROR ->
                    mutableState.value = MicroscopeState.Error(msg ?: "unknown camera error")
                ICameraStateCallBack.State.CLOSED -> Unit
            }
        }
    }

    override fun createPreviewView(context: Context): View =
        AspectRatioTextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    surfaceReady = true
                    openIfReady()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    surfaceReady = false
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
            previewView = this
        }

    override fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            mutableState.value = MicroscopeState.Unavailable(UnavailableReason.NO_USB_HOST_SUPPORT)
            return
        }
        val client = client ?: MultiCameraClient(context, connectCallback).also { client = it }
        client.register()
    }

    override fun stop() {
        teardownCamera()
        client?.unRegister()
        mutableState.value = MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED)
    }

    override suspend fun captureFrame(): Result<CapturedFrame> = captureLock.withLock {
        val camera = camera
        if (camera == null || !camera.isCameraOpened()) {
            return Result.failure(IllegalStateException("microscope is not streaming"))
        }
        runCatching {
            // Stills are worth the sensor's full resolution; the preview is not.
            camera.atLargestResolution {
                val frame = withTimeout(CAPTURE_TIMEOUT_MS) { awaitNextNv21Frame(camera) }
                // Capture what the preview shows, zoom included.
                val region = zoomedRegion(frame.width, frame.height, zoomFactor)
                CapturedFrame(
                    jpeg = nv21ToJpeg(frame.data, frame.width, frame.height, region, JPEG_QUALITY),
                    width = region.width,
                    height = region.height,
                )
            }
        }.onSuccess {
            Log.i(TAG, "captured ${it.width}x${it.height}, ${it.jpeg.size} bytes of JPEG")
            keepForInspection(it)
        }.onFailure { Log.w(TAG, "capture failed", it) }
    }

    /**
     * Debuggable builds also drop the capture into the app's external files dir, so a
     * hardware session leaves a pullable artifact instead of a verbal report. Release
     * builds keep the frame in memory only — the upload pipeline is its sole consumer.
     */
    private fun keepForInspection(frame: CapturedFrame) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return
        }
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return
            val file = File(dir, "capture-${frame.width}x${frame.height}-${SystemClock.uptimeMillis()}.jpg")
            file.writeBytes(frame.jpeg)
            Log.i(TAG, "capture also written to ${file.absolutePath}")
        }.onFailure { Log.w(TAG, "could not persist the capture for inspection", it) }
    }

    private fun openIfReady() {
        val camera = camera
        val view = previewView
        val ready = camera != null && view != null && surfaceReady && !camera.isCameraOpened()
        if (ready && opening.compareAndSet(false, true)) {
            val request = CameraRequest.Builder()
                .setPreviewWidth(PREVIEW_WIDTH)
                .setPreviewHeight(PREVIEW_HEIGHT)
                // NORMAL renders the decoded stream straight into the TextureView and keeps
                // the NV21 frame callback active, which captureFrame() depends on.
                .setRenderMode(CameraRequest.RenderMode.NORMAL)
                .setAspectRatioShow(true)
                .create()
            camera.openCamera(view, request)
        }
    }

    private fun teardownCamera() {
        camera?.closeCamera()
        camera = null
        opening.set(false)
    }

    override fun zoomBy(deltaPercent: Int) {
        zoomFactor = (zoomFactor + deltaPercent / PERCENT).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val view = previewView ?: return
        view.post {
            view.setTransform(
                Matrix().apply {
                    setScale(zoomFactor, zoomFactor, view.width / 2f, view.height / 2f)
                },
            )
        }
    }

    private class Nv21Frame(val data: ByteArray, val width: Int, val height: Int)

    private suspend fun awaitNextNv21Frame(camera: CameraUVC): Nv21Frame =
        suspendCancellableCoroutine { continuation ->
            val delivered = AtomicBoolean(false)
            val callback = object : IPreviewDataCallBack {
                override fun onPreviewData(
                    data: ByteArray?,
                    width: Int,
                    height: Int,
                    format: IPreviewDataCallBack.DataFormat,
                ) {
                    if (!delivered.compareAndSet(false, true)) {
                        return
                    }
                    camera.removePreviewDataCallBack(this)
                    if (data == null || format != IPreviewDataCallBack.DataFormat.NV21) {
                        continuation.resumeWithException(
                            IllegalStateException("unexpected preview data (format=$format)"),
                        )
                    } else {
                        continuation.resume(Nv21Frame(data, width, height))
                    }
                }
            }
            continuation.invokeOnCancellation { camera.removePreviewDataCallBack(callback) }
            camera.addPreviewDataCallBack(callback)
        }

    private companion object {
        /** 1080p is the reference device's sweet spot — 4K streams at ~4.7 fps (issue #1). */
        const val PREVIEW_WIDTH = 1920
        const val PREVIEW_HEIGHT = 1080

        const val CAPTURE_TIMEOUT_MS = 3_000L
        const val JPEG_QUALITY = 90
        const val TAG = "MicroscopeCamera"

        const val PERCENT = 100f
        const val MIN_ZOOM = 1f

        /** Past 4x, a 1080p frame has no detail left to reveal. */
        const val MAX_ZOOM = 4f
    }
}
