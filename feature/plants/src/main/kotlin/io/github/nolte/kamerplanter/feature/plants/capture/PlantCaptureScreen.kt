package io.github.nolte.kamerplanter.feature.plants.capture

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.nolte.kamerplanter.core.camera.NormalizationProfile
import io.github.nolte.kamerplanter.core.camera.rememberCameraPermission
import io.github.nolte.kamerplanter.core.camera.rememberPhotoPicking
import io.github.nolte.kamerplanter.feature.plants.CenteredMessage
import io.github.nolte.kamerplanter.feature.plants.R
import io.github.nolte.kamerplanter.feature.plants.rememberCameraGate

/**
 * Adding a plant (R-8, issue #50): one form, reached by hand today and from an
 * identification tomorrow, that creates nothing until it is confirmed.
 *
 * [onCreated] is called with the new plant's key once it exists and its photo — where one
 * was to be kept — is saved; the screen lands on the plant's page (R32). A photo that could
 * not be saved is reported here instead, with the same page as the way on (R30).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantCaptureScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onCreated: (plantKey: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlantCaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val created = state as? PlantCaptureState.Created
    LaunchedEffect(created) {
        if (created != null && created.photoSaved != false) onCreated(created.plantKey)
    }
    val photos = rememberPhotoSources(viewModel)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.plants_add_title)) },
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
        PlantCaptureBody(
            state = state,
            actions = remember(viewModel, onOpenSettings, onCreated, photos) {
                viewModel.actions(onOpenSettings, onCreated, photos)
            },
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Camera and library, each asked for only when reached for (R8). A permanently denied camera
 * grant leads to system settings rather than to a button that does nothing.
 */
@Composable
private fun rememberPhotoSources(viewModel: PlantCaptureViewModel): PhotoSources {
    val permission = rememberCameraPermission(requestOnFirstShow = false)
    val gate = rememberCameraGate(permission)
    // One photo at the gallery profile: the bytes kept with the plant (R10, R28).
    val picking = rememberPhotoPicking(maxCount = 1, profile = NormalizationProfile.GALLERY) { picked ->
        picked.firstOrNull()?.let(viewModel::setPhoto)
    }
    return remember(permission, gate, picking) {
        PhotoSources(
            onCamera = {
                if (!permission.isGranted && !permission.canAsk) permission.openSettings() else gate(picking.takePhoto)
            },
            onLibrary = picking.pickFromLibrary,
        )
    }
}

private fun PlantCaptureViewModel.actions(
    onOpenSettings: () -> Unit,
    onOpenPlant: (String) -> Unit,
    photos: PhotoSources,
) = PlantCaptureActions(
    onRetry = ::load,
    onOpenSettings = onOpenSettings,
    onOpenPlant = onOpenPlant,
    form = FormActions(
        onSearchSpecies = ::searchSpecies,
        onChooseSpecies = ::chooseSpecies,
        onEditInstanceId = ::editInstanceId,
        onEditPlantName = ::editPlantName,
        onEditPlantedOn = ::editPlantedOn,
        onChooseSite = { choosePlace(it, null) },
        onChooseLocation = { location ->
            choosePlace((state.value as? PlantCaptureState.Form)?.inputs?.siteKey, location)
        },
        onSubmit = ::submit,
        photo = PhotoActions(
            sources = photos,
            onRemove = { setPhoto(null) },
            onKeep = ::keepPhoto,
        ),
    ),
)

@Composable
internal fun PlantCaptureBody(
    state: PlantCaptureState,
    actions: PlantCaptureActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            PlantCaptureState.Loading -> CenteredMessage(
                title = stringResource(R.string.plants_add_loading),
                showProgress = true,
            )
            PlantCaptureState.NotConnected -> CenteredMessage(
                title = stringResource(R.string.plants_not_connected_title),
                body = stringResource(R.string.plants_add_not_connected_body),
                actionLabel = stringResource(R.string.plants_not_connected_action),
                onAction = actions.onOpenSettings,
            )
            PlantCaptureState.Unauthorized -> CenteredMessage(
                title = stringResource(R.string.plants_rejected_title),
                body = stringResource(R.string.plants_rejected_body),
                actionLabel = stringResource(R.string.plants_not_connected_action),
                onAction = actions.onOpenSettings,
            )
            is PlantCaptureState.Failed -> CenteredMessage(
                title = stringResource(R.string.plants_add_failed_title),
                body = stringResource(state.message),
                actionLabel = stringResource(R.string.plants_failed_retry).takeIf { state.canRetry },
                onAction = actions.onRetry.takeIf { state.canRetry },
            )
            is PlantCaptureState.Form -> CaptureForm(state, actions.form)
            is PlantCaptureState.Created -> CreatedBody(state, actions.onOpenPlant)
        }
    }
}

/** The plant exists. Shown only while the photo did not make it; otherwise the page opens itself. */
@Composable
private fun CreatedBody(state: PlantCaptureState.Created, onOpenPlant: (String) -> Unit) {
    if (state.photoSaved == false) {
        CenteredMessage(
            title = stringResource(R.string.plants_add_created_title),
            body = stringResource(R.string.plants_add_photo_not_saved),
            actionLabel = stringResource(R.string.plants_add_open_plant),
            onAction = { onOpenPlant(state.plantKey) },
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun CaptureForm(form: PlantCaptureState.Form, actions: FormActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PhotoSection(form, actions.photo)
        SpeciesField(form, actions)
        NameField(form, actions)
        InstanceIdField(form, actions)
        PlantedOnField(form, actions)
        PlaceFields(form, actions)
        FormNotice(form)
        Button(
            onClick = actions.onSubmit,
            enabled = !form.submitting && form.catalogue.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (form.submitting) R.string.plants_add_submitting else R.string.plants_add_submit))
        }
    }
}

/** The photo kept with the plant: where it comes from, what it looks like, whether to keep it (R8, R9, R28). */
@Composable
private fun PhotoSection(form: PlantCaptureState.Form, actions: PhotoActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.plants_add_photo_section), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhotoSource(Icons.Filled.PhotoCamera, R.string.plants_note_take_photo, actions.sources.onCamera)
            PhotoSource(Icons.Filled.PhotoLibrary, R.string.plants_note_pick_photo, actions.sources.onLibrary)
        }
        val photo = form.photo ?: return@Column
        Box {
            // Shown at the size it will be uploaded — these are the normalised bytes (R9).
            AsyncImage(
                model = photo.jpeg,
                contentDescription = stringResource(R.string.plants_add_photo_description),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PREVIEW_HEIGHT)
                    .clip(RoundedCornerShape(12.dp)),
            )
            IconButton(onClick = actions.onRemove, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.plants_add_remove_photo),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = form.keepPhoto, onCheckedChange = actions.onKeep)
            Text(
                text = stringResource(R.string.plants_add_keep_photo),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun PhotoSource(icon: ImageVector, labelRes: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = stringResource(labelRes), modifier = Modifier.padding(start = 4.dp))
    }
}

/** What happened last: a refusal with the instance's words beside it, or a place that would not load. */
@Composable
private fun FormNotice(form: PlantCaptureState.Form) {
    val notice = form.notice ?: return
    Text(
        text = listOfNotNull(stringResource(notice), form.noticeDetail).joinToString(" "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

private val PREVIEW_HEIGHT = 200.dp

/** Everything the screen can do, bundled so the form's signature stays readable. */
internal data class PlantCaptureActions(
    val onRetry: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenPlant: (plantKey: String) -> Unit,
    val form: FormActions,
)

internal data class FormActions(
    val onSearchSpecies: (String) -> Unit,
    val onChooseSpecies: (io.github.nolte.kamerplanter.core.network.SpeciesEntry) -> Unit,
    val onEditInstanceId: (String) -> Unit,
    val onEditPlantName: (String) -> Unit,
    val onEditPlantedOn: (java.time.LocalDate?) -> Unit,
    val onChooseSite: (String?) -> Unit,
    val onChooseLocation: (String?) -> Unit,
    val onSubmit: () -> Unit,
    val photo: PhotoActions,
)

internal data class PhotoActions(
    val sources: PhotoSources,
    val onRemove: () -> Unit,
    val onKeep: (Boolean) -> Unit,
)

internal data class PhotoSources(
    val onCamera: () -> Unit,
    val onLibrary: () -> Unit,
)
