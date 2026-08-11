package io.github.nolte.kamerplanter.feature.microscope

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Live preview + single-frame capture for the USB microscope (issue #1). The screen
 * owns the Android CAMERA runtime permission (AUSBC requires it, see the module
 * manifest); the USB-level permission dialog is driven by [MicroscopeCamera] itself.
 */
@Composable
fun MicroscopeScreen(
    modifier: Modifier = Modifier,
    viewModel: MicroscopeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasCameraPermission by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    if (hasCameraPermission) {
        DisposableEffect(Unit) {
            viewModel.start()
            onDispose { viewModel.stop() }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            if (uiState.camera is MicroscopeState.Streaming) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // On screen because the body's own rocker never reaches USB — see
                    // MicroscopeCamera.zoomBy.
                    ZoomControls(onZoomIn = viewModel::zoomIn, onZoomOut = viewModel::zoomOut)
                    CaptureButton(isCapturing = uiState.isCapturing, onCapture = viewModel::capture)
                }
            }
        },
    ) { innerPadding ->
        MicroscopeContent(
            uiState = uiState,
            hasCameraPermission = hasCameraPermission,
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            createPreviewView = viewModel::createPreviewView,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

@Composable
private fun MicroscopeContent(
    uiState: MicroscopeUiState,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    createPreviewView: (android.content.Context) -> android.view.View,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (hasCameraPermission) {
            AndroidView(
                factory = createPreviewView,
                modifier = Modifier.fillMaxSize(),
            )
        }
        when {
            !hasCameraPermission -> CameraPermissionRationale(
                onRequest = onRequestPermission,
                modifier = Modifier.align(Alignment.Center),
            )
            uiState.camera !is MicroscopeState.Streaming -> StatusMessage(
                state = uiState.camera,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        uiState.lastCapture?.let { frame ->
            CaptureThumbnail(
                frame = frame,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            )
        }
        uiState.captureError?.let { error ->
            Text(
                text = stringResource(R.string.microscope_capture_failed, error),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun ZoomControls(onZoomIn: () -> Unit, onZoomOut: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(bottom = 12.dp),
    ) {
        SmallFloatingActionButton(onClick = onZoomIn) {
            Text(text = stringResource(R.string.microscope_zoom_in))
        }
        SmallFloatingActionButton(
            onClick = onZoomOut,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(text = stringResource(R.string.microscope_zoom_out))
        }
    }
}

@Composable
private fun CaptureButton(isCapturing: Boolean, onCapture: () -> Unit) {
    ExtendedFloatingActionButton(onClick = onCapture) {
        val label = if (isCapturing) {
            R.string.microscope_capturing
        } else {
            R.string.microscope_capture
        }
        Text(text = stringResource(label))
    }
}

@Composable
private fun CameraPermissionRationale(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.microscope_camera_permission),
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.microscope_grant_permission))
        }
    }
}

@Composable
private fun StatusMessage(state: MicroscopeState, modifier: Modifier = Modifier) {
    val text = when (state) {
        is MicroscopeState.Unavailable -> when (state.reason) {
            UnavailableReason.NO_DEVICE_ATTACHED -> stringResource(R.string.microscope_no_device)
            UnavailableReason.NO_USB_HOST_SUPPORT -> stringResource(R.string.microscope_no_usb_host)
            UnavailableReason.PERMISSION_DENIED -> stringResource(R.string.microscope_usb_permission_denied)
        }
        MicroscopeState.AwaitingPermission -> stringResource(R.string.microscope_awaiting_permission)
        is MicroscopeState.Error -> stringResource(R.string.microscope_error, state.message)
        MicroscopeState.Streaming -> ""
    }
    Text(
        text = text,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(24.dp),
    )
}

@Composable
private fun CaptureThumbnail(frame: CapturedFrame, modifier: Modifier = Modifier) {
    // Subsampled while decoding: a full 4K frame would be ~33 MB of ARGB_8888 held for a
    // 140 dp thumbnail, which is an OOM candidate on a low-RAM device and jank everywhere.
    val bitmap = remember(frame) {
        val options = BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, frame.width / THUMBNAIL_TARGET_PX)
        }
        BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, options)?.asImageBitmap()
    } ?: return
    Card(modifier = modifier.width(140.dp)) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.microscope_last_capture),
            contentScale = ContentScale.FillWidth,
            // Width-bound: fillMaxSize would stretch the card over the whole preview.
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(
                R.string.microscope_capture_info,
                frame.width,
                frame.height,
                frame.jpeg.size / BYTES_PER_KILOBYTE,
            ),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(8.dp),
        )
    }
}

private const val BYTES_PER_KILOBYTE = 1024

/** Roughly the thumbnail's widest rendering, in pixels — the decode need not beat it. */
private const val THUMBNAIL_TARGET_PX = 420
