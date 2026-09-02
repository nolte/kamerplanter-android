package io.github.nolte.kamerplanter.feature.plants.capture

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nolte.kamerplanter.core.network.PlantOrgan
import io.github.nolte.kamerplanter.core.network.Suggestion
import io.github.nolte.kamerplanter.feature.plants.R
import kotlin.math.roundToInt

/**
 * The identification route, one step at a time (R5–R16).
 *
 * Shown in place of the form rather than beside it, the way the microscope replaces the diary
 * form: a preview and a list of candidates need the width, and what the user typed into the
 * form is still there when they come back. Every step ends in the form — through a chosen
 * candidate, by hand with the photo kept, or by leaving.
 */
@Composable
internal fun IdentificationFlow(
    step: IdentificationStep,
    actions: IdentificationActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (step) {
            is IdentificationStep.Consent -> ConsentStep(step, actions)
            IdentificationStep.ChooseSource -> SourceStep(actions, unusable = false)
            IdentificationStep.Unusable -> SourceStep(actions, unusable = true)
            is IdentificationStep.Preview -> PreviewStep(step, actions)
            is IdentificationStep.Identifying -> WaitingStep(step)
            is IdentificationStep.Suggestions -> SuggestionsStep(step, actions)
            is IdentificationStep.Refused -> DeclinedStep(
                image = step.image,
                title = stringResource(R.string.plants_add_identify_refused),
                detail = step.reason,
                actions = actions,
                retry = null,
            )
            is IdentificationStep.RateLimited -> DeclinedStep(
                image = step.image,
                title = stringResource(R.string.plants_add_identify_rate_limited),
                detail = step.retryAfterSeconds?.let {
                    stringResource(R.string.plants_add_identify_rate_limited_after, it)
                },
                actions = actions,
                retry = { actions.onSend(PlantOrgan.AUTO) },
            )
            is IdentificationStep.Unavailable -> DeclinedStep(
                image = step.image,
                title = stringResource(R.string.plants_add_identify_unavailable),
                detail = null,
                actions = actions,
                retry = { actions.onSend(PlantOrgan.AUTO) },
            )
            IdentificationStep.NotPermitted -> {
                Text(stringResource(R.string.plants_add_identify_not_permitted))
                LeaveButton(actions)
            }
        }
    }
}

/**
 * The consent, in the instance's own words (R6). Rendered verbatim: this is an Art. 6(1)(a)
 * GDPR consent, and app-authored wording would put words in the operator's mouth. The app's
 * own text is only the fallback for an instance that supplied none.
 */
@Composable
private fun ConsentStep(step: IdentificationStep.Consent, actions: IdentificationActions) {
    Text(
        text = step.terms?.label ?: stringResource(R.string.plants_add_consent_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(step.terms?.description ?: stringResource(R.string.plants_add_consent_body))
    step.terms?.legalBasis?.let {
        Text(
            text = stringResource(R.string.plants_add_consent_basis, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (step.failed) {
        Text(
            text = stringResource(R.string.plants_add_consent_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Button(onClick = actions.onGrantConsent, enabled = !step.granting, modifier = Modifier.fillMaxWidth()) {
        val label = if (step.granting) R.string.plants_add_consent_working else R.string.plants_add_consent_action
        Text(stringResource(label))
    }
    LeaveButton(actions)
}

/** Camera or library, asked for only here (R8). */
@Composable
private fun SourceStep(actions: IdentificationActions, unusable: Boolean) {
    Text(stringResource(R.string.plants_add_identify_source_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.plants_add_identify_source_hint))
    if (unusable) {
        Text(
            text = stringResource(R.string.plants_add_identify_unusable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SourceButton(Icons.Filled.PhotoCamera, R.string.plants_note_take_photo, actions.onCamera)
        SourceButton(Icons.Filled.PhotoLibrary, R.string.plants_note_pick_photo, actions.onLibrary)
    }
    LeaveButton(actions)
}

@Composable
private fun SourceButton(icon: ImageVector, labelRes: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = stringResource(labelRes), modifier = Modifier.padding(start = 8.dp))
    }
}

/** The image as it will be sent — the normalised bytes, not the capture (R9). */
@Composable
private fun PreviewStep(step: IdentificationStep.Preview, actions: IdentificationActions) {
    RecognitionPreview(step.image)
    Button(onClick = { actions.onSend(PlantOrgan.AUTO) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.plants_add_identify_send))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = actions.onRetake) { Text(stringResource(R.string.plants_add_identify_retake)) }
        TextButton(onClick = actions.onLeave) { Text(stringResource(R.string.plants_add_identify_leave)) }
    }
}

@Composable
private fun WaitingStep(step: IdentificationStep.Identifying) {
    RecognitionPreview(step.image)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.plants_add_identifying))
    }
}

@Composable
private fun RecognitionPreview(image: IdentificationImage) {
    AsyncImage(
        model = image.recognitionJpeg,
        contentDescription = stringResource(R.string.plants_add_identify_preview_description),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(PREVIEW_HEIGHT)
            .clip(RoundedCornerShape(12.dp)),
    )
}

/**
 * What the recogniser said (R13, R15, R16).
 *
 * A ranked list leads with the list. Each of the three weak answers leads with its own
 * sentence, then the organ hint, then the way on by hand — and the low-confidence list still
 * follows, because a poor candidate is still a candidate the user may recognise.
 */
@Composable
private fun SuggestionsStep(step: IdentificationStep.Suggestions, actions: IdentificationActions) {
    val weak = step.weak
    if (weak == null) {
        Text(stringResource(R.string.plants_add_suggestions_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.plants_add_suggestions_body))
    } else {
        Text(stringResource(weak.titleRes()), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(weak.bodyRes()))
        step.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OrganChips(tried = step.organ, enabled = step.selecting == null, onChoose = actions.onSend)
        Button(
            onClick = actions.onContinueByHand,
            enabled = step.selecting == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.plants_add_by_hand_keep_photo))
        }
    }
    step.suggestions.forEach { suggestion ->
        SuggestionRow(
            suggestion = suggestion,
            busy = step.selecting == suggestion.rank,
            enabled = step.selecting == null,
            onChoose = { actions.onChoose(suggestion) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = actions.onRetake, enabled = step.selecting == null) {
            Text(stringResource(R.string.plants_add_identify_retake))
        }
        if (weak == null) {
            TextButton(onClick = actions.onContinueByHand, enabled = step.selecting == null) {
                Text(stringResource(R.string.plants_add_by_hand_keep_photo))
            }
        }
    }
}

/**
 * One candidate: common name where the recogniser knew one, scientific name always, and the
 * confidence as a number with a word beside it — never colour alone. `auto_accept` earns a
 * border and a label, nothing more (R14).
 */
@Composable
private fun SuggestionRow(suggestion: Suggestion, busy: Boolean, enabled: Boolean, onChoose: () -> Unit) {
    val border = if (suggestion.autoAccept) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Card(
        border = border,
        colors = CardDefaults.cardColors(),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onChoose),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val common = suggestion.commonNames.firstOrNull()
                Text(common ?: suggestion.scientificName, style = MaterialTheme.typography.titleSmall)
                if (common != null) {
                    Text(
                        text = suggestion.scientificName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.plants_add_suggestion_confidence,
                        (suggestion.confidence * PERCENT).roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (suggestion.autoAccept) {
                    Text(
                        text = stringResource(R.string.plants_add_suggestion_best),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
    }
}

/** The organ hint (R16): every organ but the one just tried, on the image already captured. */
@Composable
private fun OrganChips(tried: PlantOrgan, enabled: Boolean, onChoose: (PlantOrgan) -> Unit) {
    Text(stringResource(R.string.plants_add_organ_question), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        PlantOrgan.entries.filter { it != PlantOrgan.AUTO && it != tried }.forEach { organ ->
            FilterChip(
                selected = false,
                enabled = enabled,
                onClick = { onChoose(organ) },
                label = { Text(stringResource(organ.labelRes())) },
            )
        }
    }
}

/** An answer that was not an answer: the instance's words where it gave any, and the ways on. */
@Composable
private fun DeclinedStep(
    image: IdentificationImage,
    title: String,
    detail: String?,
    actions: IdentificationActions,
    retry: (() -> Unit)?,
) {
    RecognitionPreview(image)
    Text(title, style = MaterialTheme.typography.titleMedium)
    detail?.let { Text(it) }
    retry?.let {
        Button(onClick = it, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.plants_add_identify_retry))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = actions.onRetake) { Text(stringResource(R.string.plants_add_identify_retake)) }
        TextButton(onClick = actions.onContinueByHand) { Text(stringResource(R.string.plants_add_by_hand_keep_photo)) }
    }
}

@Composable
private fun LeaveButton(actions: IdentificationActions) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = actions.onLeave) {
            Text(stringResource(R.string.plants_add_identify_leave), textAlign = TextAlign.Center)
        }
    }
}

private fun WeakResult.titleRes(): Int = when (this) {
    WeakResult.NOT_A_PLANT -> R.string.plants_add_not_a_plant_title
    WeakResult.NOTHING_RECOGNISED -> R.string.plants_add_nothing_recognised_title
    WeakResult.LOW_CONFIDENCE -> R.string.plants_add_low_confidence_title
}

private fun WeakResult.bodyRes(): Int = when (this) {
    WeakResult.NOT_A_PLANT -> R.string.plants_add_not_a_plant_body
    WeakResult.NOTHING_RECOGNISED -> R.string.plants_add_nothing_recognised_body
    WeakResult.LOW_CONFIDENCE -> R.string.plants_add_low_confidence_body
}

private fun PlantOrgan.labelRes(): Int = when (this) {
    PlantOrgan.AUTO -> R.string.plants_add_organ_habit
    PlantOrgan.LEAF -> R.string.plants_add_organ_leaf
    PlantOrgan.FLOWER -> R.string.plants_add_organ_flower
    PlantOrgan.FRUIT -> R.string.plants_add_organ_fruit
    PlantOrgan.BARK -> R.string.plants_add_organ_bark
    PlantOrgan.HABIT -> R.string.plants_add_organ_habit
}

/** What the identification steps can ask for. */
internal data class IdentificationActions(
    val onStart: () -> Unit,
    val onLeave: () -> Unit,
    val onGrantConsent: () -> Unit,
    val onCamera: () -> Unit,
    val onLibrary: () -> Unit,
    val onRetake: () -> Unit,
    val onSend: (PlantOrgan) -> Unit,
    val onChoose: (Suggestion) -> Unit,
    val onContinueByHand: () -> Unit,
)

private val PREVIEW_HEIGHT = 240.dp
private const val PERCENT = 100
