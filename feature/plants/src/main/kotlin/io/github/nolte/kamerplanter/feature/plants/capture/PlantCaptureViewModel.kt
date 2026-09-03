package io.github.nolte.kamerplanter.feature.plants.capture

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nolte.kamerplanter.core.camera.CapturedImage
import io.github.nolte.kamerplanter.core.camera.MAX_PHOTO_BYTES
import io.github.nolte.kamerplanter.core.camera.NormalizationProfile
import io.github.nolte.kamerplanter.core.network.ActionOutcome
import io.github.nolte.kamerplanter.core.network.ConsentOutcome
import io.github.nolte.kamerplanter.core.network.Fetched
import io.github.nolte.kamerplanter.core.network.IdentificationReadiness
import io.github.nolte.kamerplanter.core.network.IdentifyOutcome
import io.github.nolte.kamerplanter.core.network.PlantActionsClient
import io.github.nolte.kamerplanter.core.network.PlantCaptureClient
import io.github.nolte.kamerplanter.core.network.PlantCreateOutcome
import io.github.nolte.kamerplanter.core.network.PlantDraft
import io.github.nolte.kamerplanter.core.network.PlantOrgan
import io.github.nolte.kamerplanter.core.network.SpeciesCreateOutcome
import io.github.nolte.kamerplanter.core.network.SpeciesDraft
import io.github.nolte.kamerplanter.core.network.SpeciesEntry
import io.github.nolte.kamerplanter.core.network.Suggestion
import io.github.nolte.kamerplanter.feature.plants.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

/**
 * Drives the add-a-plant form (R-8).
 *
 * Nothing is written until [submit], and then in a fixed order the requirements spell out:
 * a species the catalogue lacks first (R25), the plant, the link back to the identification
 * (R31), the photo only once the plant exists, the cover as part of keeping the photo — and a
 * photo that fails never rolls the plant back (R29, R30). The identification route fills the
 * form in and nothing else (R14).
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class PlantCaptureViewModel(
    private val capture: PlantCaptureClient,
    /** Where a kept photo goes: the plant's own gallery, as its cover (R29). */
    private val plants: PlantActionsClient,
    private val today: () -> LocalDate,
    /** The clock the identifier's suffix is taken from, as the web UI takes it (R19). */
    private val nowMillis: () -> Long,
    /** The language the interface speaks, which is what the recogniser names species in. */
    private val language: () -> String,
    /** Where images are re-encoded: decoding a sensor's frame is not main-thread work. */
    private val work: CoroutineDispatcher,
) : ViewModel() {

    /**
     * A second constructor rather than default parameters: Dagger does not read Kotlin
     * default arguments, so `today: () -> LocalDate = …` on the injected constructor would
     * demand a binding for `Function0<LocalDate>` that nobody provides.
     */
    @Inject
    constructor(capture: PlantCaptureClient, plants: PlantActionsClient) : this(
        capture = capture,
        plants = plants,
        today = LocalDate::now,
        nowMillis = System::currentTimeMillis,
        language = { Locale.getDefault().language },
        work = Dispatchers.Default,
    )

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
        // Asked for beside the form, not before it: the manual route does not wait on the
        // recogniser, and an instance without one still gets its form at once (R2, R3).
        val readiness = async { capture.identificationReadiness() }
        val loadedCatalogue = when (val outcome = catalogue.await()) {
            is Fetched.Loaded -> outcome.value
            // Without the catalogue there is no form; the siblings are not worth waiting for,
            // and `coroutineScope` would otherwise hold the failure screen back until the
            // slowest of them — up to 25 pages of plants — has answered.
            else -> {
                coroutineContext.cancelChildren()
                return@coroutineScope when (outcome) {
                    Fetched.NotConnected -> PlantCaptureState.NotConnected
                    Fetched.Unauthorized -> PlantCaptureState.Unauthorized
                    else -> PlantCaptureState.Failed(R.string.plants_add_failed_body)
                }
            }
        }
        // Sites and the identifiers are conveniences: a form without them still creates a
        // plant, so their failure is a notice on the form rather than the form's absence.
        val loadedSites = (sites.await() as? Fetched.Loaded)?.value
        val takenIds = (taken.await() as? Fetched.Loaded)?.value.orEmpty()
        PlantCaptureState.Form(
            // Proposed from the start, `PLANT-` until a species names it — as the web UI does.
            inputs = FormInputs(
                plantedOn = today(),
                instanceId = proposeInstanceId(null, today(), nowMillis(), takenIds),
            ),
            catalogue = loadedCatalogue,
            sites = loadedSites.orEmpty(),
            locations = null,
            takenIds = takenIds,
            notice = if (loadedSites == null) R.string.plants_add_sites_failed else null,
            identification = readiness.await(),
        )
    }

    // --- the species field (R17, R18) --------------------------------------------------------

    fun searchSpecies(query: String) = updateInputs {
        copy(speciesKey = null, pendingSpecies = null, speciesQuery = query)
    }

    fun chooseSpecies(entry: SpeciesEntry) = updateInputs {
        copy(speciesKey = entry.key, pendingSpecies = null, speciesQuery = entry.scientificName)
    }

    // --- the other fields (R19–R23) ----------------------------------------------------------

    fun editInstanceId(text: String) = updateInputs { copy(instanceId = text, instanceIdEdited = true) }

    fun editPlantName(text: String) = updateInputs { copy(plantName = text) }

    fun editPlantedOn(date: LocalDate?) = updateInputs { copy(plantedOn = date) }

    /**
     * The site; the location depends on it (R23). A *different* site clears the location and
     * loads its own; the same site chosen again changes nothing — a dropdown offers the
     * chosen entry too, and picking it must not cost the location under it.
     */
    fun chooseSite(siteKey: String?) {
        if ((_state.value as? PlantCaptureState.Form)?.inputs?.siteKey == siteKey) return
        updateInputs { copy(siteKey = siteKey, locationKey = null) }
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

    /** One of the chosen site's locations, or `null` for none; meaningless without a site. */
    fun chooseLocation(locationKey: String?) = updateInputs {
        copy(locationKey = locationKey.takeIf { siteKey != null })
    }

    // --- the photo (R8, R28) -----------------------------------------------------------------

    /** A new photo, or `null` to discard the one held. Keeping is on again for a new one (R28). */
    fun setPhoto(jpeg: ByteArray?) = updateForm {
        copy(photo = jpeg?.let(::CapturedPhoto), keepPhoto = if (jpeg != null) true else keepPhoto)
    }

    fun keepPhoto(keep: Boolean) = updateForm { copy(keepPhoto = keep) }

    // --- the identification route (R5–R16) ---------------------------------------------------

    /** Onto the route: the consent question first where it is open, the camera only after it (R5). */
    fun startIdentification() = updateForm {
        when (val readiness = identification) {
            IdentificationReadiness.Ready -> copy(step = IdentificationStep.ChooseSource)
            is IdentificationReadiness.ConsentRequired -> copy(step = IdentificationStep.Consent(readiness.terms))
            else -> this
        }
    }

    /**
     * Back to the form, whatever was on the route; nothing captured there is kept. Whatever
     * the route still had in flight is cancelled with it — an answer arriving after the user
     * left must not reopen the route, nor overwrite what they typed in the meantime.
     */
    fun leaveIdentification() {
        routeJob?.cancel()
        updateForm { copy(step = null) }
    }

    /** The one call the route has in flight; a new one replaces it, leaving cancels it. */
    private var routeJob: Job? = null

    private fun launchOnRoute(block: suspend () -> Unit) {
        routeJob?.cancel()
        routeJob = viewModelScope.launch { block() }
    }

    /** Records the consent on the instance (R6), then opens the source choice. */
    fun grantIdentificationConsent() {
        val consent = step<IdentificationStep.Consent>() ?: return
        if (consent.granting) return
        updateForm { copy(step = consent.copy(granting = true, failed = false)) }
        launchOnRoute {
            when (capture.grantIdentificationConsent()) {
                ConsentOutcome.Granted -> updateForm {
                    copy(identification = IdentificationReadiness.Ready, step = IdentificationStep.ChooseSource)
                }
                ConsentOutcome.Unauthorized -> _state.value = PlantCaptureState.Unauthorized
                // A grant the instance would not record: said beside the prompt, which stays.
                ConsentOutcome.NotPermitted, is ConsentOutcome.Failed ->
                    updateForm { copy(step = consent.copy(granting = false, failed = true)) }
            }
        }
    }

    /**
     * An image for the recogniser. Cut to the `recognition` profile now, so the preview shows
     * what will be sent (R9, R10); the original stays for the gallery cut later.
     */
    fun identify(image: CapturedImage) {
        if (step<IdentificationStep.ChooseSource>() == null && step<IdentificationStep.Unusable>() == null) return
        launchOnRoute {
            val recognition = withContext(work) {
                image.normalized(NormalizationProfile.RECOGNITION, IDENTIFY_MAX_BYTES)
            }
            updateForm {
                copy(
                    step = if (recognition == null) {
                        IdentificationStep.Unusable
                    } else {
                        IdentificationStep.Preview(IdentificationImage(image, recognition))
                    },
                )
            }
        }
    }

    /** Discards the image on the route and asks for another (R9). */
    fun retakeIdentification() = updateForm { copy(step = IdentificationStep.ChooseSource) }

    /**
     * Sends the image to the recogniser. [organ] is `auto` from the preview (R16); the organ
     * chips after a weak answer call this again with a hint, on the same bytes.
     */
    fun sendForIdentification(organ: PlantOrgan = PlantOrgan.AUTO) {
        val image = when (val step = step<IdentificationStep>()) {
            is IdentificationStep.Preview -> step.image
            is IdentificationStep.Suggestions -> step.image
            is IdentificationStep.RateLimited -> step.image
            is IdentificationStep.Unavailable -> step.image
            else -> return
        }
        updateForm { copy(step = IdentificationStep.Identifying(image, organ)) }
        launchOnRoute {
            val outcome = capture.identify(image.recognitionJpeg, organ.wire, language())
            answered(image, organ, outcome)
        }
    }

    private fun answered(image: IdentificationImage, organ: PlantOrgan, outcome: IdentifyOutcome) {
        when (outcome) {
            is IdentifyOutcome.Identified -> updateForm {
                copy(
                    step = IdentificationStep.Suggestions(
                        image = image,
                        requestKey = outcome.requestKey,
                        suggestions = outcome.suggestions,
                        weak = outcome.weakness(),
                        organ = organ,
                        message = outcome.message,
                    ),
                )
            }
            is IdentifyOutcome.Refused -> updateForm { copy(step = IdentificationStep.Refused(image, outcome.reason)) }
            is IdentifyOutcome.RateLimited ->
                updateForm { copy(step = IdentificationStep.RateLimited(image, outcome.retryAfterSeconds)) }
            is IdentifyOutcome.Unavailable -> updateForm { copy(step = IdentificationStep.Unavailable(image)) }
            // Reachable despite the pre-flight: revoked in the web UI meanwhile. Back in front
            // of the question rather than a retry button that would meet the same answer.
            IdentifyOutcome.ConsentMissing -> updateForm {
                val terms = (identification as? IdentificationReadiness.ConsentRequired)?.terms
                copy(
                    identification = IdentificationReadiness.ConsentRequired(terms),
                    step = IdentificationStep.Consent(terms),
                )
            }
            IdentifyOutcome.Unauthorized -> _state.value = PlantCaptureState.Unauthorized
            IdentifyOutcome.NotPermitted -> updateForm { copy(step = IdentificationStep.NotPermitted) }
        }
    }

    /**
     * The user's pick among the candidates (R13): recorded on the instance, then the form is
     * filled in from it — and only filled in, `auto_accept` or not (R14). A candidate the
     * catalogue does not carry becomes a species to create on submission (R25).
     */
    fun chooseSuggestion(suggestion: Suggestion) {
        val step = step<IdentificationStep.Suggestions>() ?: return
        if (step.selecting != null) return
        updateForm { copy(step = step.copy(selecting = suggestion.rank)) }
        launchOnRoute {
            // Best effort, like the link: the selection is the instance's record of what the
            // user chose, and a form that refused to fill in because that record failed would
            // cost the user the answer they were given.
            step.requestKey?.let { capture.selectSuggestion(it, suggestion.rank) }
            val gallery = withContext(work) {
                step.image.original.normalized(NormalizationProfile.GALLERY, MAX_PHOTO_BYTES)
            }
            updateForm {
                filledFrom(suggestion).copy(
                    step = null,
                    identificationRequestKey = step.requestKey,
                    photo = gallery?.let(::CapturedPhoto) ?: photo,
                    keepPhoto = true,
                )
            }
        }
    }

    /** From a weak answer to the form by hand, with the photo kept for the plant (R15, R28). */
    fun continueByHand() {
        val step = step<IdentificationStep>() ?: return
        val image = when (step) {
            is IdentificationStep.Suggestions -> step.image
            is IdentificationStep.RateLimited -> step.image
            is IdentificationStep.Unavailable -> step.image
            is IdentificationStep.Refused -> step.image
            else -> null
        }
        if (image == null) {
            leaveIdentification()
            return
        }
        launchOnRoute {
            val gallery = withContext(work) { image.original.normalized(NormalizationProfile.GALLERY, MAX_PHOTO_BYTES) }
            updateForm { copy(step = null, photo = gallery?.let(::CapturedPhoto) ?: photo, keepPhoto = true) }
        }
    }

    private fun PlantCaptureState.Form.filledFrom(suggestion: Suggestion): PlantCaptureState.Form {
        val key = suggestion.matchedSpeciesKey
        return when {
            key == null -> withInputs {
                copy(
                    speciesKey = null,
                    pendingSpecies = SpeciesDraft(suggestion.scientificName, suggestion.commonNames, suggestion.genus),
                    speciesQuery = suggestion.scientificName,
                )
            }
            catalogue.any { it.key == key } -> withInputs {
                copy(speciesKey = key, pendingSpecies = null, speciesQuery = suggestion.scientificName)
            }
            // Matched upstream to a record the loaded catalogue predates: carried in as an
            // entry of its own, so the field can name it like any other (R17).
            else -> copy(catalogue = catalogue + SpeciesEntry(key, suggestion.scientificName, suggestion.commonNames))
                .withInputs { copy(speciesKey = key, pendingSpecies = null, speciesQuery = suggestion.scientificName) }
        }
    }

    // --- creating the plant (R22, R25–R27, R29–R32) --------------------------------------------

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
            // Read again after every await rather than carried from the tap: the fields stay
            // editable while the instance answers, and a refusal that wrote the tapped-on
            // snapshot back would undo whatever the user corrected in the meantime.
            if (!createPendingSpecies()) return@launch
            val settled = _state.value as? PlantCaptureState.Form ?: return@launch
            val outcome = capture.createPlant(settled.draft())
            when (outcome) {
                is PlantCreateOutcome.Created -> {
                    settled.identificationRequestKey?.let { linkBestEffort(it, outcome.plantKey) }
                    _state.value = PlantCaptureState.Created(
                        plantKey = outcome.plantKey,
                        photoSaved = settled.photo?.takeIf { settled.keepPhoto }?.let { keep(outcome.plantKey, it) },
                    )
                }
                PlantCreateOutcome.Unauthorized -> _state.value = PlantCaptureState.Unauthorized
                // A role, not a connection: asking again cannot widen it, so no retry (R33).
                PlantCreateOutcome.NotPermitted -> _state.value = PlantCaptureState.Failed(
                    R.string.plants_add_not_permitted,
                    canRetry = false,
                )
                is PlantCreateOutcome.Rejected -> settle(R.string.plants_add_rejected, outcome.reason)
                is PlantCreateOutcome.Failed -> settle(R.string.plants_add_unreachable)
            }
        }
    }

    /** The submission is over and the form stays, with a sentence about why. */
    private fun settle(@StringRes notice: Int, detail: String? = null) =
        updateForm { copy(submitting = false, notice = notice, noticeDetail = detail) }

    /**
     * The species first, where the catalogue lacked it (R25). `true` when the form now holds a
     * key to create the plant against; `false` after this has already put the refusal on
     * screen. A species that was created stays chosen even when the plant is then refused —
     * the create is idempotent upstream (R26), and a second submission must not make a second
     * one.
     */
    private suspend fun createPendingSpecies(): Boolean {
        val pending = (_state.value as? PlantCaptureState.Form)?.inputs?.pendingSpecies ?: return true
        return when (val outcome = capture.createSpecies(pending)) {
            is SpeciesCreateOutcome.Created -> {
                updateForm {
                    copy(
                        catalogue = catalogue + SpeciesEntry(outcome.key, pending.scientificName, pending.commonNames),
                        inputs = inputs.copy(speciesKey = outcome.key, pendingSpecies = null),
                    )
                }
                true
            }
            SpeciesCreateOutcome.Unauthorized -> false.also { _state.value = PlantCaptureState.Unauthorized }
            // A role the account lacks (R27) — the whole form is refused with it, because the
            // plant cannot be created without the species, and no retry widens a role.
            SpeciesCreateOutcome.NotPermitted -> false.also {
                _state.value = PlantCaptureState.Failed(R.string.plants_add_species_not_permitted, canRetry = false)
            }
            // The route's own 409 (R26): something other than the name collided. Its own
            // sentence, and the form stays for the user to pick a catalogue entry instead.
            SpeciesCreateOutcome.Conflict -> false.also { settle(R.string.plants_add_species_conflict) }
            is SpeciesCreateOutcome.Rejected -> false.also {
                settle(R.string.plants_add_species_rejected, outcome.reason)
            }
            is SpeciesCreateOutcome.Failed -> false.also { settle(R.string.plants_add_unreachable) }
        }
    }

    /** Ties the identification to its plant (R31). The plant exists either way; the outcome is not the user's. */
    private suspend fun linkBestEffort(requestKey: String, plantKey: String) {
        capture.linkIdentification(requestKey, plantKey)
    }

    /** The photo, uploaded only now that the plant exists, and as its cover (R29). */
    private suspend fun keep(plantKey: String, photo: CapturedPhoto): Boolean =
        plants.addPhoto(plantKey, photo.jpeg, asCover = true) is ActionOutcome.Done

    private fun PlantCaptureState.Form.validate(): Set<FormField> = buildSet {
        if (!inputs.hasSpecies) add(FormField.SPECIES)
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

    private inline fun <reified T : IdentificationStep> step(): T? =
        (_state.value as? PlantCaptureState.Form)?.step as? T

    private inline fun updateForm(change: PlantCaptureState.Form.() -> PlantCaptureState.Form) {
        _state.update { current -> (current as? PlantCaptureState.Form)?.change() ?: current }
    }

    /**
     * Applies an edit and re-derives the identifier proposal when the species changed (R19) —
     * unless the user has taken the field over, in which case what they typed stands.
     */
    private inline fun updateInputs(change: FormInputs.() -> FormInputs) = updateForm { withInputs(change) }

    private inline fun PlantCaptureState.Form.withInputs(change: FormInputs.() -> FormInputs): PlantCaptureState.Form {
        val edited = inputs.change()
        val speciesChanged = edited.speciesKey != inputs.speciesKey || edited.pendingSpecies != inputs.pendingSpecies
        val proposed = if (edited.instanceIdEdited || !speciesChanged) {
            edited
        } else {
            // The prefix comes from the scientific name, as in the web UI: the instance's
            // keys are numbers, and a species still to be created has no key at all.
            val speciesName = catalogue.firstOrNull { it.key == edited.speciesKey }?.scientificName
                ?: edited.pendingSpecies?.scientificName
            edited.copy(instanceId = proposeInstanceId(speciesName, today(), nowMillis(), takenIds))
        }
        return copy(inputs = proposed, errors = emptySet())
    }

    private companion object {
        /** The `/identify` route's own ceiling. */
        const val IDENTIFY_MAX_BYTES = 5 * 1024 * 1024
    }
}

/** Which of the three thin answers this is, or `null` for a list worth choosing from (R15). */
private fun IdentifyOutcome.Identified.weakness(): WeakResult? = when {
    !isPlant -> WeakResult.NOT_A_PLANT
    suggestions.isEmpty() -> WeakResult.NOTHING_RECOGNISED
    suggestions.none { it.confidence >= WEAK_CONFIDENCE } -> WeakResult.LOW_CONFIDENCE
    else -> null
}
