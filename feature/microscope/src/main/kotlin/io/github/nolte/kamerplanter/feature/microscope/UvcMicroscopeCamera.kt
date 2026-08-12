package io.github.nolte.kamerplanter.feature.microscope

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.TextureView
import android.view.View
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [MicroscopeCamera] driven directly against `libuvc` — the platform [UsbManager] finds
 * the device and carries the permission, [USBMonitor] only hands over the control block,
 * and [UVCCamera] does the streaming.
 *
 * Sitting at this layer rather than on AUSBC's `CameraUVC` wrapper is what removes four
 * problems at once: `USBMonitor.register()` — the method that crashes on `targetSdk` 34
 * by wrapping an implicit intent in a mutable `PendingIntent` — is never called; the
 * button callback needs no reflection because [UVCCamera] exposes it; the open sequence
 * cannot race because this class owns it; and a mode change is a `stopPreview` /
 * `setPreviewSize` / `startPreview` on the open device instead of a full restart with a
 * hard-coded one-second pause.
 *
 * Every native call runs on [cameraExecutor]; they are synchronized inside the engine and
 * must not block the main thread.
 */
@Singleton
internal class UvcMicroscopeCamera @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : MicroscopeCamera {

    private val mutableState =
        MutableStateFlow<MicroscopeState>(MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED))
    override val state: StateFlow<MicroscopeState> = mutableState.asStateFlow()

    private val mutableButtonPresses = MutableSharedFlow<MicroscopeButton>(extraBufferCapacity = 4)
    override val buttonPresses: SharedFlow<MicroscopeButton> = mutableButtonPresses.asSharedFlow()

    private val watcher by lazy {
        UsbAttachmentWatcher(
            context = context,
            isStreaming = { session.get() != null },
            onReady = ::openStream,
            onLost = { if (session.get()?.deviceName == it.deviceName) closeStream() },
            onState = { mutableState.value = it },
        )
    }

    /** Used for nothing but [USBMonitor.openDevice]; its listener never fires. */
    private val monitor by lazy { USBMonitor(context, SilentConnectListener) }

    private val cameraExecutor = Executors.newSingleThreadExecutor { Thread(it, "uvc-camera") }
    private val cameraDispatcher = cameraExecutor.asCoroutineDispatcher()

    private val session = AtomicReference<StreamSession?>(null)

    /**
     * Invalidates an open that is already queued on the camera thread. Without it, a
     * close arriving between `execute {}` and the session being published sees nothing
     * to close, and the stream that opens afterwards is orphaned — still holding the
     * device and still rendering into a surface the platform has taken back.
     */
    private val generation = AtomicInteger(0)
    private val captureLock = Mutex()
    private val pendingFrame = AtomicReference<CancellableContinuation<Nv21Frame>?>(null)

    @Volatile
    private var zoomFactor = MIN_ZOOM

    // @Volatile: written on the main thread (surface listener) but read on the camera
    // thread in applyPreviewTransform() from openStream's success path.
    @Volatile
    private var previewView: TextureView? = null

    private val frameCallback = IFrameCallback { buffer ->
        val waiting = pendingFrame.get() ?: return@IFrameCallback
        val open = session.get() ?: return@IFrameCallback
        val expected = open.width * open.height * NV21_BYTES_NUMERATOR / NV21_BYTES_DENOMINATOR
        if (buffer == null || buffer.capacity() != expected) {
            // A frame still in flight from the previous mode; the next one will fit.
            return@IFrameCallback
        }
        if (pendingFrame.compareAndSet(waiting, null)) {
            buffer.position(0)
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            waiting.resume(Nv21Frame(data, open.width, open.height))
        }
    }

    override fun createPreviewView(context: Context): View =
        TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    watcher.currentDevice()?.let(watcher::claim)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) =
                    applyPreviewTransform()

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    // The view is going away, so release the reference that would
                    // otherwise pin its Activity in this singleton.
                    previewView = null
                    // false: the platform must not reclaim the surface while the native
                    // preview thread may still be writing into it. closeStream releases
                    // it once the engine has actually let go.
                    closeStream(release = surface)
                    return false
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
            previewView = this
        }

    override fun start() {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            watcher.start()
        } else {
            mutableState.value = MicroscopeState.Unavailable(UnavailableReason.NO_USB_HOST_SUPPORT)
        }
    }

    override fun stop() {
        closeStream()
        watcher.stop()
        // The preview view is deliberately kept: it is released when its surface is
        // destroyed, which is when the screen is really gone. Dropping it here would
        // leave retry() — stop() plus start() — with no surface to open onto, and no
        // second onSurfaceTextureAvailable is coming for a view that still exists.
        mutableState.value = MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED)
    }

    override suspend fun captureFrame(): Result<CapturedFrame> {
        val open = session.get()
        // The busy guard belongs here, not in a caller: the camera is a singleton while
        // its observers are screen-scoped, so two of them must not queue up two captures
        // for one shutter press. tryLock drops the duplicate rather than awaiting a turn.
        val result = when {
            open == null -> Result.failure(IllegalStateException("microscope is not streaming"))
            !captureLock.tryLock() -> Result.failure(IllegalStateException("a capture is already running"))
            else -> try {
                runCatching { withContext(cameraDispatcher) { grabStill(open) } }
            } finally {
                captureLock.unlock()
            }
        }
        result.onSuccess {
            Log.i(TAG, "captured ${it.width}x${it.height}, ${it.jpeg.size} bytes of JPEG")
            it.writeForInspection(context)
        }.onFailure { Log.w(TAG, "capture failed", it) }
        return result
    }

    /**
     * Grabs one frame at the sensor's full resolution and puts the preview mode back.
     * Retuning the open device costs one stream restart, not the two full device
     * releases plus two hard-coded seconds the AUSBC wrapper needed.
     */
    private suspend fun grabStill(open: StreamSession): CapturedFrame {
        val still = open.modes.largest()
        val retuned = still != null && open.retune(still.width, still.height)
        try {
            val frame = withTimeout(CAPTURE_TIMEOUT_MS) { awaitFrame() }
            val region = zoomedRegion(frame.width, frame.height, zoomFactor)
            return CapturedFrame(
                jpeg = nv21ToJpeg(frame.data, frame.width, frame.height, region, JPEG_QUALITY),
                width = region.width,
                height = region.height,
            )
        } finally {
            if (retuned) {
                open.restorePreview()
            }
        }
    }

    override fun zoomBy(deltaPercent: Int) {
        zoomFactor = (zoomFactor + deltaPercent / PERCENT).coerceIn(MIN_ZOOM, MAX_ZOOM)
        applyPreviewTransform()
    }

    private fun openStream(device: UsbDevice) {
        val surface = previewView?.surfaceTexture ?: return
        val opening = generation.incrementAndGet()
        cameraExecutor.execute {
            if (session.get() != null || opening != generation.get()) {
                return@execute
            }
            runCatching {
                StreamSession.open(monitor, device, surface, frameCallback) { button, pressed ->
                    if (pressed) {
                        mutableButtonPresses.tryEmit(button)
                    }
                }
            }.onSuccess {
                if (opening == generation.get()) {
                    Log.i(TAG, "stream opened at ${it.width}x${it.height} on ${it.deviceName}")
                    session.set(it)
                    mutableState.value = MicroscopeState.Streaming
                    applyPreviewTransform()
                } else {
                    // A close overtook this open; nothing else would ever release it.
                    Log.i(TAG, "discarding a stream that was superseded while opening")
                    it.close()
                }
            }.onFailure {
                Log.w(TAG, "opening the microscope stream failed", it)
                mutableState.value = MicroscopeState.Error(it.message ?: "cannot open the microscope")
            }
        }
    }

    /** Closes the live stream, and releases [release] afterwards when one is given. */
    private fun closeStream(release: SurfaceTexture? = null) {
        generation.incrementAndGet()
        val open = session.getAndSet(null)
        // Wake a capture suspended in awaitFrame() so it fails fast instead of hanging the
        // full CAPTURE_TIMEOUT_MS: the frame it is waiting for will never arrive now. The
        // atomic getAndSet makes this exclusive with the frame callback's own resume.
        pendingFrame.getAndSet(null)?.resumeWithException(
            IllegalStateException("microscope stream closed"),
        )
        if (open == null && release == null) {
            return
        }
        cameraExecutor.execute {
            runCatching { open?.close() }
            runCatching { release?.release() }
        }
    }

    private suspend fun awaitFrame(): Nv21Frame = suspendCancellableCoroutine { continuation ->
        pendingFrame.set(continuation)
        continuation.invokeOnCancellation { pendingFrame.compareAndSet(continuation, null) }
    }

    private fun applyPreviewTransform() {
        val view = previewView ?: return
        val open = session.get() ?: return
        view.post {
            view.setTransform(
                previewTransform(view.width, view.height, open.width, open.height, zoomFactor),
            )
        }
    }

    private companion object {
        const val TAG = "MicroscopeCamera"

        const val CAPTURE_TIMEOUT_MS = 5_000L
        const val JPEG_QUALITY = 90
        const val PERCENT = 100f
        const val MIN_ZOOM = 1f

        /** Past 4x, a 1080p frame has no detail left to reveal. */
        const val MAX_ZOOM = 4f

        const val NV21_BYTES_NUMERATOR = 3
        const val NV21_BYTES_DENOMINATOR = 2
    }
}

/** [USBMonitor] insists on a listener; nothing here uses one, because `register()` is never called. */
private object SilentConnectListener : USBMonitor.OnDeviceConnectListener {
    override fun onAttach(device: UsbDevice?) = Unit
    override fun onDetach(device: UsbDevice?) = Unit
    override fun onConnect(device: UsbDevice?, block: USBMonitor.UsbControlBlock?, createNew: Boolean) = Unit
    override fun onDisconnect(device: UsbDevice?, block: USBMonitor.UsbControlBlock?) = Unit
    override fun onCancel(device: UsbDevice?) = Unit
}

internal class Nv21Frame(val data: ByteArray, val width: Int, val height: Int)
