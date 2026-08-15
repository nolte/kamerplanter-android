package io.github.nolte.kamerplanter.feature.microscope

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.TextureView
import android.view.View
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.IFrameCallback
import com.jiangdg.uvc.UVCCamera
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
import java.lang.ref.WeakReference
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
            onLost = { lost ->
                // A published session decides on its own: closeStream tears down whatever
                // is live regardless of device, so trusting a queued open's name while a
                // stream exists would let one device's detach kill another's stream.
                // Only with nothing published does the queued name matter — and it must,
                // because session stays null until publication, and a detach falling in
                // that window would otherwise leave a stream that surfaces after its
                // device is gone, with no further broadcast able to close it.
                val open = session.get()
                val ours = if (open != null) {
                    open.deviceName == lost.deviceName
                } else {
                    openingDevice.get() == lost.deviceName
                }
                if (ours) {
                    closeStream()
                }
            },
            onState = { mutableState.value = it },
        )
    }

    /** Used for nothing but [USBMonitor.openDevice]; its listener never fires. */
    private val monitor by lazy { USBMonitor(context, SilentConnectListener) }

    private val cameraExecutor = Executors.newSingleThreadExecutor { Thread(it, "uvc-camera") }
    private val cameraDispatcher = cameraExecutor.asCoroutineDispatcher()

    private val session = AtomicReference<StreamSession?>(null)

    /** Makes publishing a session and tearing one down mutually exclusive. */
    private val sessionLock = Any()

    /** The device an open is queued or running for; read by the `onLost` callback. */
    private val openingDevice = AtomicReference<String?>(null)

    /** Which surface the stream belongs to; see [StreamSurfaces]. Guarded by [sessionLock]. */
    private val surfaces = StreamSurfaces()

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

    /**
     * Weak on purpose. This class is a singleton, so holding the view strongly would pin
     * the Activity that created it; dropping the reference when the surface goes away
     * would be worse still, because the platform destroys the surface of a *living* view
     * whenever the window stops — and the `AndroidView` factory never runs again, so the
     * reference could never be restored and the preview would stay dead after the first
     * time the app is backgrounded.
     *
     * Volatile because it is written on the main thread in createPreviewView and read on
     * the camera thread from openStream's success path.
     */
    @Volatile
    private var previewViewRef: WeakReference<TextureView>? = null

    private val previewView: TextureView?
        get() = previewViewRef?.get()

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
                    // Logged on both edges, device included: the surface round trip is
                    // the whole reason this listener exists, and a reopen that finds no
                    // device is exactly the silent failure worth naming.
                    val device = watcher.currentDevice()
                    Log.i(TAG, "preview surface available at ${width}x$height, device=${device?.deviceName}")
                    // A second screen's view arriving while a stream still renders into the
                    // previous one: the handover happens here, explicitly, rather than being
                    // left to the order in which the outgoing view is destroyed. openStream
                    // refuses while a session is published, so without this the arriving view
                    // never gets a stream and shows a black preview over a Streaming state.
                    val heldElsewhere = synchronized(sessionLock) { surfaces.heldElsewhereThan(surface) }
                    if (heldElsewhere != null) {
                        Log.i(TAG, "handing the stream over to a newer preview surface")
                        closeStream()
                    }
                    device?.let(watcher::claim)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) =
                    applyPreviewTransform()

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    Log.i(TAG, "preview surface destroyed; open stream: ${session.get()?.deviceName}")
                    // Whether this surface owns the stream is [StreamSurfaces]' decision, and
                    // it covers the case a published session cannot: an open still in flight.
                    // Opening a device takes hundreds of milliseconds, and a surface destroyed
                    // inside that window has no session to compare against — answering "not
                    // mine" there hands the platform a surface the open is about to render
                    // into, which is the orphaned stream the generation counter exists to stop.
                    if (synchronized(sessionLock) { surfaces.mayReclaim(surface) }) return true
                    // The view itself survives this — the window merely stopped — so the
                    // reference stays and onSurfaceTextureAvailable can reopen on return.
                    // false: the platform must not reclaim the surface while the native
                    // preview thread may still be writing into it. closeStream releases
                    // it once the engine has actually let go.
                    closeStream(release = surface)
                    return false
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
            previewViewRef = WeakReference(this)
        }

    /** See [Holders]: two screens can hold this singleton at once, and they overlap. */
    private val holders = Holders()

    override fun start() {
        if (!holders.acquire()) return
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            watcher.start()
        } else {
            mutableState.value = MicroscopeState.Unavailable(UnavailableReason.NO_USB_HOST_SUPPORT)
        }
    }

    override fun stop() {
        if (!holders.release()) return
        closeStream()
        watcher.stop()
        // The preview view is deliberately kept. It is held weakly, so it cannot pin the
        // Activity, and dropping it here would leave retry() — stop() plus start() —
        // with no surface to open onto.
        //
        // No state is published here. closeStream already reported what is true, and
        // overwriting it with "no device attached" put that dead end one Retry press
        // away: retry() is stop() plus start(), and start() cannot correct the claim
        // while the surface is still gone.
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
        // Taken together under the lock the release checks: reserving the slot and moving
        // the generation are one step, or an open finishing in between could hand back a
        // reservation that already belongs to this one.
        val opening = synchronized(sessionLock) {
            openingDevice.set(device.deviceName)
            surfaces.opening(surface)
            generation.incrementAndGet()
        }
        cameraExecutor.execute {
            // Released on every exit, but only by the open that still owns the slot. The
            // device name cannot tell two opens of the same device apart, so an overtaken
            // one would hand back a *newer* open's reservation — and a detach arriving in
            // that gap would find nothing to close and leave the orphan this guards
            // against. The generation can tell them apart, and it cannot move while the
            // lock is held.
            fun releaseSlot() = synchronized(sessionLock) {
                if (opening == generation.get()) {
                    openingDevice.set(null)
                }
            }
            if (session.get() != null || opening != generation.get()) {
                releaseSlot()
                return@execute
            }
            fun abandon() = synchronized(sessionLock) {
                if (opening == generation.get()) surfaces.abandoned()
            }
            runCatching {
                StreamSession.open(monitor, device, surface, frameCallback) { button, pressed ->
                    if (pressed) {
                        mutableButtonPresses.tryEmit(button)
                    }
                }
            }.onSuccess {
                // Under the lock so a teardown cannot land between the generation check
                // and the publication: that interleaving left a live session behind an
                // Unavailable state, with nothing able to close it or reopen past it.
                val published = synchronized(sessionLock) {
                    (opening == generation.get()).also { current ->
                        if (current) {
                            session.set(it)
                            surfaces.published()
                            mutableState.value = MicroscopeState.Streaming
                        }
                    }
                }
                releaseSlot()
                if (published) {
                    Log.i(TAG, "stream opened at ${it.width}x${it.height} on ${it.deviceName}")
                    applyPreviewTransform()
                } else {
                    // A close overtook this open; nothing else would ever release it.
                    Log.i(TAG, "discarding a stream that was superseded while opening")
                    it.close()
                }
            }.onFailure {
                releaseSlot()
                abandon()
                Log.w(TAG, "opening the microscope stream failed", it)
                // Same generation check as the success path, and under the same lock: a
                // teardown landing between the check and the write would still put an
                // error over a deliberate close. An open that a teardown superseded fails
                // by design — its surface was taken away — so it stays a log line.
                synchronized(sessionLock) {
                    if (opening == generation.get()) {
                        mutableState.value = MicroscopeState.Error(it.message ?: "cannot open the microscope")
                    }
                }
            }
        }
    }

    /** Closes the live stream, and releases [release] afterwards when one is given. */
    private fun closeStream(release: SurfaceTexture? = null) {
        // Reports what is actually true: on the backgrounding path the device is still
        // plugged in, and "no device attached" would put a wrong message on screen that
        // only unplugging clears — the very shape this class keeps having to remove.
        val next = if (watcher.currentDevice() == null) {
            MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED)
        } else {
            MicroscopeState.Connecting
        }
        var heldUntilTornDown: Any? = null
        val open = synchronized(sessionLock) {
            generation.incrementAndGet()
            openingDevice.set(null)
            // Leave Streaming behind: otherwise the capture and zoom controls stay on
            // screen over a stream that is gone, and capture only yields "not streaming".
            // Error goes with it — it belongs to the session being torn down, and nothing
            // else clears it, so it would greet the next visit to this screen. A pending
            // permission state is left alone: that one is still being answered.
            when (mutableState.value) {
                MicroscopeState.Streaming, is MicroscopeState.Error -> mutableState.value = next
                else -> Unit
            }
            heldUntilTornDown = surfaces.closing()
            session.getAndSet(null)
        }
        // Wake a capture suspended in awaitFrame() so it fails fast instead of hanging the
        // full CAPTURE_TIMEOUT_MS: the frame it is waiting for will never arrive now. The
        // atomic getAndSet makes this exclusive with the frame callback's own resume.
        pendingFrame.getAndSet(null)?.resumeWithException(
            IllegalStateException("microscope stream closed"),
        )
        // The surface stays ours until the engine has let go of it, even when this close had
        // nothing published to tear down: an open in flight is still about to render into it.
        if (open == null && release == null && heldUntilTornDown == null) {
            return
        }
        cameraExecutor.execute {
            runCatching { open?.close() }
            runCatching { release?.release() }
            heldUntilTornDown?.let { synchronized(sessionLock) { surfaces.released(it) } }
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

        /**
         * Past 4x the *preview* has no detail left to reveal — the cap follows the mode the
         * preview runs in (1080p on the reference device), not what a capture resolves. A
         * still may be taken at a larger mode, so for captures the cap is deliberately
         * conservative rather than derived from their resolution.
         */
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
