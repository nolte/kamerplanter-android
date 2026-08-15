package io.github.nolte.kamerplanter.core.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * A still capture from the device camera.
 *
 * Bound to the composition's lifecycle, so it stops with the screen. The shutter is the
 * returned [PhoneCameraShutter]: capturing is a suspending call rather than a callback, which
 * lets the caller hold one flow — capture, then upload — instead of splitting it.
 */
interface PhoneCameraShutter {

    /**
     * Takes a photo and returns it as a JPEG the upload contract accepts, or `null` when the
     * camera failed or the photo cannot be brought under [maxBytes].
     *
     * The downscale happens here rather than at the upload because it is the phone path's own
     * problem: a modern sensor produces several megabytes where the microscope produces a few
     * hundred kilobytes, and the rotation the sensor reports has to be baked into the pixels
     * before the backend strips the metadata that carries it.
     */
    suspend fun capture(maxBytes: Int): ByteArray?
}

/**
 * A seam, not a convenience: the shutter only exists while a particular composable is bound, so
 * a caller that owns the capture flow can only be tested against something it can construct.
 */
internal class CameraXShutter(
    private val capture: ImageCapture,
    private val context: Context,
) : PhoneCameraShutter {

    override suspend fun capture(maxBytes: Int): ByteArray? {
        val taken = takePicture() ?: return null
        // Off the main thread, deliberately. Decoding, scaling, rotating and re-encoding a
        // fifty-megapixel photo is seconds of work, and `takePicture` resumes on the main
        // executor — so without this the UI freezes for the whole of it, right up to an ANR.
        return withContext(Dispatchers.Default) {
            JpegDownscale.toUploadable(taken.bytes, maxBytes, taken.rotationDegrees)
        }
    }

    private class Taken(val bytes: ByteArray, val rotationDegrees: Int)

    private suspend fun takePicture(): Taken? = suspendCancellableCoroutine { continuation ->
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val taken = image.use {
                        // A JPEG ImageProxy carries exactly one plane holding the encoded file.
                        val buffer = it.planes[0].buffer
                        Taken(ByteArray(buffer.remaining()).also(buffer::get), it.imageInfo.rotationDegrees)
                    }
                    if (continuation.isActive) continuation.resume(taken)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            },
        )
    }
}

/**
 * A live preview of the back camera with a shutter attached.
 *
 * The caller MUST hold the CAMERA permission already — see [rememberCameraPermission]. This
 * only binds the camera; what is done with a photo belongs to whoever asked for it.
 *
 * [onShutterReady] hands back the shutter once binding succeeds, and `null` when it fails or
 * the composable leaves — so a screen can disable its own button rather than offer one that
 * throws.
 */
@Composable
fun PhoneCameraPreview(
    onShutterReady: (PhoneCameraShutter?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        // Both the provider listener and onDispose run on the main thread, so this plain flag
        // is enough to skip a bind whose composable was already torn down.
        var disposed = false
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                if (disposed) return@addListener
                val provider = runCatching { providerFuture.get() }.getOrNull()
                if (provider == null) {
                    onShutterReady(null)
                    return@addListener
                }
                val preview = Preview.Builder().build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                // MINIMIZE_LATENCY, not MAXIMIZE_QUALITY: the subject is a leaf held at arm's
                // length, and the extra exposure time of the quality mode is motion blur on a
                // photo whose whole point is fine detail.
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val bound = runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                }
                onShutterReady(
                    if (bound.isSuccess) CameraXShutter(capture, context) else null,
                )
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            onShutterReady(null)
            // Only when it is already resolved: `get()` blocks, and on a cold start the user
            // can leave before the provider arrives — which would freeze the UI until the
            // camera finished initialising for a screen they have already left. An unresolved
            // provider has nothing bound to unbind anyway.
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
