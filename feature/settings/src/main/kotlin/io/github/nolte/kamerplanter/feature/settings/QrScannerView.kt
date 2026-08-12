package io.github.nolte.kamerplanter.feature.settings

import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Live device-camera QR scanner: a CameraX preview whose frames are analysed by ML Kit
 * barcode scanning. Every decoded QR string is handed to [onQrDetected]; parsing and the
 * pairing decision belong to [SettingsViewModel], not here.
 *
 * The caller MUST have already obtained the CAMERA runtime permission — this composable
 * only binds the camera. CameraX + ML Kit are referenced only within `:feature:settings`.
 */
@Composable
internal fun QrScannerView(
    onQrDetected: (String) -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    // Keep the latest callbacks without rebinding the camera on every recomposition.
    val currentOnQrDetected by rememberUpdatedState(onQrDetected)
    val currentOnError by rememberUpdatedState(onError)

    DisposableEffect(lifecycleOwner) {
        // Both the provider listener and onDispose run on the main thread, so this plain
        // flag is enough to skip a bind whose composable was already torn down (teardown
        // race): the executor/scanner would be dead by the time a late callback fires.
        var disposed = false
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                if (!disposed) {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(
                                analysisExecutor,
                                QrCodeAnalyzer(scanner) { raw -> currentOnQrDetected(raw) },
                            )
                        }
                    provider.unbindAll()
                    // Surface a bind failure (camera in use, hardware error, no back camera)
                    // instead of leaving a permanently black preview with no recovery.
                    runCatching {
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }.onFailure { currentOnError() }
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            disposed = true
            // Never block the main thread waiting on the provider: only unbind if it has
            // already resolved; an unresolved future means nothing was bound yet.
            if (providerFuture.isDone) {
                runCatching { providerFuture.get().unbindAll() }
            }
            analysisExecutor.shutdown()
            scanner.close()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Feeds each camera frame to ML Kit and forwards the first decoded QR value. Runs on a
 * single-threaded executor, so [onQr] is never delivered concurrently — the ViewModel's
 * "only while scanning" guard is enough to fire pairing exactly once.
 */
private class QrCodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val onQr: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                barcodes.firstNotNullOfOrNull { it.rawValue }?.let(onQr)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
