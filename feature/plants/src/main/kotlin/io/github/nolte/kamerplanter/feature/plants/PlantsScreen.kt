package io.github.nolte.kamerplanter.feature.plants

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Yard
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.nolte.kamerplanter.core.network.CareAction
import io.github.nolte.kamerplanter.core.network.PlantSummary

/**
 * The Plants tab: the connected tenant's plant instances, one row each.
 *
 * Filtering and search are deliberately absent for now — this is the list only. What is here
 * instead is every state the list can be in, because a screen that shows an empty box when
 * the app is disconnected is indistinguishable from one that is merely slow.
 */
@Composable
fun PlantsScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlantListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Remembered so scrolling does not rebuild the loader and drop its memory cache.
    val imageLoader = remember(viewModel.imageClient) {
        plantImageLoader(context, viewModel.imageClient)
    }
    PlantsContent(
        state = state,
        onRetry = viewModel::retry,
        onOpenSettings = onOpenSettings,
        imageLoader = imageLoader,
        modifier = modifier,
    )
}

@Composable
internal fun PlantsContent(
    state: PlantListState,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            PlantListState.Loading -> CenteredMessage(
                title = stringResource(R.string.plants_loading),
                showProgress = true,
            )
            PlantListState.NotConnected -> CenteredMessage(
                title = stringResource(R.string.plants_not_connected_title),
                body = stringResource(R.string.plants_not_connected_body),
                actionLabel = stringResource(R.string.plants_not_connected_action),
                onAction = onOpenSettings,
            )
            PlantListState.Empty -> CenteredMessage(
                title = stringResource(R.string.plants_empty_title),
                body = stringResource(R.string.plants_empty_body),
            )
            // A refused credential cannot be retried into working — the way out is Settings.
            is PlantListState.Failed -> if (state.credentialRejected) {
                CenteredMessage(
                    title = stringResource(R.string.plants_rejected_title),
                    body = stringResource(R.string.plants_rejected_body),
                    actionLabel = stringResource(R.string.plants_not_connected_action),
                    onAction = onOpenSettings,
                )
            } else {
                CenteredMessage(
                    title = stringResource(R.string.plants_failed_title),
                    body = stringResource(R.string.plants_failed_body),
                    actionLabel = stringResource(R.string.plants_failed_retry),
                    onAction = onRetry,
                )
            }
            is PlantListState.Content -> PlantList(state.plants, imageLoader)
        }
    }
}

@Composable
private fun PlantList(plants: List<PlantSummary>, imageLoader: ImageLoader?) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = plants, key = { it.key }) { plant ->
            PlantRow(plant, imageLoader)
            HorizontalDivider()
        }
    }
}

@Composable
private fun PlantRow(plant: PlantSummary, imageLoader: ImageLoader?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlantThumbnail(plant, imageLoader)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Text(
                text = plant.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Species and location are each optional, and a row for a plant with neither
            // should not leave two blank lines behind.
            plant.species?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            plant.location?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        plant.careAction?.let { CareBadge(it) }
    }
}

@Composable
private fun PlantThumbnail(plant: PlantSummary, imageLoader: ImageLoader?) {
    val shape = RoundedCornerShape(8.dp)
    val description = if (plant.thumbnailUrl != null) {
        stringResource(R.string.plants_photo_description, plant.displayName)
    } else {
        stringResource(R.string.plants_photo_missing_description, plant.displayName)
    }

    if (plant.thumbnailUrl == null || imageLoader == null) {
        Surface(
            modifier = Modifier
                .size(56.dp)
                .clip(shape),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Icon(
                imageVector = Icons.Filled.Yard,
                contentDescription = description,
                modifier = Modifier.padding(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        AsyncImage(
            model = plant.thumbnailUrl,
            imageLoader = imageLoader,
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(shape),
        )
    }
}

/**
 * The "needs attention" flag.
 *
 * Carries an icon and a content description as well as its colour: a badge distinguished by
 * colour alone disappears for a colour-blind reader and is silent to a screen reader.
 */
@Composable
private fun CareBadge(action: CareAction) {
    val label = stringResource(action.labelRes())
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.PriorityHigh,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
            disabledLabelColor = MaterialTheme.colorScheme.onErrorContainer,
            disabledLeadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        // The chip is a status indicator, not a control: one description for the whole thing
        // reads as "Needs attention: Water" rather than as a disabled button.
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "$label"
        },
    )
}

/**
 * Maps the backend's `reminder_type` to a label.
 *
 * The fallback is not defensive padding: this build knows the kinds the schema had when it
 * was generated, and a server one release ahead will name others. Rendering "Needs
 * attention" for an unknown kind keeps the row useful (R-COMPAT-3).
 */
private fun CareAction.labelRes(): Int = when (kind) {
    "watering" -> R.string.plants_care_watering
    "fertilizing" -> R.string.plants_care_fertilizing
    "repotting" -> R.string.plants_care_repotting
    "pest_check" -> R.string.plants_care_pest_check
    else -> R.string.plants_care_other
}

@Composable
private fun CenteredMessage(
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    showProgress: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (showProgress) {
            CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        body?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 24.dp)) {
                Text(text = actionLabel)
            }
        }
    }
}
