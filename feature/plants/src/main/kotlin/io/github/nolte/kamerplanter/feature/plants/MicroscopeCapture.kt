package io.github.nolte.kamerplanter.feature.plants

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeState
import io.github.nolte.kamerplanter.feature.microscope.UnavailableReason
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Takes a diary photo through the USB microscope.
 *
 * The third source, beside the phone's camera and its photo library, and the one this app was
 * written for: a leaf's underside at 200× is what a diary entry about an infestation is
 * actually about, and it is the picture no phone camera can take.
 *
 * Shown in place of the entry form rather than beside it. A live preview needs the width of
 * the dialogue to be worth aiming with, and swapping the content keeps the text already typed
 * and the photos already picked — which navigating to a capture screen would not.
 */
@Composable
internal fun MicroscopeCapture(
    microscope: MicroscopeAccess,
    onCaptured: (ByteArray) -> Unit,
    onCancel: () -> Unit,
) {
    var isCapturing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state by microscope.state.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // Always composed, never gated on the state — the stream opens *onto* this
            // surface. Creating it only once the camera reports `Streaming` is a deadlock:
            // no surface, so no stream; no stream, so never `Streaming`. The preview sat at
            // "starting…" forever. `MicroscopeScreen` composes it unconditionally and lays
            // its messages over the top, which is the shape that works.
            AndroidView(
                factory = microscope.createPreviewView,
                modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT),
            )
            if (state !is MicroscopeState.Streaming) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = stringResource(state.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            if (isCapturing) CircularProgressIndicator()
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onCancel, enabled = !isCapturing) {
                Text(stringResource(R.string.plants_note_cancel))
            }
            Button(
                onClick = {
                    isCapturing = true
                    scope.launch {
                        val jpeg = microscope.capture()
                        isCapturing = false
                        // A frame that could not be read leaves the preview open rather than
                        // returning to the form empty-handed: the usual cause is that the
                        // sample moved, and the next press is the remedy.
                        if (jpeg != null) onCaptured(jpeg)
                    }
                },
                enabled = state is MicroscopeState.Streaming && !isCapturing,
            ) {
                Text(stringResource(R.string.plants_note_microscope_shutter))
            }
        }
    }
}

/**
 * Binds the microscope while the preview is on screen, and releases it afterwards.
 *
 * A USB camera is exclusive: holding it open behind a closed dialogue would leave the
 * microscope tab — and the pest-detection flow — with nothing to bind.
 */
@Composable
internal fun MicroscopeSession(start: () -> Unit, stop: () -> Unit) {
    DisposableEffect(Unit) {
        start()
        onDispose(stop)
    }
}

/**
 * What the surface says while it is not streaming.
 *
 * `AwaitingPermission` gets its own sentence rather than falling in with "starting": the
 * system's USB dialogue is waiting for an answer, and telling the user something is loading
 * while it waits for them is how a dialogue gets dismissed unanswered.
 */
private fun MicroscopeState.messageRes(): Int = when (this) {
    is MicroscopeState.Unavailable -> when (reason) {
        UnavailableReason.PERMISSION_DENIED -> R.string.plants_microscope_denied
        UnavailableReason.NO_USB_HOST_SUPPORT -> R.string.plants_microscope_no_host
        UnavailableReason.NO_DEVICE_ATTACHED -> R.string.plants_microscope_unavailable
    }
    MicroscopeState.AwaitingPermission -> R.string.plants_microscope_awaiting_permission
    is MicroscopeState.Error -> R.string.plants_microscope_error
    else -> R.string.plants_microscope_starting
}

private val PREVIEW_HEIGHT = 220.dp

/**
 * The microscope, as much of it as writing a diary entry needs.
 *
 * Bundled rather than five members on the ViewModel: they are one capability, and split apart
 * they read as five unrelated abilities a plant's page happens to have. Carries the state as a
 * flow, not a value, so the surface reflects a microscope unplugged while the dialogue is open.
 */
internal data class MicroscopeAccess(
    val state: StateFlow<MicroscopeState>,
    val createPreviewView: (Context) -> View,
    val start: () -> Unit,
    val stop: () -> Unit,
    val capture: suspend () -> ByteArray?,
)
