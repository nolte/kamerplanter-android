package io.github.nolte.kamerplanter.feature.plants

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Yard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.nolte.kamerplanter.core.camera.CameraPermission
import io.github.nolte.kamerplanter.core.camera.MAX_PHOTOS
import io.github.nolte.kamerplanter.core.camera.rememberCameraPermission
import io.github.nolte.kamerplanter.core.camera.rememberPhotoPicking
import io.github.nolte.kamerplanter.core.network.CareAction
import io.github.nolte.kamerplanter.core.network.Detection
import io.github.nolte.kamerplanter.core.network.DiaryEntry
import io.github.nolte.kamerplanter.core.network.EnvironmentReading
import io.github.nolte.kamerplanter.core.network.PlantDetail
import io.github.nolte.kamerplanter.core.network.PlantPhase
import io.github.nolte.kamerplanter.core.network.PlantPhoto
import io.github.nolte.kamerplanter.core.network.PlantRemoval

/**
 * One plant, and what can be done to it.
 *
 * The actions come first, above the diary and below only the plant's own identity: this page
 * is opened from a list where the reason to tap a row is almost always "I just did something
 * to this plant" or "this one needs something". Reading about it is the secondary use, so the
 * things that write sit where the thumb is, not behind a scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantDetailScreen(
    onBack: () -> Unit,
    onDetectPests: (plantKey: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlantDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Remembered for the same reason the list does it: rebuilding the loader drops its cache,
    // and this page shows a photo the list has usually just fetched.
    val imageLoader = remember(viewModel.imageClient) {
        plantImageLoader(context, viewModel.imageClient)
    }
    val snackbars = remember { SnackbarHostState() }
    var noteOpen by rememberSaveable { mutableStateOf(false) }

    ReportOutcome(state, snackbars, viewModel::clearMessages)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(state.plant?.displayName.orEmpty(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.plants_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            PlantDetailPage(
                state = state,
                imageLoader = imageLoader,
                viewModel = viewModel,
                onDetectPests = onDetectPests,
                onAddNote = { noteOpen = true },
            )
        }
    }

    if (noteOpen) {
        NoteComposer(viewModel = viewModel, state = state, onClose = { noteOpen = false })
    }
}

/**
 * The diary composer, and the one rule about when it closes.
 *
 * Closed by the write landing, not by the press that started it. Closing on the press
 * discarded the draft — the typed sentence and every photo, including a microscope frame taken
 * with the sample still under the objective — while the request was still in flight. A 422 or a
 * lost connection then reported a careful reason for work that no longer existed. The dialogue
 * stays, the reason arrives, the draft is still there.
 */
@Composable
private fun NoteComposer(
    viewModel: PlantDetailViewModel,
    state: PlantDetailUiState,
    onClose: () -> Unit,
) {
    LaunchedEffect(state.actionDone) {
        if (state.actionDone == PlantAction.NOTE_ADDED) onClose()
    }
    NoteDialog(
        microscope = viewModel.microscope,
        isSaving = state.isWorking,
        onDismiss = onClose,
        onSave = viewModel::addNote,
    )
}

/**
 * The page, or the one sentence that stands in for all of it.
 *
 * Two answers are true of every section at once and end the page rather than a section: a
 * credential nothing will load with, and a plant that is not there to load. The header's own
 * failure joins them, because everything below hangs off the plant.
 */
@Composable
private fun BoxScope.PlantDetailPage(
    state: PlantDetailUiState,
    imageLoader: ImageLoader?,
    viewModel: PlantDetailViewModel,
    onDetectPests: (plantKey: String) -> Unit,
    onAddNote: () -> Unit,
) {
    val plant = state.plant
    val header = state.header
    when {
        state.credentialRefused -> PageFailure(
            body = stringResource(R.string.plants_rejected_body),
            onRetry = viewModel::load,
        )

        state.isGone -> PageFailure(
            body = stringResource(R.string.plants_detail_gone),
            onRetry = null,
        )

        header is SectionState.Failed -> PageFailure(body = header.reason, onRetry = viewModel::load)

        plant != null -> PlantDetailBody(
            plant = plant,
            state = state,
            imageLoader = imageLoader,
            actions = PlantDetailActions(
                onWater = viewModel::water,
                onConfirmCare = viewModel::confirmCare,
                onAddNote = onAddNote,
                onDetectPests = { onDetectPests(plant.key) },
                onRetrySection = viewModel::reload,
            ),
        )

        else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
    }
}

/**
 * Announces an action's outcome and then forgets it.
 *
 * A snackbar rather than a line on the page: the outcome is a moment, not a state, and text
 * appearing between two buttons moves everything under it just as the user is reaching for
 * one of them.
 */
@Composable
private fun ReportOutcome(
    state: PlantDetailUiState,
    snackbars: SnackbarHostState,
    onShown: () -> Unit,
) {
    val failure = state.actionError
    val doneMessage = state.actionDone?.let { stringResource(it.messageRes()) }
    val dismiss = stringResource(R.string.plants_dismiss)
    LaunchedEffect(failure, doneMessage) {
        val message = failure ?: doneMessage ?: return@LaunchedEffect
        snackbars.showSnackbar(
            message = message,
            // A failure waits to be dismissed; a success does not. The instance's reason for
            // refusing something is often a sentence naming a field, and a message that
            // clears itself after four seconds is one the reader has to catch rather than
            // read — which is exactly what happened.
            actionLabel = failure?.let { dismiss },
            withDismissAction = failure != null,
            duration = if (failure != null) SnackbarDuration.Indefinite else SnackbarDuration.Short,
        )
        onShown()
    }
}

/**
 * The page could not be shown at all.
 *
 * Only for the two answers that are true of every section: a refused credential and a plant
 * that is gone. Everything else states its problem inside its own section, beside the five
 * that loaded.
 *
 * [onRetry] is `null` where trying again cannot change the answer — a plant the instance no
 * longer has will be missing next time too, and a button that promises otherwise is worse
 * than no button.
 */
@Composable
private fun PageFailure(body: String, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.plants_detail_failed),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        onRetry?.let {
            Button(onClick = it, modifier = Modifier.padding(top = 24.dp)) {
                Text(stringResource(R.string.plants_failed_retry))
            }
        }
    }
}

/**
 * The page, section by section.
 *
 * The order is what a reader asks in: what is this, what does it need from me, what can I do
 * about it, and only then the record — master data, phase, photos, diary, past pest checks.
 * The actions sit above every section that can be slow to load, so the primary one is
 * reachable without scrolling however long the instance takes to answer (#11).
 */
@Composable
private fun PlantDetailBody(
    plant: PlantDetail,
    state: PlantDetailUiState,
    imageLoader: ImageLoader?,
    actions: PlantDetailActions,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { PlantHeader(plant, state.cover, imageLoader) }
        state.openTask?.let { action ->
            item {
                OpenTask(
                    action = action,
                    isWorking = state.isWorking,
                    onConfirm = { actions.onConfirmCare(action.kind) },
                )
            }
        }
        item {
            QuickActions(
                isWorking = state.isWorking,
                detectionAvailable = state.detectionAvailable,
                actions = actions,
            )
        }
        recordSections(plant = plant, state = state, imageLoader = imageLoader, actions = actions)

        // The diary keeps its own rows rather than going through Section: it is the one part
        // of this page that can be long, and a section that rendered it as one item would put
        // a hundred entries outside the list's own recycling.
        item {
            SectionHeading(
                title = stringResource(R.string.plants_diary),
                state = state.diary,
                onRetry = { actions.onRetrySection(PlantSection.DIARY) },
                emptyMessage = stringResource(R.string.plants_diary_empty),
            )
        }
        items(state.diary.valueOrNull.orEmpty(), key = { it.key }) { entry ->
            DiaryRow(entry, imageLoader)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

/**
 * What the instance has on record for this plant, section by section.
 *
 * Split from the page's own layout so each stays readable: the page decides the order, this
 * decides what each section shows and how it says it has nothing.
 */
private fun LazyListScope.recordSections(
    plant: PlantDetail,
    state: PlantDetailUiState,
    imageLoader: ImageLoader?,
    actions: PlantDetailActions,
) {
    item {
        Section(
            title = stringResource(R.string.plants_section_facts),
            state = state.header,
            onRetry = { actions.onRetrySection(PlantSection.HEADER) },
        ) { MasterData(it) }
    }
    item {
        Section(
            title = stringResource(R.string.plants_section_phase),
            state = state.phases,
            onRetry = { actions.onRetrySection(PlantSection.PHASES) },
            emptyMessage = stringResource(R.string.plants_section_phase_empty)
                .takeIf { plant.phase == null },
        ) { history -> Phases(current = plant.phase, history = history) }
    }
    item {
        Section(
            title = stringResource(R.string.plants_section_care),
            state = state.care,
            onRetry = { actions.onRetrySection(PlantSection.CARE) },
            emptyMessage = stringResource(R.string.plants_section_care_empty),
        ) { OpenTasks(it) }
    }
    item {
        Section(
            title = stringResource(R.string.plants_section_photos),
            state = state.photos,
            onRetry = { actions.onRetrySection(PlantSection.PHOTOS) },
            emptyMessage = stringResource(R.string.plants_section_photos_empty),
        ) { Gallery(it, imageLoader) }
    }
    item {
        Section(
            title = stringResource(R.string.plants_section_pest_checks),
            state = state.pestChecks,
            onRetry = { actions.onRetrySection(PlantSection.PEST_CHECKS) },
            emptyMessage = stringResource(R.string.plants_section_pest_checks_empty),
        ) { PastChecks(it) }
    }
}

/**
 * One section: its heading, and whatever it has to show.
 *
 * Loading, empty and failed are each said in place, under the heading the section keeps
 * either way — a section that vanished while it loaded would move everything under it just as
 * the reader started on it, and one that vanished when it failed would leave them wondering
 * whether the plant has no photos or whether nobody asked.
 */
@Composable
private fun <T> Section(
    title: String,
    state: SectionState<T>,
    onRetry: () -> Unit,
    emptyMessage: String? = null,
    content: @Composable (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeading(title = title, state = state, onRetry = onRetry, emptyMessage = emptyMessage)
        (state as? SectionState.Loaded)?.value?.let { value ->
            if (!value.isEmptyContent()) content(value)
        }
    }
}

/** The heading, plus the one line that stands in for content the section does not have. */
@Composable
private fun <T> SectionHeading(
    title: String,
    state: SectionState<T>,
    onRetry: () -> Unit,
    emptyMessage: String?,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        when (state) {
            SectionState.Loading -> Text(
                text = stringResource(R.string.plants_section_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            is SectionState.Failed -> {
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.plants_failed_retry))
                }
            }
            is SectionState.Loaded -> if (state.value.isEmptyContent() && emptyMessage != null) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Whether a section's payload has nothing to draw — an empty list, or nothing at all. */
private fun Any?.isEmptyContent(): Boolean = this == null || (this is Collection<*> && isEmpty())

@Composable
private fun PlantHeader(plant: PlantDetail, cover: PlantPhoto?, imageLoader: ImageLoader?) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        val shape = RoundedCornerShape(12.dp)
        if (cover == null || imageLoader == null) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(HEADER_IMAGE_HEIGHT).clip(shape),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Icon(
                    imageVector = Icons.Filled.Yard,
                    contentDescription = stringResource(
                        R.string.plants_photo_missing_description,
                        plant.displayName,
                    ),
                    modifier = Modifier.padding(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AsyncImage(
                model = cover.url,
                imageLoader = imageLoader,
                contentDescription = stringResource(
                    R.string.plants_photo_description,
                    plant.displayName,
                ),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(HEADER_IMAGE_HEIGHT).clip(shape),
            )
        }
        listOfNotNull(plant.species, plant.location)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(SEPARATOR)
            ?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        plant.removal?.let { RemovedNotice(it) }
    }
}

/**
 * That this plant is no longer in the garden, and how it left.
 *
 * Stated at the top rather than as a field further down: everything below — an open task, a
 * phase, a photo from last spring — reads differently once you know the plant is gone, and a
 * reader who learns it after scrolling has already misread the page.
 */
@Composable
private fun RemovedNotice(removal: PlantRemoval) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.plants_removed_on, removal.removedOn),
                style = MaterialTheme.typography.labelLarge,
            )
            // Type and cause as the instance names them. A translation table here would go
            // stale the moment the instance learns a new one, and the raw word is at least
            // the grower's own.
            listOfNotNull(removal.type, removal.cause)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(SEPARATOR)
                ?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}

/** Planted on, what it sits in, how it is grown, and where it came from. */
@Composable
private fun MasterData(plant: PlantDetail) {
    val facts = listOfNotNull(
        plant.plantedOn?.let { stringResource(R.string.plants_fact_planted, it) },
        plant.containerVolumeLiters?.let {
            stringResource(R.string.plants_fact_container, it.formatLitres())
        },
        plant.substrate?.let { stringResource(R.string.plants_fact_substrate, it) },
        plant.cultivationCycle?.let { stringResource(R.string.plants_fact_cycle, it) },
        // A lineage hint, not a link: resolving the mother plant's name would be a second
        // lookup for one line, and the key is what the grower recorded.
        plant.motherKey?.let { stringResource(R.string.plants_fact_mother, it) },
    )
    if (facts.isEmpty()) {
        SectionNote(stringResource(R.string.plants_section_facts_empty))
        return
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        facts.forEach { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** The phase it is in, and the ones it has been through. */
@Composable
private fun Phases(current: PlantPhase?, history: List<PlantPhase>) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        current?.let {
            Text(
                text = it.startedAt?.let { since ->
                    stringResource(R.string.plants_phase_current_since, it.name, since)
                } ?: it.name,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        history.forEach { phase ->
            Text(
                text = listOfNotNull(phase.name, phase.startedAt, phase.endedAt)
                    .joinToString(SEPARATOR),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Every open task, not only the one the header offers to clear. */
@Composable
private fun OpenTasks(tasks: List<CareAction>) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tasks.forEach { CareBadge(action = it) }
    }
}

/** The plant's photos, sideways, cover first. */
@Composable
private fun Gallery(photos: List<PlantPhoto>, imageLoader: ImageLoader?) {
    if (imageLoader == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        photos.forEach { photo ->
            AsyncImage(
                model = photo.url,
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(GALLERY_PHOTO_SIZE)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}

/** What this plant has been checked for, and what came of it. */
@Composable
private fun PastChecks(checks: List<Detection>) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        checks.forEach { check ->
            Column {
                check.recordedAt?.let {
                    Text(text = it, style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    text = if (check.isConfident) {
                        check.findings.joinToString(SEPARATOR) { it.commonName }
                            .ifBlank { stringResource(R.string.plants_check_nothing_found) }
                    } else {
                        stringResource(R.string.plants_check_abstained)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** A line that stands in for content a section does not have. */
@Composable
private fun SectionNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** Litres without a trailing `.0`, which is how a pot size is written. */
private fun Double.formatLitres(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()

private val GALLERY_PHOTO_SIZE = 96.dp

/**
 * The open care task, with the one button that clears it.
 *
 * The same badge the list row shows, plus the action — so a user who tapped the row *because*
 * of that badge finds the thing that answers it in the place they were already looking.
 */
@Composable
private fun OpenTask(
    action: CareAction,
    isWorking: Boolean,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CareBadge(action = action, modifier = Modifier.weight(1f, fill = false))
        Button(onClick = onConfirm, enabled = !isWorking) {
            Text(stringResource(R.string.plants_action_done))
        }
    }
}

@Composable
private fun QuickActions(
    isWorking: Boolean,
    detectionAvailable: Boolean,
    actions: PlantDetailActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickAction(
            icon = Icons.Filled.WaterDrop,
            labelRes = R.string.plants_action_water,
            enabled = !isWorking,
            onClick = actions.onWater,
            modifier = Modifier.weight(1f),
        )
        QuickAction(
            icon = Icons.Filled.EditNote,
            labelRes = R.string.plants_action_note,
            enabled = !isWorking,
            onClick = actions.onAddNote,
            modifier = Modifier.weight(1f),
        )
        // Hidden rather than disabled where the instance does not offer detection: a greyed
        // button invites a press and explains nothing, and the reason lives on the instance
        // where no press can change it.
        if (detectionAvailable) {
            QuickAction(
                icon = Icons.Filled.BugReport,
                labelRes = R.string.plants_action_pests,
                // Not disabled while an action runs: it navigates rather than writing, and a
                // camera the user is reaching for should not be locked by a diary save.
                enabled = true,
                onClick = actions.onDetectPests,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun DiaryRow(entry: DiaryEntry, imageLoader: ImageLoader?) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        entry.title?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, style = MaterialTheme.typography.titleSmall)
        }
        if (entry.text.isNotBlank()) {
            Text(text = entry.text, style = MaterialTheme.typography.bodyMedium)
        }
        if (entry.photoUrls.isNotEmpty() && imageLoader != null) {
            EntryPhotos(entry.photoUrls, imageLoader)
        }
        if (entry.environment.isNotEmpty()) {
            EnvironmentReadings(entry.environment)
        }
        entry.createdAt?.let {
            Text(
                text = it.take(ISO_DATE_LENGTH),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The entry's photos, fetched with the connection's credential like every other image. */
@Composable
private fun EntryPhotos(urls: List<String>, imageLoader: ImageLoader) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        urls.forEach { url ->
            AsyncImage(
                model = url,
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}

/**
 * What the instance's sensors read when the entry was written.
 *
 * Shown as plain values with their units rather than as a chart: one entry is one moment, and
 * a single point plotted over time is a line with nothing to say.
 */
@Composable
private fun EnvironmentReadings(readings: List<EnvironmentReading>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        readings.forEach { reading ->
            Text(
                text = reading.display(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "Temperatur 21.4 °C".
 *
 * The metric keeps the backend's own name where this build has no word for it: a reading
 * labelled `soil_moisture` is still a reading, and hiding it because the vocabulary moved on
 * would lose data the instance took the trouble to record (R-COMPAT-3).
 */
@Composable
private fun EnvironmentReading.display(): String {
    val label = METRIC_LABELS[metric]?.let { stringResource(it) } ?: metric
    // The configuration's locale, not `Locale.getDefault()`: read inside a composable the
    // latter is invisible to Compose, so a decimal comma stays a decimal point until something
    // else happens to recompose. Android Lint calls this `NonObservableLocale`, and it is the
    // check that caught it — a rule my local gate does not run and CI does.
    val locale = LocalConfiguration.current.locales[0]
    val number = if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(locale, "%.1f", value)
    }
    return listOfNotNull(label, number, unit).joinToString(" ")
}

private val METRIC_LABELS = mapOf(
    "temperature" to R.string.plants_metric_temperature,
    "humidity" to R.string.plants_metric_humidity,
    "soil_moisture" to R.string.plants_metric_soil_moisture,
    "light" to R.string.plants_metric_light,
)

/**
 * Writes a diary entry: a sentence, up to five photos, and whether the instance should attach
 * what its sensors read.
 *
 * A dialogue rather than its own screen: pushing a destination for one text field costs the
 * user the sight of the plant they are writing about. The photo strip and the sensor switch
 * fit beside it because neither needs room — one is a row of thumbnails, the other a toggle.
 */
@Composable
private fun NoteDialog(
    microscope: MicroscopeAccess,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (text: String, photos: List<ByteArray>, captureEnvironment: Boolean) -> Unit,
) {
    val draft = rememberSaveable(saver = NoteDraft.Saver) { NoteDraft() }
    val permission = rememberCameraPermission(requestOnFirstShow = false)
    val withCamera = rememberCameraGate(permission)
    val picking = rememberPhotoPicking { picked ->
        draft.photos = (draft.photos + picked).take(MAX_PHOTOS)
    }
    if (draft.microscopeOpen) {
        MicroscopeSession(start = microscope.start, stop = microscope.stop)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(draft.titleRes())) },
        text = {
            if (draft.microscopeOpen) {
                MicroscopeCapture(
                    microscope = microscope,
                    onCaptured = {
                        draft.photos = (draft.photos + it).take(MAX_PHOTOS)
                        draft.microscopeOpen = false
                    },
                    onCancel = { draft.microscopeOpen = false },
                )
            } else {
                NoteForm(
                    draft = draft,
                    // The camera grant covers two of the three sources: the phone camera
                    // obviously, and the microscope because AOSP refuses to show the USB
                    // dialogue for a video-class device to an app without it. Only the library
                    // picker needs nothing — the system picker grants per item as it goes.
                    sources = PhotoSourceActions(
                        onCamera = { withCamera(picking.takePhoto) },
                        onLibrary = picking.pickFromLibrary,
                        onMicroscope = { withCamera { draft.microscopeOpen = true } },
                    ),
                )
            }
        },
        confirmButton = {
            // Text, not photos, is what the endpoint requires (`minLength: 1`). A picture with
            // no words is a fine idea and a 422, so the button says so up front rather than
            // letting the instance refuse the entry after the photos have uploaded.
            TextButton(
                onClick = { onSave(draft.text, draft.photos, draft.captureEnvironment) },
                enabled = draft.text.isNotBlank() && !draft.microscopeOpen && !isSaving,
            ) {
                Text(
                    stringResource(
                        if (isSaving) R.string.plants_note_saving else R.string.plants_note_save,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.plants_note_cancel)) }
        },
    )
}

/**
 * Runs an action with the camera grant, asking for it first and then carrying on.
 *
 * The carrying on is the point. Asking and stopping there left the user watching a dialogue
 * they had just answered "allow" to, with nothing happening — the source they picked was
 * forgotten the moment the request went out, and only a second tap did what the first one
 * asked for.
 */
@Composable
private fun rememberCameraGate(permission: CameraPermission): (() -> Unit) -> Unit {
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(permission.isGranted) {
        if (permission.isGranted) {
            pending?.invoke()
            pending = null
        }
    }
    return { action ->
        if (permission.isGranted) {
            action()
        } else {
            pending = action
            permission.request()
        }
    }
}

/**
 * A diary entry being written.
 *
 * A holder rather than four `rememberSaveable`s threaded through signatures: the form and the
 * dialogue both read all of it, and passing each piece with its own setter is what pushed both
 * past the parameter limit.
 *
 * Photos are held but never saved. Several megabytes of JPEG through the saved-instance Bundle
 * fails — and fails at exactly the moment the state was worth keeping. Losing unsent photos to
 * a process death is the smaller loss; losing the sentence already typed is not, so that is
 * saved.
 */
@Stable
private class NoteDraft(text: String = "", captureEnvironment: Boolean = true) {

    var text by mutableStateOf(text)
    var photos by mutableStateOf<List<ByteArray>>(emptyList())
    var captureEnvironment by mutableStateOf(captureEnvironment)
    var microscopeOpen by mutableStateOf(false)

    fun titleRes(): Int =
        if (microscopeOpen) R.string.plants_note_microscope else R.string.plants_note_title

    companion object {
        val Saver: Saver<NoteDraft, Any> = listSaver(
            save = { listOf(it.text, it.captureEnvironment) },
            restore = { NoteDraft(it[0] as String, it[1] as Boolean) },
        )
    }
}

/** The entry form: what to write, what to attach, and whether to record the surroundings. */
@Composable
private fun NoteForm(draft: NoteDraft, sources: PhotoSourceActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = draft.text,
            onValueChange = { draft.text = it },
            label = { Text(stringResource(R.string.plants_note_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        PickedPhotos(
            photos = draft.photos,
            onRemove = { index -> draft.photos = draft.photos.filterIndexed { at, _ -> at != index } },
        )
        PhotoSources(enabled = draft.photos.size < MAX_PHOTOS, actions = sources)
        EnvironmentSwitch(
            checked = draft.captureEnvironment,
            onChange = { draft.captureEnvironment = it },
        )
    }
}

/** Where a photo can come from; bundled so the form's signature stays readable. */
internal data class PhotoSourceActions(
    val onCamera: () -> Unit,
    val onLibrary: () -> Unit,
    val onMicroscope: () -> Unit,
)

/** Where a photo can come from. */
@Composable
private fun PhotoSources(enabled: Boolean, actions: PhotoSourceActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhotoSource(Icons.Filled.PhotoCamera, R.string.plants_note_take_photo, enabled, actions.onCamera)
        PhotoSource(Icons.Filled.PhotoLibrary, R.string.plants_note_pick_photo, enabled, actions.onLibrary)
        // Offered whether or not a microscope is attached: the surface behind it says so
        // plainly, which is more use than a button that is simply missing.
        PhotoSource(Icons.Filled.Biotech, R.string.plants_note_microscope, enabled, actions.onMicroscope)
    }
}

@Composable
private fun PhotoSource(icon: ImageVector, labelRes: Int, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = stringResource(labelRes), modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * Whether the instance should attach what its sensors read.
 *
 * On by default, because that is the backend's own default and because the readings are the
 * part of an entry nobody can reconstruct later. Off is offered because the backend has a
 * state for it: an entry written away from the plant would otherwise record the room the
 * writer is standing in as if it were the plant's.
 */
@Composable
private fun EnvironmentSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(
            text = stringResource(R.string.plants_note_capture_environment),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** The photos picked so far, each removable before anything is sent. */
@Composable
private fun PickedPhotos(photos: List<ByteArray>, onRemove: (Int) -> Unit) {
    if (photos.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.forEachIndexed { index, bytes ->
            Box {
                AsyncImage(
                    model = bytes,
                    contentDescription = stringResource(R.string.plants_note_photo, index + 1),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                )
                IconButton(
                    onClick = { onRemove(index) },
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(
                            R.string.plants_note_remove_photo,
                            index + 1,
                        ),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

private fun PlantAction.messageRes(): Int = when (this) {
    PlantAction.WATERED -> R.string.plants_action_watered_done
    PlantAction.CARE_CONFIRMED -> R.string.plants_action_care_done
    PlantAction.NOTE_ADDED -> R.string.plants_action_note_done
}

private val HEADER_IMAGE_HEIGHT = 200.dp

/** `2026-08-16T09:31:00Z` — the date is what a diary row needs; the clock time is noise. */
private const val ISO_DATE_LENGTH = 10

/** The page's callbacks, bundled — see [PlantListActions] for why. */
data class PlantDetailActions(
    val onWater: () -> Unit,
    val onConfirmCare: (kind: String) -> Unit,
    val onAddNote: () -> Unit,
    val onDetectPests: () -> Unit,
    /** Loads one section again, from the button that section shows when it failed. */
    val onRetrySection: (PlantSection) -> Unit,
)
