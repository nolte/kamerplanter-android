package io.github.nolte.kamerplanter.feature.microscope

import android.graphics.BitmapFactory
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nolte.kamerplanter.core.camera.rememberCameraPermission

/**
 * Live preview + single-frame capture for the USB microscope (issue #1).
 *
 * The CAMERA permission is a platform gate, not a library one: AOSP refuses to show the
 * USB permission dialog for a video-class device unless the requesting app holds it, and
 * denies the request outright instead. So this screen asks for CAMERA first and only then
 * lets the camera look for the device.
 */
@Composable
fun MicroscopeScreen(
    /**
     * Leaves for the pest-detection flow (#10), which owns its own capture.
     *
     * A callback rather than a dependency: this module knows the microscope, not what any
     * other feature does with it, and the navigation graph belongs to the app module.
     */
    onIdentifyPest: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MicroscopeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permission = rememberCameraPermission()
    if (permission.isGranted) {
        DisposableEffect(Unit) {
            viewModel.start()
            onDispose { viewModel.stop() }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Not gated on the stream. It was, on the grounds that the detection flow opens
                // the same camera — true when that flow was microscope-only, and false since it
                // grew a phone-camera source. Gating it meant the entire feature, including the
                // source that needs no microscope at all, could only be reached by first
                // attaching one: on a phone with nothing plugged in the app looked unchanged.
                IdentifyPestButton(onIdentify = onIdentifyPest)
                if (uiState.camera is MicroscopeState.Streaming) {
                    // These two do need a live stream: there is nothing to zoom or capture
                    // without one.
                    //
                    // Zoom is on screen because the body's own rocker never reaches USB — see
                    // MicroscopeCamera.zoomBy.
                    ZoomControls(onZoomIn = viewModel::zoomIn, onZoomOut = viewModel::zoomOut)
                    CaptureButton(isCapturing = uiState.isCapturing, onCapture = viewModel::capture)
                }
            }
        },
    ) { innerPadding ->
        if (permission.isGranted) {
            MicroscopeContent(
                uiState = uiState,
                onRetry = viewModel::retry,
                createPreviewView = viewModel::createPreviewView,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            // Once the grant is refused for good the system stops prompting, so the button
            // has to lead somewhere that still works.
            ActionableMessage(
                text = stringResource(R.string.microscope_camera_permission),
                actionLabel = stringResource(
                    if (permission.canAsk) {
                        R.string.microscope_grant_permission
                    } else {
                        R.string.microscope_open_settings
                    },
                ),
                onAction = if (permission.canAsk) permission.request else permission.openSettings,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@Composable
private fun MicroscopeContent(
    uiState: MicroscopeUiState,
    onRetry: () -> Unit,
    createPreviewView: (android.content.Context) -> android.view.View,
    modifier: Modifier = Modifier,
) {
    val camera = uiState.camera
    Box(modifier = modifier) {
        AndroidView(factory = createPreviewView, modifier = Modifier.fillMaxSize())
        when {
            // Both of these used to be dead ends: nothing leaves the state on its own
            // and the controls gate on Streaming, so unplugging was the only way out.
            // A denied USB grant is the likelier of the two — the system dialog is
            // dismissed by a screen that locks, and the answer comes back as a refusal.
            camera is MicroscopeState.Error -> ActionableMessage(
                text = stringResource(R.string.microscope_error, camera.message),
                actionLabel = stringResource(R.string.microscope_retry),
                onAction = onRetry,
                modifier = Modifier.align(Alignment.Center),
            )
            camera is MicroscopeState.Unavailable &&
                camera.reason == UnavailableReason.PERMISSION_DENIED -> ActionableMessage(
                text = stringResource(R.string.microscope_usb_permission_denied),
                actionLabel = stringResource(R.string.microscope_retry),
                onAction = onRetry,
                modifier = Modifier.align(Alignment.Center),
            )
            // Connecting deliberately gets no Retry: the only way back from a lost
            // surface is onSurfaceTextureAvailable, which fires on its own, and
            // retry() would just tear down the reopen that is already under way.
            camera !is MicroscopeState.Streaming -> StatusMessage(
                state = camera,
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
private fun IdentifyPestButton(onIdentify: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onIdentify,
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        Text(text = stringResource(R.string.microscope_identify_pest))
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

/** A message the user can act on, rather than a dead end they can only read. */
@Composable
private fun ActionableMessage(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = text, textAlign = TextAlign.Center)
        Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = actionLabel)
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
        MicroscopeState.Connecting -> stringResource(R.string.microscope_connecting)
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
