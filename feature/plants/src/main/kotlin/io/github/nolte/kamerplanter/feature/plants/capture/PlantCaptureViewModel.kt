package io.github.nolte.kamerplanter.feature.plants.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nolte.kamerplanter.core.network.ActionOutcome
import io.github.nolte.kamerplanter.core.network.Fetched
import io.github.nolte.kamerplanter.core.network.PlantActionsClient
import io.github.nolte.kamerplanter.core.network.PlantCaptureClient
import io.github.nolte.kamerplanter.core.network.PlantCreateOutcome
import io.github.nolte.kamerplanter.core.network.PlantDraft
import io.github.nolte.kamerplanter.core.network.SpeciesEntry
import io.github.nolte.kamerplanter.feature.plants.R
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Drives the add-a-plant form (R-8).
 *
 * Nothing is written until [submit], and then in a fixed order the requirements spell out:
 * the plant first, the photo only once the plant exists, the cover as part of keeping the
 * photo — and a photo that fails never rolls the plant back (R29, R30).
 */
@HiltViewModel
class PlantCaptureViewModel(
    private val capture: PlantCaptureClient,
    /** Where a kept photo goes: the plant's own gallery, as its cover (R29). */
    private val plants: PlantActionsClient,
    private val today: () -> LocalDate,
) : ViewModel() {

    /**
     * A second constructor rather than a default parameter: Dagger does not read Kotlin
     * default arguments, so `today: () -> LocalDate = …` on the injected constructor would
     * demand a binding for `Function0<LocalDate>` that nobody provides.
     */
    @Inject
    constructor(capture: PlantCaptureClient, plants: PlantActionsClient) : this(capture, plants, LocalDate::now)

    private val _state = MutableStateFlow<PlantCaptureState>(PlantCaptureState.Loading)
    val state: StateFlow<PlantCaptureState> = _state.asStateFlow()

    init {
        load()
    }

    /** Loads what the form needs; also the retry after a failure. */
    fun load() {
        _state.value = PlantCaptureState.Loading
        viewModelScope.launch {
            _state.value = prepareForm()
        }
    }

    private suspend fun prepareForm(): PlantCaptureState = coroutineScope {
        val catalogue = async { capture.catalogue() }
        val sites = async { capture.sites() }
        val taken = async { capture.instanceIds() }
        val loadedCatalogue = when (val outcome = catalogue.await()) {
            is Fetched.Loaded -> outcome.value
            Fetched.NotConnected -> return@coroutineScope PlantCaptureState.NotConnected
            Fetched.Unauthorized -> return@coroutineScope PlantCaptureState.Unauthorized
            is Fetched.Failed -> return@coroutineScope PlantCaptureState.Failed(R.string.plants_add_failed_body)
        }
        // Sites and the identifiers are conveniences: a form without them still creates a
        // plant, so their failure is a notice on the form rather than the form's absence.
        val loadedSites = (sites.await() as? Fetched.Loaded)?.value
        val takenIds = (taken.await() as? Fetched.Loaded)?.value.orEmpty()
        PlantCaptureState.Form(
            inputs = FormInputs(plantedOn = today()),
            catalogue = loadedCatalogue,
            sites = loadedSites.orEmpty(),
            locations = null,
            takenIds = takenIds,
            notice = if (loadedSites == null) R.string.plants_add_sites_failed else null,
        )
    }

    // --- the species field (R17, R18) --------------------------------------------------------

    fun searchSpecies(query: String) = updateInputs { copy(speciesKey = null, speciesQuery = query) }

    fun chooseSpecies(entry: SpeciesEntry) = updateInputs {
        copy(speciesKey = entry.key, speciesQuery = entry.scientificName)
    }

    // --- the other fields (R19–R23) ----------------------------------------------------------

    fun editInstanceId(text: String) = updateInputs { copy(instanceId = text, instanceIdEdited = true) }

    fun editPlantName(text: String) = updateInputs { copy(plantName = text) }

    fun editPlantedOn(date: LocalDate?) = updateInputs { copy(plantedOn = date) }

    /**
     * Site and location together, because the second depends on the first (R23): a new site
     * clears the location and loads the site's own; a location is only ever one of those.
     */
    fun choosePlace(siteKey: String?, locationKey: String?) {
        val siteChanged = (_state.value as? PlantCaptureState.Form)?.inputs?.siteKey != siteKey
        updateInputs { copy(siteKey = siteKey, locationKey = locationKey.takeUnless { siteChanged }) }
        if (!siteChanged) return
        updateForm { copy(locations = null) }
        if (siteKey == null) return
        viewModelScope.launch {
            val outcome = capture.locations(siteKey)
            updateForm {
                // The user may have moved on to another site while these loaded.
                if (inputs.siteKey != siteKey) return@updateForm this
                when (outcome) {
                    is Fetched.Loaded -> copy(locations = outcome.value)
                    else -> copy(locations = emptyList(), notice = R.string.plants_add_locations_failed)
                }
            }
        }
    }

    // --- the photo (R8, R28) -----------------------------------------------------------------

    /** A new photo, or `null` to discard the one held. Keeping is on again for a new one (R28). */
    fun setPhoto(jpeg: ByteArray?) = updateForm {
        copy(photo = jpeg?.let(::CapturedPhoto), keepPhoto = if (jpeg != null) true else keepPhoto)
    }

    fun keepPhoto(keep: Boolean) = updateForm { copy(keepPhoto = keep) }

    // --- creating the plant (R22, R29–R32) ---------------------------------------------------

    fun submit() {
        val form = _state.value as? PlantCaptureState.Form ?: return
        if (form.submitting) return
        val errors = form.validate()
        if (errors.isNotEmpty()) {
            _state.value = form.copy(errors = errors, notice = null, noticeDetail = null)
            return
        }
        _state.value = form.copy(submitting = true, errors = emptySet(), notice = null, noticeDetail = null)
        viewModelScope.launch {
            val outcome = capture.createPlant(form.draft())
            _state.value = when (outcome) {
                is PlantCreateOutcome.Created -> PlantCaptureState.Created(
                    plantKey = outcome.plantKey,
                    photoSaved = form.photo?.takeIf { form.keepPhoto }?.let { keep(outcome.plantKey, it) },
                )
                PlantCreateOutcome.Unauthorized -> PlantCaptureState.Unauthorized
                // A role, not a connection: asking again cannot widen it, so no retry (R33).
                PlantCreateOutcome.NotPermitted -> PlantCaptureState.Failed(
                    R.string.plants_add_not_permitted,
                    canRetry = false,
                )
                is PlantCreateOutcome.Rejected -> form.copy(
                    submitting = false,
                    notice = R.string.plants_add_rejected,
                    noticeDetail = outcome.reason,
                )
                is PlantCreateOutcome.Failed -> form.copy(submitting = false, notice = R.string.plants_add_unreachable)
            }
        }
    }

    /** The photo, uploaded only now that the plant exists, and as its cover (R29). */
    private suspend fun keep(plantKey: String, photo: CapturedPhoto): Boolean =
        plants.addPhoto(plantKey, photo.jpeg, asCover = true) is ActionOutcome.Done

    private fun PlantCaptureState.Form.validate(): Set<FormField> = buildSet {
        if (inputs.speciesKey == null) add(FormField.SPECIES)
        if (inputs.instanceId.isBlank() || instanceIdTaken) add(FormField.INSTANCE_ID)
        if (inputs.plantedOn == null) add(FormField.PLANTED_ON)
    }

    private fun PlantCaptureState.Form.draft() = PlantDraft(
        instanceId = inputs.instanceId.trim(),
        speciesKey = checkNotNull(inputs.speciesKey),
        plantedOn = checkNotNull(inputs.plantedOn),
        plantName = inputs.plantName.trim().ifEmpty { null },
        siteKey = inputs.siteKey,
        locationKey = inputs.locationKey,
    )

    // --- plumbing ----------------------------------------------------------------------------

    private inline fun updateForm(change: PlantCaptureState.Form.() -> PlantCaptureState.Form) {
        _state.update { current -> (current as? PlantCaptureState.Form)?.change() ?: current }
    }

    /**
     * Applies an edit and re-derives the identifier proposal from it (R19) — unless the user
     * has taken the field over, in which case what they typed stands.
     */
    private inline fun updateInputs(change: FormInputs.() -> FormInputs) = updateForm {
        val edited = inputs.change()
        val proposed = if (edited.instanceIdEdited) {
            edited
        } else {
            edited.copy(
                instanceId = proposeInstanceId(edited.speciesKey, edited.locationKey, takenIds)
                    ?: edited.instanceId.takeIf { edited.speciesKey != null }.orEmpty(),
            )
        }
        copy(inputs = proposed, errors = emptySet())
    }
}
