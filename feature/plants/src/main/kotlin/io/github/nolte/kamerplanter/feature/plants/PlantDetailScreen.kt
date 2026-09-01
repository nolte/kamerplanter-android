package io.github.nolte.kamerplanter.feature.plants

import android.content.Context
import android.text.format.DateUtils
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Yard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import io.github.nolte.kamerplanter.core.camera.PhotoPicking
import io.github.nolte.kamerplanter.core.camera.rememberCameraPermission
import io.github.nolte.kamerplanter.core.camera.rememberPhotoPicking
import io.github.nolte.kamerplanter.core.network.CareAction
import io.github.nolte.kamerplanter.core.network.DIARY_ENTRY_TYPES
import io.github.nolte.kamerplanter.core.network.DIARY_TEXT_MAX
import io.github.nolte.kamerplanter.core.network.DIARY_TITLE_MAX
import io.github.nolte.kamerplanter.core.network.Detection
import io.github.nolte.kamerplanter.core.network.DiaryDraft
import io.github.nolte.kamerplanter.core.network.DiaryEntry
import io.github.nolte.kamerplanter.core.network.ENTRY_TYPE_NOTE
import io.github.nolte.kamerplanter.core.network.EnvironmentReading
import io.github.nolte.kamerplanter.core.network.PlantDetail
import io.github.nolte.kamerplanter.core.network.PlantPhase
import io.github.nolte.kamerplanter.core.network.PlantPhoto
import io.github.nolte.kamerplanter.core.network.PlantRemoval
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeState
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

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
    // Which entry the editor is open on. Held by key rather than by value so it survives a
    // reload: the list is replaced after every write, and an entry held by identity would be
    // a copy of a row that no longer exists.
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingKey?.let { key ->
        state.diary.valueOrNull?.firstOrNull { it.key == key }
    }

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
                onCompose = { entry ->
                    editingKey = entry?.key
                    noteOpen = true
                },
            )
        }
    }

    if (noteOpen) {
        NoteComposer(
            viewModel = viewModel,
            state = state,
            editing = editing,
            onClose = {
                noteOpen = false
                editingKey = null
            },
        )
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
    editing: DiaryEntry?,
    onClose: () -> Unit,
) {
    LaunchedEffect(state.actionDone) {
        val written = state.actionDone == PlantAction.NOTE_ADDED ||
            state.actionDone == PlantAction.NOTE_UPDATED
        if (written) onClose()
    }
    NoteDialog(
        microscope = viewModel.microscope,
        isSaving = state.isWorking,
        editing = editing,
        onDismiss = onClose,
        onSave = { viewModel.saveEntry(it, editing = editing?.key) },
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
    /** Opens the editor — empty for a new entry, on the entry it is handed. */
    onCompose: (DiaryEntry?) -> Unit,
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
                onAddNote = { onCompose(null) },
                onDetectPests = { onDetectPests(plant.key) },
                onRetrySection = viewModel::reload,
                onLoadOlderDiary = viewModel::loadOlderDiary,
                diaryRow = DiaryRowActions(
                    onEdit = onCompose,
                    onDelete = { viewModel.deleteEntry(it.key) },
                    onRequestAnalysis = { viewModel.requestAnalysis(it.key) },
                ),
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
    val failure = state.actionError ?: state.actionRefusal?.let { stringResource(it) }
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
            DiaryRow(entry, imageLoader, actions.diaryRow)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (state.diaryHasMore) {
            item {
                TextButton(
                    onClick = actions.onLoadOlderDiary,
                    enabled = !state.isLoadingOlder,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(
                        stringResource(
                            if (state.isLoadingOlder) {
                                R.string.plants_diary_loading_older
                            } else {
                                R.string.plants_diary_older
                            },
                        ),
                    )
                }
            }
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
                text = stringResource(
                    R.string.plants_removed_on,
                    removal.removedOn.asLocalDate(LocalContext.current),
                ),
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
    val context = LocalContext.current
    val facts = listOfNotNull(
        plant.plantedOn?.let {
            stringResource(R.string.plants_fact_planted, it.asLocalDate(context))
        },
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
    val context = LocalContext.current
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        current?.let {
            Text(
                text = it.startedAt?.let { since ->
                    stringResource(
                        R.string.plants_phase_current_since,
                        it.name,
                        since.asLocalDate(context),
                    )
                } ?: it.name,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        history.forEach { phase ->
            Text(
                text = listOfNotNull(
                    phase.name,
                    phase.startedAt?.asLocalDate(context),
                    phase.endedAt?.asLocalDate(context),
                ).joinToString(SEPARATOR),
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
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        checks.forEach { check ->
            Column {
                check.recordedAt?.let {
                    Text(
                        text = it.asLocalDate(context),
                        style = MaterialTheme.typography.labelMedium,
                    )
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
private fun DiaryRow(entry: DiaryEntry, imageLoader: ImageLoader?, actions: DiaryRowActions?) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            entry.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            } ?: Text(
                text = stringResource(entry.kind.entryTypeLabel()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            actions?.let { EntryMenu(entry = entry, actions = it) }
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
        if (entry.tags.isNotEmpty()) {
            Text(
                text = entry.tags.joinToString(TAG_SEPARATOR),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        entry.analysisLine()?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        entry.createdAt?.let {
            Text(
                text = it.asLocalDate(LocalContext.current),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** What can be done to one entry; `null` for a row nobody is allowed to act on. */
data class DiaryRowActions(
    val onEdit: (DiaryEntry) -> Unit,
    val onDelete: (DiaryEntry) -> Unit,
    val onRequestAnalysis: (DiaryEntry) -> Unit,
)

/**
 * Edit, delete, and — where this reader may — ask for an analysis.
 *
 * Analysis is offered per entry rather than per page: the backend decides it from authorship,
 * so in a shared garden one list legitimately mixes entries that can be analysed with entries
 * that cannot, and one verdict cached for the page would offer an action that 403s.
 */
@Composable
private fun EntryMenu(entry: DiaryEntry, actions: DiaryRowActions) {
    var open by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.plants_entry_actions),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.plants_entry_edit)) },
                onClick = {
                    open = false
                    actions.onEdit(entry)
                },
            )
            if (entry.canRequestAnalysis) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.plants_entry_analyse)) },
                    onClick = {
                        open = false
                        actions.onRequestAnalysis(entry)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.plants_entry_delete)) },
                onClick = {
                    open = false
                    confirmingDelete = true
                },
            )
        }
    }
    if (confirmingDelete) {
        // Asked before, not undone after: the instance is the only place the entry exists,
        // and nothing in this app can bring one back.
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.plants_entry_delete_title)) },
            text = { Text(stringResource(R.string.plants_entry_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        actions.onDelete(entry)
                    },
                ) { Text(stringResource(R.string.plants_entry_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.plants_note_cancel))
                }
            },
        )
    }
}

/**
 * What the instance's analysis has to say, or how far it has got.
 *
 * The state is shown even without a result, because "asked for, still running" is the answer
 * for as long as it takes and an entry that showed nothing would read as one nobody asked
 * about.
 */
@Composable
private fun DiaryEntry.analysisLine(): String? = analysis?.takeIf { it.isNotBlank() }
    ?: analysisState
        ?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.plants_entry_analysis_state, it) }

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
    /** The entry being rewritten, or `null` when this is a new one. */
    editing: DiaryEntry?,
    onDismiss: () -> Unit,
    onSave: (draft: DiaryDraft) -> Unit,
) {
    // Keyed on the entry: opening the editor on a different entry has to start from that
    // entry's text, and a draft remembered across both would put one entry's words into the
    // other.
    val draft = rememberSaveable(editing?.key, saver = NoteDraft.Saver) {
        editing?.let(NoteDraft::of) ?: NoteDraft()
    }
    val permission = rememberCameraPermission(requestOnFirstShow = false)
    val withCamera = rememberCameraGate(permission)
    val cameraState by microscope.state.collectAsStateWithLifecycle()
    val microscopeAttached = cameraState !is MicroscopeState.Unavailable
    val picking = rememberPhotoPicking { picked ->
        draft.photos = (draft.photos + picked).take(MAX_PHOTOS - draft.keptRefs.size)
    }
    if (draft.microscopeOpen) {
        MicroscopeSession(start = microscope.start, stop = microscope.stop)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(draft.titleRes())) },
        text = {
            NoteDialogBody(
                draft = draft,
                microscope = microscope,
                microscopeAttached = microscopeAttached,
                photos = PhotoAccess(picking = picking, withCamera = withCamera),
                isNew = editing == null,
            )
        },
        confirmButton = {
            // Text, not photos, is what the endpoint requires (`minLength: 1`). A picture with
            // no words is a fine idea and a 422, so the button says so up front rather than
            // letting the instance refuse the entry after the photos have uploaded.
            TextButton(
                onClick = {
                    onSave(
                        DiaryDraft(
                            entryType = draft.entryType,
                            title = draft.title,
                            text = draft.text,
                            photoRefs = draft.keptRefs,
                            newPhotos = draft.photos,
                            tags = draft.tagList(),
                            captureEnvironment = draft.captureEnvironment,
                        ),
                    )
                },
                enabled = draft.canSave && !draft.microscopeOpen && !isSaving,
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
 * The system picker and the camera gate, which always travel together.
 *
 * Two of the three photo sources need the CAMERA grant — the phone camera obviously, and the
 * microscope because AOSP refuses to show the USB dialogue for a video-class device to an app
 * without it — so the gate is never useful without the picker beside it.
 */
private class PhotoAccess(val picking: PhotoPicking, val withCamera: (() -> Unit) -> Unit)

/**
 * Either the viewfinder or the form — the dialogue is one or the other, never both.
 */
@Composable
private fun NoteDialogBody(
    draft: NoteDraft,
    /** The microscope, and whether one is there to use. */
    microscope: MicroscopeAccess,
    microscopeAttached: Boolean,
    /** Where a photo comes from, and the camera grant that two of the three need. */
    photos: PhotoAccess,
    /** False while an existing entry is being rewritten; see [NoteForm]. */
    isNew: Boolean,
) {
    if (draft.microscopeOpen) {
        MicroscopeCapture(
            microscope = microscope,
            onCaptured = {
                draft.photos = (draft.photos + it).take(MAX_PHOTOS - draft.keptRefs.size)
                draft.microscopeOpen = false
            },
            onCancel = { draft.microscopeOpen = false },
        )
        return
    }
    NoteForm(
        draft = draft,
        isNew = isNew,
        // The camera grant covers two of the three sources: the phone camera obviously, and
        // the microscope because AOSP refuses to show the USB dialogue for a video-class
        // device to an app without it. Only the library picker needs nothing — the system
        // picker grants per item as it goes.
        sources = PhotoSourceActions(
            onCamera = { photos.withCamera(photos.picking.takePhoto) },
            onLibrary = photos.picking.pickFromLibrary,
            // Offered only while a device is actually attached. Shown regardless, it was a
            // button whose whole answer was "no microscope here" — which the picker can say
            // by not offering it (#12).
            onMicroscope = { photos.withCamera { draft.microscopeOpen = true } }
                .takeIf { microscopeAttached },
        ),
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
/**
 * The photos an entry already has: the ids the instance knows them by, and the thumbnails that
 * show them. One value rather than two lists, because they are the same photos in the same
 * order and a draft that let them drift would save the wrong ones.
 */
private data class KeptPhotos(
    val refs: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
)

@Stable
private class NoteDraft(
    text: String = "",
    title: String = "",
    /** Comma-separated while it is being typed; split on save. */
    tags: String = "",
    entryType: String = ENTRY_TYPE_NOTE,
    captureEnvironment: Boolean = true,
    /** The photos this entry already has, when one is being rewritten. */
    kept: KeptPhotos = KeptPhotos(),
) {

    var text by mutableStateOf(text)
    var title by mutableStateOf(title)
    var tags by mutableStateOf(tags)
    var entryType by mutableStateOf(entryType)
    var photos by mutableStateOf<List<ByteArray>>(emptyList())
    var captureEnvironment by mutableStateOf(captureEnvironment)
    var microscopeOpen by mutableStateOf(false)

    /** Photos the instance already holds; dropping one here removes it from the entry. */
    var kept by mutableStateOf(kept)

    val keptRefs: List<String> get() = kept.refs
    val keptUrls: List<String> get() = kept.urls

    /** How many photos the entry would have — kept plus newly taken. */
    val photoCount: Int get() = keptRefs.size + photos.size

    /**
     * Whether this draft is one the endpoint would accept.
     *
     * Text is what it requires (`minLength: 1`), and the two lengths are its own limits. A
     * picture with no words is a fine idea and a 422, so the button says so up front rather
     * than letting the instance refuse the entry after the photos have uploaded.
     */
    val canSave: Boolean
        get() = text.isNotBlank() &&
            text.length <= DIARY_TEXT_MAX &&
            title.length <= DIARY_TITLE_MAX

    fun titleRes(): Int = when {
        microscopeOpen -> R.string.plants_note_microscope
        else -> R.string.plants_note_title
    }

    /** The tags as the endpoint takes them: split, trimmed, and without the empties. */
    fun tagList(): List<String> = tags.split(',').map(String::trim).filter(String::isNotEmpty)

    /** Removes one of the photos the entry already had, by position. */
    fun dropKept(index: Int) {
        kept = KeptPhotos(
            refs = kept.refs.filterIndexed { at, _ -> at != index },
            urls = kept.urls.filterIndexed { at, _ -> at != index },
        )
    }

    companion object {
        val Saver: Saver<NoteDraft, Any> = listSaver(
            save = {
                listOf(
                    it.text,
                    it.title,
                    it.tags,
                    it.entryType,
                    it.captureEnvironment,
                    it.kept.refs,
                    it.kept.urls,
                )
            },
            restore = {
                @Suppress("UNCHECKED_CAST")
                NoteDraft(
                    text = it[0] as String,
                    title = it[1] as String,
                    tags = it[2] as String,
                    entryType = it[3] as String,
                    captureEnvironment = it[4] as Boolean,
                    kept = KeptPhotos(refs = it[5] as List<String>, urls = it[6] as List<String>),
                )
            },
        )

        /** A draft that starts where an existing entry left off. */
        fun of(entry: DiaryEntry) = NoteDraft(
            text = entry.text,
            title = entry.title.orEmpty(),
            tags = entry.tags.joinToString(TAG_SEPARATOR),
            entryType = entry.kind,
            kept = KeptPhotos(refs = entry.photoRefs, urls = entry.photoUrls),
        )
    }
}

/** The entry form: what kind, what to write, what to attach, and what to record with it. */
@Composable
private fun NoteForm(draft: NoteDraft, isNew: Boolean, sources: PhotoSourceActions) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EntryTypePicker(selected = draft.entryType, onSelect = { draft.entryType = it })
        OutlinedTextField(
            value = draft.title,
            onValueChange = { draft.title = it.take(DIARY_TITLE_MAX) },
            label = { Text(stringResource(R.string.plants_note_title_hint)) },
            singleLine = true,
            isError = draft.title.length >= DIARY_TITLE_MAX,
            supportingText = { Remaining(draft.title.length, DIARY_TITLE_MAX) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.text,
            // Capped rather than merely counted: the instance refuses anything longer, and
            // finding that out after typing another page of text is worse than not being able
            // to type it.
            onValueChange = { draft.text = it.take(DIARY_TEXT_MAX) },
            label = { Text(stringResource(R.string.plants_note_hint)) },
            isError = draft.text.length >= DIARY_TEXT_MAX,
            supportingText = { Remaining(draft.text.length, DIARY_TEXT_MAX) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.tags,
            onValueChange = { draft.tags = it },
            label = { Text(stringResource(R.string.plants_note_tags_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // The photos already on the entry, which an edit can drop, then the new ones.
        KeptPhotos(urls = draft.keptUrls, onRemove = draft::dropKept)
        PickedPhotos(
            photos = draft.photos,
            onRemove = { index -> draft.photos = draft.photos.filterIndexed { at, _ -> at != index } },
        )
        PhotoSources(enabled = draft.photoCount < MAX_PHOTOS, actions = sources)
        // Only while writing. The switch asks the instance to *look* at its sensors, and an
        // entry being rewritten was looked at when it was written — `PUT` carries no such
        // field. Shown on an edit it was a control that quietly did nothing, which is worse
        // than one that is not there.
        if (isNew) {
            EnvironmentSwitch(
                checked = draft.captureEnvironment,
                onChange = { draft.captureEnvironment = it },
            )
        }
    }
}

/**
 * What kind of entry this is.
 *
 * All six the backend defines, because they are what the diary is read back by — "problem" and
 * "milestone" are what somebody scrolling a season looks for, and filing everything as a note
 * makes the whole diary one undifferentiated column.
 */
@Composable
private fun EntryTypePicker(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DIARY_ENTRY_TYPES.forEach { type ->
            FilterChip(
                selected = type == selected,
                onClick = { onSelect(type) },
                label = { Text(stringResource(type.entryTypeLabel())) },
            )
        }
    }
}

/** How much of a field is left, said only once it is worth saying. */
@Composable
private fun Remaining(used: Int, limit: Int) {
    if (used < limit - REMAINING_THRESHOLD) return
    Text(
        text = stringResource(R.string.plants_note_remaining, limit - used),
        style = MaterialTheme.typography.labelSmall,
        color = if (used >= limit) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/** The photos an entry already has, each of which an edit may drop. */
@Composable
private fun KeptPhotos(urls: List<String>, onRemove: (Int) -> Unit) {
    if (urls.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        urls.forEachIndexed { index, _ ->
            AssistChip(
                onClick = { onRemove(index) },
                label = { Text(stringResource(R.string.plants_note_photo, index + 1)) },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(
                            R.string.plants_note_remove_photo,
                            index + 1,
                        ),
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

/** The backend's entry kinds, in the reader's language; an unknown one keeps its own name. */
private fun String.entryTypeLabel(): Int = when (this) {
    "observation" -> R.string.plants_entry_observation
    "problem" -> R.string.plants_entry_problem
    "milestone" -> R.string.plants_entry_milestone
    "measurement" -> R.string.plants_entry_measurement
    "photo" -> R.string.plants_entry_photo
    else -> R.string.plants_entry_note
}

/** How close to a limit is close enough to start counting down. */
private const val REMAINING_THRESHOLD = 100

/** Where a photo can come from; bundled so the form's signature stays readable. */
internal data class PhotoSourceActions(
    val onCamera: () -> Unit,
    val onLibrary: () -> Unit,
    /** `null` while no microscope is attached — the option is then not offered at all. */
    val onMicroscope: (() -> Unit)?,
)

/** Where a photo can come from. */
@Composable
private fun PhotoSources(enabled: Boolean, actions: PhotoSourceActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhotoSource(Icons.Filled.PhotoCamera, R.string.plants_note_take_photo, enabled, actions.onCamera)
        PhotoSource(Icons.Filled.PhotoLibrary, R.string.plants_note_pick_photo, enabled, actions.onLibrary)
        // Only while one is attached. Offered regardless, the button's whole answer was "no
        // microscope here", which the picker can say by not offering it (#12).
        actions.onMicroscope?.let {
            PhotoSource(Icons.Filled.Biotech, R.string.plants_note_microscope, enabled, it)
        }
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
    Column {
        Text(
            text = stringResource(R.string.plants_note_capture_environment_explained),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EnvironmentSwitchRow(checked = checked, onChange = onChange)
    }
}

@Composable
private fun EnvironmentSwitchRow(checked: Boolean, onChange: (Boolean) -> Unit) {
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
    PlantAction.NOTE_UPDATED -> R.string.plants_action_note_updated
    PlantAction.NOTE_DELETED -> R.string.plants_action_note_deleted
    PlantAction.ANALYSIS_REQUESTED -> R.string.plants_action_analysis_requested
}

private val HEADER_IMAGE_HEIGHT = 200.dp

/**
 * The calendar day this instant falls on **for the reader**, not for the server.
 *
 * The plain first-ten-characters reading is the server's day: a phase that began
 * `2026-08-14T02:00:00Z` started on the 13th for someone in UTC-5, and taking the prefix
 * would tell them the 14th. Fields that carry an instant therefore go through the device's
 * zone first.
 *
 * A bare `LocalDate` — `planted_on` and `removed_on` are exactly that upstream — carries no
 * instant to convert, so it is read as the calendar day it already is. Anything this build
 * cannot parse falls back to the caller, which keeps the ISO date rather than showing nothing.
 */
private fun String.asLocalCalendarDay(): LocalDate? =
    runCatching { OffsetDateTime.parse(this).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }
        .recoverCatching { LocalDate.parse(take(ISO_DATE_LENGTH)) }
        .getOrNull()

/**
 * `2026-08-16T09:31:00Z` as the reader's own device writes a date.
 *
 * The clock time is dropped — a date on this page needs the day, not the minute — and the day
 * itself goes through the platform's formatter rather than being printed as the ISO string it
 * arrives as: 08/16 and 16.08. are the same date to two readers who would each misread the
 * other's.
 *
 * Which day that is comes from [asLocalCalendarDay], which is where the reader's time zone is
 * taken into account. A value this build cannot parse falls back to its first ten characters —
 * the ISO date, readable — rather than to nothing.
 */
private fun String.asLocalDate(context: Context): String {
    val date = asLocalCalendarDay() ?: return take(ISO_DATE_LENGTH)
    val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )
}

/** How tags read as one line, both in the editor and on a row. */
private const val TAG_SEPARATOR = ", "

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
    /** Appends the next page of older entries. */
    val onLoadOlderDiary: () -> Unit,
    /** What each entry's own menu offers. */
    val diaryRow: DiaryRowActions,
)
