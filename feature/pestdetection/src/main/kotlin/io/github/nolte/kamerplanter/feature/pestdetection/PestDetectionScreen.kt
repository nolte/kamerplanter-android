package io.github.nolte.kamerplanter.feature.pestdetection

import android.graphics.BitmapFactory
import androidx.annotation.StringRes
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nolte.kamerplanter.core.camera.CameraPermission
import io.github.nolte.kamerplanter.core.camera.rememberCameraPermission
import io.github.nolte.kamerplanter.core.camera.sampleSizeFor
import io.github.nolte.kamerplanter.core.network.Detection
import io.github.nolte.kamerplanter.core.network.Finding
import io.github.nolte.kamerplanter.core.network.RecordedFeedback
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

    // Only while a capture could actually follow. USB monitoring asks the user for device
    // access, and asking on a screen that is about to say "your instance does not offer this"
    // spends a permission dialogue on something they cannot use.
    val wantsCamera = permission.isGranted &&
        (state is PestDetectionState.Ready || state is PestDetectionState.Result)
    // USB monitoring is only worth starting for the source that uses it. Asking someone who
    // picked the phone camera for access to a USB device would be a dialogue about nothing —
    // and a Result carries no source of its own, so reading only Ready would pop that dialogue
    // over the very photo they just took with the other camera.
    val chosenSource by viewModel.chosenSource.collectAsStateWithLifecycle()
    val wantsMicroscope = wantsCamera && chosenSource != CaptureSource.PHONE
    if (wantsMicroscope) {
        DisposableEffect(Unit) {
            viewModel.microscope.start()
            onDispose { viewModel.microscope.stop() }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            Shutter(
                state = state,
                camera = camera,
                enabled = wantsCamera,
                onCapture = { viewModel.capture(language) },
            )
        },
    ) { innerPadding ->
        val content = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        if (permission.isGranted) {
            PestDetectionContent(
                state = state,
                camera = camera,
                isPlantBound = viewModel.isPlantBound,
                actions = viewModel.actions(onOpenSettings),
                modifier = content,
            )
        } else {
            MissingCameraPermission(permission, content)
        }
    }

    // Over whatever the flow is showing, and closed without disturbing it: looking up the last
    // check must not cost a viewfinder that is already streaming or a result on screen.
    val history by viewModel.history.collectAsStateWithLifecycle()
    if (history != DetectionHistoryState.Hidden) {
        PastChecks(state = history, onClose = viewModel::hideHistory)
    }
}

/**
 * What this plant has been checked for before.
 *
 * A dialog rather than a destination: it is context for a decision being made right now — is
 * this the same thing as last month, and did anyone confirm it — and pushing a screen for it
 * would take the user away from the capture they came to make.
 */
@Composable
private fun PastChecks(state: DetectionHistoryState, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.pest_history_close)) }
        },
        title = { Text(stringResource(R.string.pest_history_title)) },
        text = {
            when (state) {
                DetectionHistoryState.Hidden,
                DetectionHistoryState.Loading,
                -> Text(stringResource(R.string.pest_history_loading))
                is DetectionHistoryState.Failed -> Text(stringResource(state.message))
                is DetectionHistoryState.Shown -> if (state.detections.isEmpty()) {
                    // A plant nobody has checked yet is not an error, and it is the ordinary
                    // state of most plants.
                    Text(stringResource(R.string.pest_history_empty))
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        state.detections.forEach { PastCheck(it) }
                    }
                }
            }
        },
    )
}

/**
 * One past check: when, and what came of it.
 *
 * The date is printed as the instance wrote it. Parsing it into a device-formatted one would
 * mean guessing at a shape this build has not been promised, and a row that says nothing
 * because a timestamp did not parse is worse than one that says more than it needs to.
 */
@Composable
private fun PastCheck(detection: Detection) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        detection.recordedAt?.let {
            Text(text = it, style = MaterialTheme.typography.labelMedium)
        }
        val summary = if (detection.isConfident) {
            stringResource(R.string.pest_history_findings, detection.findings.size)
        } else {
            stringResource(R.string.pest_history_abstained)
        }
        Text(text = summary, style = MaterialTheme.typography.bodyMedium)
        // The names themselves, because "2 findings" answers nothing a gardener asked.
        detection.findings.take(HISTORY_NAMES).forEach {
            Text(
                text = it.commonName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How many names a past check lists before the dialog would start scrolling for one row. */
private const val HISTORY_NAMES = 3

/**
 * Everything the content can ask for, bound to this ViewModel.
 *
 * Assembled here rather than inline in the screen so the screen reads as what it lays out,
 * and so the wiring is one thing to check when an action is added.
 */
@Composable
private fun PestDetectionViewModel.actions(onOpenSettings: () -> Unit) = PestDetectionActions(
    onOpenSettings = onOpenSettings,
    onGrantConsent = ::grantConsent,
    onRetry = ::checkInstance,
    onCaptureAgain = ::captureAgain,
    result = ResultActions(
        onFeedback = ::recordFeedback,
        onFileInspection = ::fileInspection,
        onShowHistory = ::showHistory,
    ),
    capture = CaptureActions(
        createPreviewView = microscope::createPreviewView,
        // Restarted rather than left dead: a refused USB dialogue is not asked again on its
        // own, and a stream that failed to open is not retried.
        onRetryCamera = microscope::restart,
        onChooseSource = ::chooseSource,
        onPhoneShutterReady = { phoneShutter = it },
    ),
)

/**
 * The capture button, shown only where it could actually fire.
 *
 * A device has to be streaming and the instance has to have said it will look at the frame —
 * a shutter offered before either is a button whose only outcome is an error screen.
 */
@Composable
private fun Shutter(
    state: PestDetectionState,
    camera: MicroscopeState,
    enabled: Boolean,
    onCapture: () -> Unit,
) {
    val ready = state as? PestDetectionState.Ready ?: return
    if (!enabled || !ready.canFire(camera)) return
    ExtendedFloatingActionButton(onClick = onCapture) {
        Text(
            stringResource(
                if (ready.isUploading) R.string.pest_capturing else R.string.pest_capture,
            ),
        )
    }
}

/** What the screen can ask the ViewModel to do, bundled so the dispatch stays readable. */
private class PestDetectionActions(
    val onOpenSettings: () -> Unit,
    val onGrantConsent: () -> Unit,
    val onRetry: () -> Unit,
    val onCaptureAgain: () -> Unit,
    val result: ResultActions,
    val capture: CaptureActions,
)

/** What can be done with a detection once it is on screen. */
private class ResultActions(
    val onFeedback: (findingLabel: String, verdict: FeedbackVerdict) -> Unit,
    val onFileInspection: () -> Unit,
    val onShowHistory: () -> Unit,
)

@Composable
private fun PestDetectionContent(
    state: PestDetectionState,
    camera: MicroscopeState,
    /** Whether this flow was entered from a plant — what past checks and inspections need. */
    isPlantBound: Boolean,
    actions: PestDetectionActions,
    modifier: Modifier = Modifier,
) {
    when (state) {
        PestDetectionState.CheckingInstance -> Busy(stringResource(R.string.pest_checking), modifier)

        // Both send the user to Settings, because that is where a connection is made or
        // remade, and neither can be fixed from here.
        PestDetectionState.NotConnected -> SettingsPrompt(
            title = R.string.pest_not_connected_title,
            body = R.string.pest_not_connected_body,
            onOpenSettings = actions.onOpenSettings,
            modifier = modifier,
        )

        PestDetectionState.Unauthorized -> SettingsPrompt(
            title = R.string.pest_rejected_title,
            body = R.string.pest_rejected_body,
            onOpenSettings = actions.onOpenSettings,
            modifier = modifier,
        )

        // A retry is offered, but the wording promises nothing: an answer this build cannot
        // read will read the same way again, and only a change on the instance — or a newer
        // app — will alter it. Saying "your instance could not be reached" here would send its
        // owner to check a server that answered perfectly well.
        PestDetectionState.NotUnderstood -> Explanation(
            title = stringResource(R.string.pest_not_understood_title),
            body = stringResource(R.string.pest_not_understood_body),
            action = ExplanationAction(stringResource(R.string.pest_failed_retry), actions.onRetry),
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

        is PestDetectionState.Ready -> CaptureStep(
            state = state,
            camera = camera,
            actions = actions.capture,
            modifier = modifier,
            onShowHistory = actions.result.onShowHistory.takeIf { isPlantBound },
        )

        is PestDetectionState.Result -> DetectionResult(
            state = state,
            onCaptureAgain = actions.onCaptureAgain,
            actions = actions.result,
            modifier = modifier,
        )

        // Even a failure a retry cannot fix gets a way out: without one these screens have no
        // control at all, and the only exit is the system back gesture.
        is PestDetectionState.Failed -> Explanation(
            title = stringResource(R.string.pest_failed_title),
            body = stringResource(state.message),
            action = if (state.canRetry) {
                ExplanationAction(stringResource(R.string.pest_failed_retry), actions.onRetry)
            } else {
                // A way out, not a remedy — and labelled as one. Sending the same frame again
                // would meet the same fixed proxy limit or the same missing permission, so the
                // button returns to the viewfinder and says exactly that.
                ExplanationAction(stringResource(R.string.pest_back_to_viewfinder), actions.onCaptureAgain)
            },
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

/**
 * Asks for the camera grant, and offers the only route back once it is refused for good.
 *
 * After "Don't ask again" the system stops prompting, so a request button there is a control
 * that visibly does nothing — and this screen would be a dead end with no way out but the back
 * gesture.
 */
@Composable
private fun MissingCameraPermission(permission: CameraPermission, modifier: Modifier = Modifier) {
    Explanation(
        title = stringResource(R.string.pest_title),
        body = stringResource(R.string.pest_camera_permission),
        action = ExplanationAction(
            stringResource(
                if (permission.canAsk) R.string.pest_grant_permission else R.string.pest_open_settings,
            ),
            if (permission.canAsk) permission.request else permission.openSettings,
        ),
        modifier = modifier,
    )
}

@Composable
private fun SettingsPrompt(
    @StringRes title: Int,
    @StringRes body: Int,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Explanation(
        title = stringResource(title),
        body = stringResource(body),
        action = ExplanationAction(
            stringResource(R.string.pest_not_connected_action),
            onOpenSettings,
        ),
        modifier = modifier,
    )
}

@Composable
private fun DetectionResult(
    state: PestDetectionState.Result,
    onCaptureAgain: () -> Unit,
    actions: ResultActions,
    modifier: Modifier = Modifier,
) {
    val frame = state.frame
    val detection = state.detection
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

        WhatTheInstanceSaw(state = state, onFeedback = actions.onFeedback)

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

        ResultFooter(state = state, onCaptureAgain = onCaptureAgain, actions = actions)
    }
}

/**
 * What can be done about a detection, under what it says.
 *
 * Split out so [DetectionResult] stays a rendering of the instance's answer and this stays
 * the list of what follows from it.
 */
@Composable
private fun ResultFooter(
    state: PestDetectionState.Result,
    onCaptureAgain: () -> Unit,
    actions: ResultActions,
) {
    // What just happened, beside the findings rather than instead of them. None of these end
    // the flow: a refused verdict leaves the detection exactly as it was.
    state.notice?.let {
        Text(
            text = stringResource(it),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Only on the plant-bound path, and only for a detection the instance kept: the endpoint
    // files an inspection against a plant, using a key it only issues for a persisted
    // detection.
    if (state.plantBound && state.detection.key != null) {
        OutlinedButton(
            onClick = actions.onFileInspection,
            enabled = !state.filingInspection && !state.inspectionFiled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.pest_inspection_create))
        }
        TextButton(onClick = actions.onShowHistory, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pest_history_open))
        }
    }

    Button(onClick = onCaptureAgain, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.pest_result_again))
    }
}

/**
 * The findings, or the sentence that stands in for them.
 *
 * Three outcomes, not two, and the difference between the last two is the whole point. "I
 * could not tell" and "I looked and there is nothing" are opposite answers: reading the first
 * as the second would tell someone their plant is fine when the recognizer declined to say so.
 */
@Composable
private fun WhatTheInstanceSaw(
    state: PestDetectionState.Result,
    onFeedback: (findingLabel: String, verdict: FeedbackVerdict) -> Unit,
) {
    val detection = state.detection
    when (detection.outcome()) {
        DetectionShape.FINDINGS -> {
            Text(
                text = stringResource(R.string.pest_result_title),
                style = MaterialTheme.typography.titleMedium,
            )
            detection.findings.forEach { finding ->
                FindingCard(
                    finding = finding,
                    // Only where the instance kept the detection. Without a key there is
                    // nothing to attach a verdict to, and offering the buttons would be
                    // offering an action that cannot be taken.
                    feedback = if (detection.key == null) {
                        null
                    } else {
                        FindingFeedback(
                            recorded = state.verdictOn(finding.label),
                            isRecording = state.recordingFor == finding.label,
                            onVerdict = { onFeedback(finding.label, it) },
                        )
                    },
                )
            }
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
}

/** The screen's answer when there is no findings list to show. */
@Composable
private fun Verdict(title: String, body: String) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    Text(text = body)
}

/**
 * The verdict controls for one finding, or `null` where none can be offered.
 *
 * Grouped rather than passed as three parameters, so a card that shows no controls says so by
 * holding nothing instead of by three defaults that have to agree with each other.
 */
private class FindingFeedback(
    val recorded: RecordedFeedback?,
    val isRecording: Boolean,
    val onVerdict: (FeedbackVerdict) -> Unit,
)

@Composable
private fun FindingCard(finding: Finding, feedback: FindingFeedback? = null) {
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
            // Only these two modes carry a meaning the user can act on, and anything else the
            // backend names is left unlabelled rather than guessed at (R-COMPAT-3). Calling an
            // unknown mode a damage pattern would make a nutrient deficiency read as pest
            // damage — a wrong label is worse here than none.
            val mode = when (finding.mode) {
                MODE_DIRECT -> R.string.pest_mode_direct
                MODE_SYMPTOM -> R.string.pest_mode_symptom
                else -> null
            }
            if (mode != null) {
                Text(text = stringResource(mode), style = MaterialTheme.typography.bodySmall)
            }
            if (finding.isBeneficial) {
                Text(
                    text = stringResource(R.string.pest_beneficial_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            feedback?.let { FeedbackRow(it) }
        }
    }
}

/**
 * "Was this right?", and the three answers.
 *
 * Once something has been said, the row states it instead of asking again — the instance is
 * the one holding the verdict, and re-offering the buttons over an answer it already has
 * invites the user to send the same thing twice.
 *
 * "It is a beneficial" is a third button rather than a second tap on "wrong", because it is
 * what the instance most needs to hear: a beneficial reported as a pest is the one mistake
 * this feature must not repeat.
 */
@Composable
private fun FeedbackRow(feedback: FindingFeedback) {
    val recorded = feedback.recorded
    if (recorded != null) {
        Text(
            text = stringResource(recorded.saidRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(
        text = stringResource(R.string.pest_feedback_prompt),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FeedbackVerdict.entries.forEach { verdict ->
            TextButton(
                onClick = { feedback.onVerdict(verdict) },
                enabled = !feedback.isRecording,
            ) {
                Text(stringResource(verdict.labelRes()))
            }
        }
    }
}

private fun FeedbackVerdict.labelRes(): Int = when (this) {
    FeedbackVerdict.CORRECT -> R.string.pest_feedback_correct
    FeedbackVerdict.WRONG -> R.string.pest_feedback_wrong
    FeedbackVerdict.BENEFICIAL -> R.string.pest_feedback_beneficial
}

/**
 * What the recorded verdict says, read back from the instance's own fields.
 *
 * `wasBeneficial` is checked before `confirmed`, because the two are not exclusive in the
 * payload: a verdict that says both would otherwise read as a plain confirmation and lose the
 * part that matters.
 */
private fun RecordedFeedback.saidRes(): Int = when {
    wasBeneficial -> R.string.pest_feedback_said_beneficial
    confirmed -> R.string.pest_feedback_said_correct
    else -> R.string.pest_feedback_said_wrong
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
 * A full 4K frame is ~33 MB of ARGB_8888, held for as long as the result is on screen. The
 * shared `sampleSizeFor` aims at the target rather than under it, so a 4K microscope frame
 * decodes to 1920×1080 — about 8 MB — and a phone photo, already capped at 2048 on upload,
 * decodes unchanged at up to ~17 MB. More than the old undershooting version held, and the
 * point: this is the picture the bounding boxes are drawn over.
 */
private fun ByteArray.decodeSubsampled(): ImageBitmap? {
    val bounds = decodeBounds() ?: return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.width, DISPLAY_TARGET_PX) }
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
private const val MODE_SYMPTOM = "symptom"

/**
 * Roughly the widest the capture is ever drawn.
 *
 * The shared `sampleSizeFor` aims *at* this rather than under it, and that matters here: it
 * only halves, so the smallest factor landing below the target usually lands far below — a
 * 2160-wide capture would come back at 540 px, a visibly soft picture underneath boxes the
 * user is looking at closely, which is the opposite of what this screen is for.
 */
private const val DISPLAY_TARGET_PX = 1080
