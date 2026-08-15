package io.github.nolte.kamerplanter.feature.pestdetection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nolte.kamerplanter.core.network.Detection
import io.github.nolte.kamerplanter.core.network.Finding
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Photograph something small through the USB microscope and have the instance say what it is
 * (issue #10).
 *
 * Everything the screen shows about the finding comes from the instance — the labels, the
 * boxes and the disclaimer. Nothing is recognized, scored or reworded here.
 *
 * The CAMERA permission is a platform gate rather than a library one, the same way it is on
 * the microscope screen: AOSP refuses to show the USB permission dialog for a video-class
 * device unless the requesting app holds it.
 */
@Composable
fun PestDetectionScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PestDetectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val camera by viewModel.cameraState.collectAsStateWithLifecycle()
    // The language the instance should answer in, taken from the configuration that picked
    // this screen's own strings — so a finding's name is in the language the rest of the
    // screen is written in, whether that came from the system or from a per-app override.
    val language = LocalConfiguration.current.locales[0].language
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
            // Only where a shutter can actually fire: a device has to be streaming, and the
            // instance has to have said it will look at the frame.
            val ready = state as? PestDetectionState.Ready
            if (ready != null && permission.isGranted && camera is MicroscopeState.Streaming) {
                ExtendedFloatingActionButton(onClick = { viewModel.capture(language) }) {
                    Text(
                        stringResource(
                            if (ready.isUploading) R.string.pest_capturing else R.string.pest_capture,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        val content = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        if (permission.isGranted) {
            PestDetectionContent(
                state = state,
                camera = camera,
                actions = PestDetectionActions(
                    onOpenSettings = onOpenSettings,
                    onGrantConsent = viewModel::grantConsent,
                    onRetry = viewModel::checkInstance,
                    onCaptureAgain = viewModel::captureAgain,
                    createPreviewView = viewModel::createPreviewView,
                ),
                modifier = content,
            )
        } else {
            Explanation(
                title = stringResource(R.string.pest_title),
                body = stringResource(R.string.pest_camera_permission),
                action = ExplanationAction(
                    stringResource(R.string.pest_grant_permission),
                    permission.request,
                ),
                modifier = content,
            )
        }
    }
}

/** What the screen can ask the ViewModel to do, bundled so the dispatch stays readable. */
private class PestDetectionActions(
    val onOpenSettings: () -> Unit,
    val onGrantConsent: () -> Unit,
    val onRetry: () -> Unit,
    val onCaptureAgain: () -> Unit,
    val createPreviewView: (Context) -> android.view.View,
)

private class CameraPermission(val isGranted: Boolean, val request: () -> Unit)

/**
 * The CAMERA grant, kept current.
 *
 * Re-read on resume rather than sampled once: granting from system Settings does not restart
 * the process, so a value read at first composition would keep showing the rationale to
 * someone who has already said yes.
 */
@Composable
private fun rememberCameraPermission(): CameraPermission {
    val context = LocalContext.current
    var isGranted by remember { mutableStateOf(context.hasCameraPermission()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isGranted = context.hasCameraPermission()
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> isGranted = granted }

    LaunchedEffect(isGranted) {
        if (!isGranted) launcher.launch(Manifest.permission.CAMERA)
    }
    return CameraPermission(isGranted) { launcher.launch(Manifest.permission.CAMERA) }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun PestDetectionContent(
    state: PestDetectionState,
    camera: MicroscopeState,
    actions: PestDetectionActions,
    modifier: Modifier = Modifier,
) {
    when (state) {
        PestDetectionState.CheckingInstance -> Busy(stringResource(R.string.pest_checking), modifier)

        PestDetectionState.NotConnected -> Explanation(
            title = stringResource(R.string.pest_not_connected_title),
            body = stringResource(R.string.pest_not_connected_body),
            action = ExplanationAction(
                stringResource(R.string.pest_not_connected_action),
                actions.onOpenSettings,
            ),
            modifier = modifier,
        )

        PestDetectionState.Unauthorized -> Explanation(
            title = stringResource(R.string.pest_rejected_title),
            body = stringResource(R.string.pest_rejected_body),
            action = ExplanationAction(
                stringResource(R.string.pest_not_connected_action),
                actions.onOpenSettings,
            ),
            modifier = modifier,
        )

        // No action either: a scope is widened on the instance, not by asking again from
        // here, and pointing at Settings would send the user to re-pair a connection that
        // is working exactly as it should.
        PestDetectionState.NotPermitted -> Explanation(
            title = stringResource(R.string.pest_not_permitted_title),
            body = stringResource(R.string.pest_failed_not_permitted),
            modifier = modifier,
        )

        // No action: the operator enables this on the instance, and offering a retry would
        // suggest the user can change the answer by asking again.
        PestDetectionState.NotOffered -> Explanation(
            title = stringResource(R.string.pest_not_offered_title),
            body = stringResource(R.string.pest_not_offered_body),
            modifier = modifier,
        )

        is PestDetectionState.ConsentRequired ->
            ConsentPrompt(state, actions.onGrantConsent, modifier)

        is PestDetectionState.Ready -> Viewfinder(
            camera = camera,
            createPreviewView = actions.createPreviewView,
            modifier = modifier,
        )

        is PestDetectionState.Result -> DetectionResult(
            frame = state.frame,
            detection = state.detection,
            onCaptureAgain = actions.onCaptureAgain,
            modifier = modifier,
        )

        is PestDetectionState.Failed -> Explanation(
            title = stringResource(R.string.pest_failed_title),
            body = stringResource(state.message),
            action = ExplanationAction(
                stringResource(R.string.pest_failed_retry),
                actions.onRetry,
            ).takeIf { state.canRetry },
            modifier = modifier,
        )
    }
}

/**
 * Asks for the consent the active adapter needs, in the instance's own words.
 *
 * The wording is rendered verbatim: this is an Art. 6(1)(a) GDPR consent, and app-authored
 * text would have the user agreeing to something this app invented about processing it does
 * not perform. The app's own strings appear only where the instance supplied none at all.
 */
@Composable
private fun ConsentPrompt(
    state: PestDetectionState.ConsentRequired,
    onGrantConsent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Explanation(
        title = state.terms?.label ?: stringResource(R.string.pest_consent_title),
        body = state.terms?.description ?: stringResource(R.string.pest_consent_body),
        footnote = state.terms?.legalBasis?.let { stringResource(R.string.pest_consent_basis, it) },
        action = ExplanationAction(
            stringResource(
                if (state.isGranting) R.string.pest_consent_working else R.string.pest_consent_action,
            ),
            onGrantConsent.takeUnless { state.isGranting },
        ),
        modifier = modifier,
    )
}

@Composable
private fun Viewfinder(
    camera: MicroscopeState,
    createPreviewView: (Context) -> android.view.View,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AndroidView(factory = createPreviewView, modifier = Modifier.fillMaxSize())
        if (camera !is MicroscopeState.Streaming) {
            Text(
                text = stringResource(R.string.pest_no_device),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }
    }
}

@Composable
private fun DetectionResult(
    frame: ByteArray,
    detection: Detection,
    onCaptureAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Only a confident detection draws boxes. The backend may list what it saw below its
        // own threshold, and the findings list already suppresses those — drawing them anyway
        // would put the marks of a result on screen under the words "no reliable
        // identification", which is the more persuasive of the two.
        AnnotatedCapture(
            frame = frame,
            findings = if (detection.outcome() == DetectionShape.FINDINGS) detection.findings else emptyList(),
        )

        // Three outcomes, not two, and the difference between the last two is the whole point.
        // "I could not tell" and "I looked and there is nothing" are opposite answers: reading
        // the first as the second would tell someone their plant is fine when the recognizer
        // declined to say so.
        when (detection.outcome()) {
            DetectionShape.FINDINGS -> {
                Text(
                    text = stringResource(R.string.pest_result_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                detection.findings.forEach { FindingCard(it) }
            }
            DetectionShape.ABSTAINED -> Verdict(
                title = stringResource(R.string.pest_abstained_title),
                body = stringResource(R.string.pest_abstained_body),
            )
            DetectionShape.NOTHING_FOUND -> Verdict(
                title = stringResource(R.string.pest_nothing_found_title),
                body = stringResource(R.string.pest_nothing_found_body),
            )
        }

        Text(
            text = detection.suggestedNextStep,
            style = MaterialTheme.typography.bodyMedium,
        )
        // Verbatim, always. It is what keeps a recognizer's guess from reading like a
        // diagnosis, and rewording it would put this app's words on the instance's position.
        Text(
            text = detection.disclaimer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.pest_tiles, detection.tilesProcessed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = onCaptureAgain, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pest_result_again))
        }
    }
}

/** The screen's answer when there is no findings list to show. */
@Composable
private fun Verdict(title: String, body: String) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    Text(text = body)
}

@Composable
private fun FindingCard(finding: Finding) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.pest_finding,
                        finding.commonName,
                        (finding.confidence * PERCENT).roundToInt(),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (finding.isBeneficial) {
                    Text(
                        text = stringResource(R.string.pest_beneficial_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Text(
                text = stringResource(
                    // Only these two modes carry a meaning the user can act on. Anything else
                    // the backend names is left unlabelled rather than guessed at (R-COMPAT-3).
                    if (finding.mode == MODE_DIRECT) R.string.pest_mode_direct else R.string.pest_mode_symptom,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (finding.isBeneficial) {
                Text(
                    text = stringResource(R.string.pest_beneficial_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * The captured frame with each localized finding drawn over it.
 *
 * The boxes are normalized to the full image, so they survive the subsampling the decode does
 * — and they have to be placed against the *drawn* rectangle rather than the composable's
 * bounds, because `ContentScale.Fit` letterboxes a frame whose aspect ratio differs from the
 * space it is given. Placing them against the bounds would slide every box off the subject.
 */
@Composable
private fun AnnotatedCapture(frame: ByteArray, findings: List<Finding>) {
    // The bounds pass is cheap — inJustDecodeBounds reads the header and allocates no pixels —
    // so it stays here and gives the layout the aspect ratio up front. Without that the
    // picture appears late and shoves the findings, the disclaimer and the button down the
    // screen just as the user starts reading them.
    val shape = remember(frame) { frame.decodeBounds() } ?: return
    // The pixels are decoded off the main thread: two BitmapFactory passes over a
    // multi-megabyte JPEG during composition is a visible hitch at the exact moment this flow
    // pays off, and it is the one frame the user is certain to be looking at.
    val bitmap by produceState<ImageBitmap?>(initialValue = null, frame) {
        value = withContext(Dispatchers.Default) { frame.decodeSubsampled() }
    }
    val pestColor = MaterialTheme.colorScheme.error
    val beneficialColor = MaterialTheme.colorScheme.tertiary
    val description = stringResource(R.string.pest_photo_description)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(shape.width.toFloat() / shape.height)
            // One image as far as a screen reader is concerned: the overlay carries no text,
            // and the findings below it say everything the boxes show.
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val drawn = bitmap ?: return@Box
        Image(
            bitmap = drawn,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
        Canvas(modifier = Modifier.matchParentSize()) {
            val image = Size(drawn.width.toFloat(), drawn.height.toFloat())
            findings.forEach { finding ->
                val box = finding.boundingBox ?: return@forEach
                val rect = overlayRect(box, canvas = size, image = image)
                drawRect(
                    color = if (finding.isBeneficial) beneficialColor else pestColor,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = Stroke(width = BOX_STROKE_DP.dp.toPx()),
                )
            }
        }
    }
}

/**
 * Decodes the capture at roughly display size.
 *
 * A full 4K frame is ~33 MB of ARGB_8888 held for a view a few hundred pixels wide — an OOM
 * candidate on a low-RAM device, and jank everywhere else.
 */
private fun ByteArray.decodeSubsampled(): ImageBitmap? {
    val bounds = decodeBounds() ?: return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.width) }
    return BitmapFactory.decodeByteArray(this, 0, size, options)?.asImageBitmap()
}

/** Pixel dimensions of a capture, read from its header without decoding it. */
private class ImageShape(val width: Int, val height: Int)

private fun ByteArray.decodeBounds(): ImageShape? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(this, 0, size, bounds)
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        ImageShape(bounds.outWidth, bounds.outHeight)
    } else {
        null
    }
}

/**
 * The subsampling factor for a capture [sourceWidth] pixels wide.
 *
 * The smallest power of two that brings the width to [target] or below. A power of two
 * because `BitmapFactory` rounds anything else *down* to one — the arithmetic factor for a
 * 3840-wide frame is 3, which the decoder silently reads as 2, so it decodes at 1920 px and
 * ~14 MB of ARGB_8888 instead of the ~4 MB asked for.
 *
 * Smallest, not merely sufficient: doubling once more halves the resolution again, and a
 * 2160-wide capture would come back at 540 px — a visibly soft picture underneath boxes the
 * user is looking at closely, which is the opposite of what this screen is for.
 */
internal fun sampleSizeFor(sourceWidth: Int, target: Int = DISPLAY_TARGET_PX): Int {
    if (sourceWidth <= target) return 1
    var sample = 2
    while (sourceWidth / sample > target) sample *= 2
    return sample
}

@Composable
private fun Busy(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(text = text, textAlign = TextAlign.Center)
    }
}

/** A message with an optional way out of it — never a dead end the user can only read. */
@Composable
private fun Explanation(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    /** Secondary line under the body — the consent's legal basis, where there is one. */
    footnote: String? = null,
    action: ExplanationAction? = null,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text = body, textAlign = TextAlign.Center)
        if (footnote != null) {
            Text(
                text = footnote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Button(
                onClick = action.onClick ?: {},
                // A label with no handler is a button mid-work: it says what is happening
                // rather than disappearing, which would make the screen jump.
                enabled = action.onClick != null,
            ) { Text(action.label) }
        }
    }
}

/** A button under an [Explanation]; a null [onClick] renders it as disabled. */
private class ExplanationAction(val label: String, val onClick: (() -> Unit)?)

private const val PERCENT = 100
private const val BOX_STROKE_DP = 3
private const val MODE_DIRECT = "direct"

/** Roughly the widest the capture is ever drawn, in pixels — the decode need not beat it. */
internal const val DISPLAY_TARGET_PX = 1080
