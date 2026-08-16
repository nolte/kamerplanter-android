package io.github.nolte.kamerplanter.feature.plants

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Yard
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.nolte.kamerplanter.core.network.CareAction
import io.github.nolte.kamerplanter.core.network.PlantSummary
import java.time.LocalDate
import java.time.ZoneId

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
    onOpenPlant: (plantKey: String) -> Unit,
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
        actions = PlantListActions(
            onRetry = viewModel::retry,
            onOpenSettings = onOpenSettings,
            onOpenPlant = onOpenPlant,
        ),
        imageLoader = imageLoader,
        modifier = modifier,
    )
}

@Composable
internal fun PlantsContent(
    state: PlantListState,
    actions: PlantListActions,
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
                onAction = actions.onOpenSettings,
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
                    onAction = actions.onOpenSettings,
                )
            } else {
                CenteredMessage(
                    title = stringResource(R.string.plants_failed_title),
                    body = stringResource(R.string.plants_failed_body),
                    actionLabel = stringResource(R.string.plants_failed_retry),
                    onAction = actions.onRetry,
                )
            }
            is PlantListState.Content -> PlantList(state.plants, imageLoader, actions.onOpenPlant)
        }
    }
}

@Composable
private fun PlantList(
    plants: List<PlantSummary>,
    imageLoader: ImageLoader?,
    onOpenPlant: (plantKey: String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = plants, key = { it.key }) { plant ->
            PlantRow(plant, imageLoader, onClick = { onOpenPlant(plant.key) })
            // Inset to where the text starts (thumbnail plus its gap), so the divider reads as
            // a break between entries rather than a rule drawn across the page.
            HorizontalDivider(modifier = Modifier.padding(start = THUMBNAIL_SIZE + 32.dp))
        }
    }
}

/**
 * One plant.
 *
 * The care badge sits under the text rather than beside it. Sharing the row cost the title
 * roughly half its width, and with names like `AGLAO-0617-RB5` — the identifier the instance
 * generates when a plant is not given one — every row ended in an ellipsis, so the list showed
 * nine plants no two of which could be told apart. Below the text the badge has room for what
 * it is actually for: which task, and whether it is late.
 *
 * The whole row is one node for a screen reader. As four siblings it was read as four
 * unrelated fragments, and the badge — the only part that says anything is wrong — arrived
 * last and unattached.
 */
@Composable
private fun PlantRow(plant: PlantSummary, imageLoader: ImageLoader?, onClick: () -> Unit) {
    val openLabel = stringResource(R.string.plants_open_description, plant.displayName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Clickable before the padding, so the ripple covers the whole row rather than a
            // rectangle inset from the edges the user is aiming at.
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        PlantThumbnail(plant, imageLoader)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = plant.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Species and location on one line, separated by a middle dot: they are both
            // "which plant is this", they are each short, and two lines of grey under the
            // title pushed the badge off the visible part of a dense row. Either may be
            // absent, and a row with neither must not leave a blank line behind.
            listOfNotNull(plant.species, plant.location)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(SEPARATOR)
                ?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            plant.careAction?.let {
                CareBadge(action = it, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

internal const val SEPARATOR = " · "

private val THUMBNAIL_SIZE = 56.dp

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
                .size(THUMBNAIL_SIZE)
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
                .size(THUMBNAIL_SIZE)
                .clip(shape),
        )
    }
}

/**
 * What this plant needs, and how late it is.
 *
 * Both, because either alone misleads. The badge used to carry only the task, painted in the
 * error colour whatever its urgency — so a tenant whose plants all had something scheduled saw
 * an unbroken column of red, in which the two genuinely overdue plants were invisible. Half of
 * these reminders are simply upcoming.
 *
 * A flat label rather than a chip: the previous version was an [androidx.compose.material3.AssistChip]
 * with `enabled = false` and an empty `onClick`, which reads as a button, is styled as a
 * button, and does nothing when pressed. This states a fact and looks like one.
 */
@Composable
internal fun CareBadge(action: CareAction, modifier: Modifier = Modifier) {
    val task = stringResource(action.labelRes())
    val due = action.dueLabel()
    val container = if (action.isOverdue) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (action.isOverdue) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    // Never colour alone (WCAG 1.4.1): overdue also carries a different icon, and the due text
    // says "overdue" in words. Someone who cannot tell the two containers apart still can.
    val icon = if (action.isOverdue) Icons.Filled.PriorityHigh else Icons.Filled.Schedule
    val spoken = if (due == null) task else "$task$SEPARATOR$due"

    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
        // One node carrying what the icon, the colour and both texts convey together. A
        // screen reader that read them separately would announce a task, then a date, and
        // never the fact that the date has passed.
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(text = spoken, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * "Overdue since 11 Aug" or "Due 18 Aug", or nothing where the backend gave no date.
 *
 * The date was in the model from the start and never shown. It is the part that makes the
 * badge worth reading: "water" is true of a plant watered yesterday and of one forgotten for a
 * week, and only the date tells the owner which they are looking at.
 */
@Composable
private fun CareAction.dueLabel(): String? {
    val date = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val template = if (isOverdue) R.string.plants_care_overdue_since else R.string.plants_care_due_on
    return stringResource(template, date.asShortDate(LocalContext.current))
}

/**
 * The date, abbreviated, and without the year when it is this one.
 *
 * Left in full, "Überfällig seit 10.08.2026" wrapped the badge onto a second line for most
 * plants — four characters of year, on every row, saying what the reader already knows.
 * [DateUtils] rather than a hand-built pattern because dropping a year from a date format is
 * locale-specific work: the platform already knows where the year sits in each one.
 */
private fun LocalDate.asShortDate(context: Context): String {
    val millis = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    if (year == LocalDate.now().year) flags = flags or DateUtils.FORMAT_NO_YEAR
    return DateUtils.formatDateTime(context, millis, flags)
}

/**
 * The task in the user's language.
 *
 * All thirteen `reminder_type` values the backend defines, not the four that happened to be
 * needed first. The other nine fell to the catch-all — and the catch-all reads "needs
 * attention", which is both vaguer and more alarming than the named tasks it stood in for: a
 * humidity check announced itself as more serious than an overdue watering. The catch-all
 * stays for a type a later release adds (R-COMPAT-3), which is what it is for.
 */
private fun CareAction.labelRes(): Int = CARE_LABELS[kind] ?: R.string.plants_care_other

/** Every `reminder_type` the backend defines, in the order its own enum lists them. */
private val CARE_LABELS = mapOf(
    "watering" to R.string.plants_care_watering,
    "fertilizing" to R.string.plants_care_fertilizing,
    "repotting" to R.string.plants_care_repotting,
    "pest_check" to R.string.plants_care_pest_check,
    "location_check" to R.string.plants_care_location_check,
    "humidity_check" to R.string.plants_care_humidity_check,
    "deadheading" to R.string.plants_care_deadheading,
    "tuber_dig" to R.string.plants_care_tuber_dig,
    "storage_check" to R.string.plants_care_storage_check,
    "spring_uncover" to R.string.plants_care_spring_uncover,
    "winter_protection" to R.string.plants_care_winter_protection,
    "dormancy_health_check" to R.string.plants_care_dormancy_health_check,
    "quarter_climate_check" to R.string.plants_care_quarter_climate_check,
)

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

/**
 * The list's callbacks, bundled.
 *
 * Same reason `ConnectionActions` exists in `:feature:settings`: a content composable that
 * takes every one of them as its own parameter grows a signature nobody reads, and the
 * threshold that catches it is there to force exactly this grouping.
 */
data class PlantListActions(
    val onRetry: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenPlant: (plantKey: String) -> Unit,
)
