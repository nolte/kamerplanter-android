package io.github.nolte.kamerplanter.feature.pestdetection

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nolte.kamerplanter.core.camera.PhoneCameraPreview
import io.github.nolte.kamerplanter.core.camera.PhoneCameraShutter
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeState
import io.github.nolte.kamerplanter.feature.microscope.UnavailableReason

/** What the capture surfaces can ask the ViewModel to do. */
internal class CaptureActions(
    val createPreviewView: (Context) -> android.view.View,
    val onRetryCamera: () -> Unit,
    val onChooseSource: (CaptureSource?) -> Unit,
    val onPhoneShutterReady: (PhoneCameraShutter?) -> Unit,
)

/**
 * Everything between choosing a camera and having a photo.
 *
 * One composable rather than three branches in the screen's dispatch, because the three are one
 * step from the user's side: pick a camera, look through it, press the shutter. The dispatch
 * stays a list of states, and this owns the only one with a shape of its own.
 */
@Composable
internal fun CaptureStep(
    state: PestDetectionState.Ready,
    camera: MicroscopeState,
    actions: CaptureActions,
    modifier: Modifier = Modifier,
) {
    when (state.source) {
        null -> SourcePicker(
            // Attached is enough to offer it — whether it is streaming yet is the viewfinder's
            // problem, and a picker that waited for a stream would look broken while the USB
            // dialogue is open.
            microscopeAttached = camera !is MicroscopeState.Unavailable,
            onChoose = actions.onChooseSource,
            modifier = modifier,
        )
        CaptureSource.MICROSCOPE -> Viewfinder(
            camera = camera,
            createPreviewView = actions.createPreviewView,
            onRetryCamera = actions.onRetryCamera,
            onChangeSource = { actions.onChooseSource(null) },
            modifier = modifier,
        )
        CaptureSource.PHONE -> PhoneViewfinder(
            onShutterReady = actions.onPhoneShutterReady,
            onChangeSource = { actions.onChooseSource(null) },
            modifier = modifier,
        )
    }
}

/**
 * Which camera to photograph with.
 *
 * Both feed the same detection, so the choice is not about capability — it is about the
 * subject, and only the user knows what they are looking at. The backend favours opposite
 * modes for the two: a whole leaf carries the damage pattern, and the animal itself is
 * usually too small for a phone to resolve at all.
 *
 * The microscope option is shown but disabled without a device rather than hidden, so its
 * absence reads as "not plugged in" instead of "this app cannot do that".
 */
@Composable
private fun SourcePicker(
    microscopeAttached: Boolean,
    onChoose: (CaptureSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.pest_source_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        SourceOption(
            title = R.string.pest_source_phone,
            hint = R.string.pest_source_phone_hint,
            enabled = true,
            onClick = { onChoose(CaptureSource.PHONE) },
        )
        SourceOption(
            title = R.string.pest_source_microscope,
            hint = if (microscopeAttached) {
                R.string.pest_source_microscope_hint
            } else {
                R.string.pest_source_microscope_missing
            },
            enabled = microscopeAttached,
            onClick = { onChoose(CaptureSource.MICROSCOPE) },
        )
    }
}

@Composable
private fun SourceOption(
    @StringRes title: Int,
    @StringRes hint: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(text = stringResource(hint), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onClick, enabled = enabled) {
                Text(stringResource(title))
            }
        }
    }
}

/** The device camera's live preview, with a way back to the picker. */
@Composable
private fun PhoneViewfinder(
    onShutterReady: (PhoneCameraShutter?) -> Unit,
    onChangeSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        PhoneCameraPreview(onShutterReady = onShutterReady, modifier = Modifier.fillMaxSize())
        TextButton(onClick = onChangeSource, modifier = Modifier.align(Alignment.TopStart)) {
            Text(stringResource(R.string.pest_source_change))
        }
    }
}

@Composable
private fun Viewfinder(
    camera: MicroscopeState,
    createPreviewView: (Context) -> android.view.View,
    onRetryCamera: () -> Unit,
    onChangeSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AndroidView(factory = createPreviewView, modifier = Modifier.fillMaxSize())
        TextButton(onClick = onChangeSource, modifier = Modifier.align(Alignment.TopStart)) {
            Text(stringResource(R.string.pest_source_change))
        }
        // Says what is true rather than always "no device": the stream passes through
        // Connecting on every handover, and through AwaitingPermission while the user is
        // looking at the USB dialogue. Telling them to plug in a microscope they have already
        // plugged in is the failure this camera's own teardown path takes care to avoid.
        val waiting = when (camera) {
            is MicroscopeState.Unavailable -> when (camera.reason) {
                UnavailableReason.NO_USB_HOST_SUPPORT -> R.string.pest_no_usb_host
                // Its own message: telling someone who declined the USB dialogue to attach the
                // microscope they already attached is the failure this whole block exists to
                // stop, and it was still in the `else` branch.
                UnavailableReason.PERMISSION_DENIED -> R.string.pest_usb_permission_denied
                UnavailableReason.NO_DEVICE_ATTACHED -> R.string.pest_no_device
            }
            MicroscopeState.AwaitingPermission -> R.string.pest_awaiting_usb_permission
            MicroscopeState.Connecting -> R.string.pest_connecting
            is MicroscopeState.Error -> R.string.pest_camera_error
            MicroscopeState.Streaming -> null
        }
        if (waiting != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            ) {
                Text(text = stringResource(waiting), textAlign = TextAlign.Center)
                // A declined USB grant and a failed stream are both dead ends otherwise:
                // nothing leaves either state on its own, so without this the only way on is
                // to navigate away and back. The waiting states need no button — they resolve
                // themselves.
                if (camera.isDeadEnd()) {
                    Button(onClick = onRetryCamera) {
                        Text(stringResource(R.string.pest_failed_retry))
                    }
                }
            }
        }
    }
}

/**
 * Whether the shutter can actually fire.
 *
 * Neither source is ready the moment it is chosen: binding the phone camera takes a beat, and
 * the microscope has to be streaming, which it is not while a handover or a USB dialogue is in
 * flight. A shutter offered before either is a control whose press ends the flow on an error.
 */
internal fun PestDetectionState.Ready.canFire(camera: MicroscopeState): Boolean = when (source) {
    null -> false
    // Binding the phone camera is asynchronous, and a shutter pressed before it finishes does
    // not fail quietly — it ends the flow on an error screen and loses the chosen source.
    CaptureSource.PHONE -> phoneReady
    CaptureSource.MICROSCOPE -> camera is MicroscopeState.Streaming
}

/** States nothing leaves on its own — the ones that need a button rather than patience. */
private fun MicroscopeState.isDeadEnd(): Boolean =
    this is MicroscopeState.Error ||
        (this is MicroscopeState.Unavailable && reason == UnavailableReason.PERMISSION_DENIED)
