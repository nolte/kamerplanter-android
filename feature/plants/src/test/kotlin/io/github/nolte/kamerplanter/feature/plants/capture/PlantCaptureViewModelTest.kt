package io.github.nolte.kamerplanter.feature.plants.capture

import io.github.nolte.kamerplanter.core.camera.CapturedImage
import io.github.nolte.kamerplanter.core.camera.NormalizationProfile
import io.github.nolte.kamerplanter.core.network.ActionOutcome
import io.github.nolte.kamerplanter.core.network.ConsentOutcome
import io.github.nolte.kamerplanter.core.network.ConsentTerms
import io.github.nolte.kamerplanter.core.network.DiaryDraft
import io.github.nolte.kamerplanter.core.network.DiaryOutcome
import io.github.nolte.kamerplanter.core.network.Fetched
import io.github.nolte.kamerplanter.core.network.IdentificationReadiness
import io.github.nolte.kamerplanter.core.network.IdentifyOutcome
import io.github.nolte.kamerplanter.core.network.Location
import io.github.nolte.kamerplanter.core.network.PlantActionsClient
import io.github.nolte.kamerplanter.core.network.PlantCaptureClient
import io.github.nolte.kamerplanter.core.network.PlantCreateOutcome
import io.github.nolte.kamerplanter.core.network.PlantDraft
import io.github.nolte.kamerplanter.core.network.PlantOrgan
import io.github.nolte.kamerplanter.core.network.Site
import io.github.nolte.kamerplanter.core.network.SpeciesCreateOutcome
import io.github.nolte.kamerplanter.core.network.SpeciesDraft
import io.github.nolte.kamerplanter.core.network.SpeciesEntry
import io.github.nolte.kamerplanter.core.network.Suggestion
import io.github.nolte.kamerplanter.feature.plants.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The add-a-plant form over fakes of its two seams: what it proposes, what it refuses, the
 * order it writes in, and what it reports when the photo does not make it (R19–R22, R28–R32) —
 * and the identification route laid over it: consent before the camera, one original cut
 * twice, the three thin answers, the organ re-run, and a species the catalogue lacks
 * (R5–R16, R25–R27, R31).
 */
class PlantCaptureViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val capture = FakeCaptureClient()
    private val plants = FakePlantActions()
    private val today = LocalDate.of(2026, 9, 2)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = PlantCaptureViewModel(
        capture = capture,
        plants = plants,
        today = { today },
        language = { "de" },
        work = dispatcher,
    )

    /** A form already on the identification route, image previewed. */
    private fun previewing(): PlantCaptureViewModel {
        capture.readiness = IdentificationReadiness.Ready
        val model = viewModel()
        model.form()
        model.startIdentification()
        model.identify(IMAGE)
        model.form()
        return model
    }

    private fun PlantCaptureViewModel.step(): IdentificationStep? = form().step

    private fun answer(isPlant: Boolean, suggestions: List<Suggestion>, message: String? = null) =
        IdentifyOutcome.Identified("req", isPlant = isPlant, suggestions = suggestions, message = message)

    private fun form(): PlantCaptureState.Form = viewModel().let { model ->
        model.state.settled() as PlantCaptureState.Form
        model.form()
    }

    private fun PlantCaptureViewModel.form(): PlantCaptureState.Form = state.settled() as PlantCaptureState.Form

    private fun <T> StateFlow<T>.settled(): T {
        dispatcher.scheduler.advanceUntilIdle()
        return value
    }

    // --- preparing the form -----------------------------------------------------------------

    @Test
    fun `the form opens with today's date and nothing else filled in`() {
        val form = form()

        assertEquals(today, form.inputs.plantedOn)
        assertNull(form.inputs.speciesKey)
        assertEquals("", form.inputs.instanceId)
        assertEquals(2, form.catalogue.size)
        assertEquals(1, form.sites.size)
    }

    @Test
    fun `a catalogue that cannot be loaded is the form's absence, a site list that cannot is a notice`() {
        capture.catalogue = Fetched.Failed("down")
        assertTrue(viewModel().state.settled() is PlantCaptureState.Failed)

        capture.catalogue = Fetched.Loaded(CATALOGUE)
        capture.sites = Fetched.Failed("down")
        val form = form()
        assertEquals(R.string.plants_add_sites_failed, form.notice)
        assertTrue(form.sites.isEmpty())
    }

    @Test
    fun `a refused credential and no connection are their own states`() {
        capture.catalogue = Fetched.Unauthorized
        assertEquals(PlantCaptureState.Unauthorized, viewModel().state.settled())

        capture.catalogue = Fetched.NotConnected
        assertEquals(PlantCaptureState.NotConnected, viewModel().state.settled())
    }

    // --- the species field (R17, R18) -------------------------------------------------------

    @Test
    fun `the species search matches common and scientific names and tells a miss from an empty catalogue`() {
        val model = viewModel()
        model.form()

        model.searchSpecies("cheese")
        assertEquals(listOf("sp-monstera"), model.form().speciesMatches.map { it.key })

        model.searchSpecies("Ficus")
        assertEquals(listOf("sp-ficus"), model.form().speciesMatches.map { it.key })

        model.searchSpecies("zzz")
        assertTrue(model.form().speciesMatches.isEmpty())
        assertFalse(model.form().catalogue.isEmpty())
    }

    @Test
    fun `choosing a species shows its scientific name and typing again clears the choice`() {
        val model = viewModel()
        model.form()

        model.chooseSpecies(MONSTERA)
        assertEquals("Monstera deliciosa", model.form().inputs.speciesQuery)
        assertEquals("sp-monstera", model.form().inputs.speciesKey)

        model.searchSpecies("Monstera d")
        assertNull(model.form().inputs.speciesKey)
    }

    // --- the identifier (R19–R21) -------------------------------------------------------------

    @Test
    fun `the identifier is proposed from species and location and skips what is taken`() {
        capture.taken = Fetched.Loaded(setOf("SPM_01"))
        val model = viewModel()
        model.form()

        model.chooseSpecies(MONSTERA)
        assertEquals("SPM_02", model.form().inputs.instanceId)

        model.chooseSite("site-1")
        model.form()
        model.chooseLocation("loc-1")
        assertEquals("loc-1_SPM_01", model.form().inputs.instanceId)
    }

    @Test
    fun `an identifier the user edited is never overwritten by a proposal`() {
        val model = viewModel()
        model.form()
        model.chooseSpecies(MONSTERA)

        model.editInstanceId("MY-OWN")
        model.chooseSpecies(FICUS)

        assertEquals("MY-OWN", model.form().inputs.instanceId)
    }

    // --- places (R23) -------------------------------------------------------------------------

    @Test
    fun `choosing a site loads its locations and a new site drops the old location`() {
        val model = viewModel()
        model.form()

        model.chooseSite("site-1")
        assertEquals(listOf("loc-1"), model.form().locations?.map { it.key })
        model.chooseLocation("loc-1")
        assertEquals("loc-1", model.form().inputs.locationKey)

        model.chooseSite(null)
        assertNull(model.form().inputs.locationKey)
        assertNull(model.form().locations)
    }

    @Test
    fun `choosing the same site again keeps its location, and a location needs a site`() {
        val model = viewModel()
        model.form()
        model.chooseSite("site-1")
        model.form()
        model.chooseLocation("loc-1")

        model.chooseSite("site-1")
        assertEquals("loc-1", model.form().inputs.locationKey)

        model.chooseSite(null)
        model.chooseLocation("loc-1")
        assertNull(model.form().inputs.locationKey)
    }

    // --- refusing and creating (R22, R29–R32) ------------------------------------------------

    @Test
    fun `a submission without species, identifier or date is refused field by field`() {
        val model = viewModel()
        model.form()
        model.editInstanceId("")
        model.editPlantedOn(null)

        model.submit()

        val form = model.form()
        assertEquals(setOf(FormField.SPECIES, FormField.INSTANCE_ID, FormField.PLANTED_ON), form.errors)
        assertTrue(capture.created.isEmpty())
    }

    @Test
    fun `a taken identifier is refused before anything is sent`() {
        capture.taken = Fetched.Loaded(setOf("TAKEN"))
        val model = viewModel()
        model.form()
        model.chooseSpecies(MONSTERA)
        model.editInstanceId("TAKEN")

        model.submit()

        assertEquals(setOf(FormField.INSTANCE_ID), model.form().errors)
        assertTrue(capture.created.isEmpty())
    }

    @Test
    fun `creating sends the form's fields, keeps the photo as the cover, and lands on the plant`() {
        val model = viewModel()
        model.form()
        model.chooseSpecies(MONSTERA)
        model.editPlantName("  Fenster  ")
        model.chooseSite("site-1")
        model.form()
        model.chooseLocation("loc-1")
        model.setPhoto(byteArrayOf(1, 2, 3))

        model.submit()

        assertEquals(PlantCaptureState.Created("p-9", photoSaved = true), model.state.settled())
        val draft = capture.created.single()
        assertEquals(PlantDraft("loc-1_SPM_01", "sp-monstera", today, "Fenster", "site-1", "loc-1"), draft)
        val (plantKey, jpeg, asCover) = plants.photos.single()
        assertEquals("p-9", plantKey)
        assertTrue(jpeg.contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(asCover)
    }

    /** R28: keeping off means no upload at all. */
    @Test
    fun `with keeping turned off no photo is uploaded`() {
        val model = viewModel()
        model.form()
        model.chooseSpecies(MONSTERA)
        model.setPhoto(byteArrayOf(1))
        model.keepPhoto(false)

        model.submit()

        assertEquals(PlantCaptureState.Created("p-9", photoSaved = null), model.state.settled())
        assertTrue(plants.photos.isEmpty())
    }

    /** R30: the plant stays created; the photo is reported as not saved, once. */
    @Test
    fun `a failed photo never rolls the plant back and is reported as its own outcome`() {
        plants.photoOutcome = ActionOutcome.Failed("nope")
        val model = viewModel()
        model.form()
        model.chooseSpecies(MONSTERA)
        model.setPhoto(byteArrayOf(1))

        model.submit()

        assertEquals(PlantCaptureState.Created("p-9", photoSaved = false), model.state.settled())
        assertEquals(1, capture.created.size)
    }

    @Test
    fun `a rejected plant names the instance's reason and keeps the form`() {
        capture.createOutcome = PlantCreateOutcome.Rejected("instance_id: already in use")
        val model = viewModel()
        model.form()
        model.chooseSpecies(MONSTERA)

        model.submit()

        val form = model.form()
        assertEquals(R.string.plants_add_rejected, form.notice)
        assertEquals("instance_id: already in use", form.noticeDetail)
        assertFalse(form.submitting)
    }

    @Test
    fun `what is edited while the instance answers survives its refusal`() {
        capture.createOutcome = PlantCreateOutcome.Rejected("instance_id: taken")
        capture.hold = CompletableDeferred()
        val model = viewModel()
        model.form()
        model.chooseSpecies(MONSTERA)

        model.submit()
        model.form()
        model.editPlantName("Corrected while adding")
        model.keepPhoto(false)
        capture.hold!!.complete(Unit)

        val form = model.form()
        assertEquals(R.string.plants_add_rejected, form.notice)
        assertEquals("Corrected while adding", form.inputs.plantName)
        assertFalse(form.keepPhoto)
        assertFalse(form.submitting)
    }

    /** R33: a role is a sentence and no retry; a refused credential ends in Settings. */
    @Test
    fun `a role and a refused credential are their own outcomes`() {
        capture.createOutcome = PlantCreateOutcome.NotPermitted
        val forbidden = viewModel()
        forbidden.form()
        forbidden.chooseSpecies(MONSTERA)
        forbidden.submit()
        val failed = forbidden.state.settled() as PlantCaptureState.Failed
        assertEquals(R.string.plants_add_not_permitted, failed.message)
        assertFalse(failed.canRetry)

        capture.createOutcome = PlantCreateOutcome.Unauthorized
        val refused = viewModel()
        refused.form()
        refused.chooseSpecies(MONSTERA)
        refused.submit()
        assertEquals(PlantCaptureState.Unauthorized, refused.state.settled())
    }

    // --- the identification route: readiness and consent (R1–R6) ------------------------------

    @Test
    fun `the identification route is offered only where the recogniser is, and never gates the form`() {
        assertFalse(form().identificationOffered)

        capture.readiness = IdentificationReadiness.Ready
        assertTrue(form().identificationOffered)

        capture.readiness = IdentificationReadiness.ConsentRequired(null)
        assertTrue(form().identificationOffered)

        capture.readiness = IdentificationReadiness.Unavailable("down")
        val form = form()
        assertFalse(form.identificationOffered)
        assertEquals(2, form.catalogue.size)
    }

    @Test
    fun `a missing consent is asked for before any image, in the instance's words, and granted from the flow`() {
        capture.readiness = IdentificationReadiness.ConsentRequired(TERMS)
        val model = viewModel()
        model.form()

        model.startIdentification()
        val consent = model.step() as IdentificationStep.Consent
        assertEquals(TERMS, consent.terms)
        // The camera is not on offer yet: an image must not exist before the consent does.
        model.identify(IMAGE)
        assertTrue(model.step() is IdentificationStep.Consent)

        model.grantIdentificationConsent()
        model.form()
        assertEquals(1, capture.consentsGranted)
        assertEquals(IdentificationStep.ChooseSource, model.step())
        assertEquals(IdentificationReadiness.Ready, model.form().identification)
    }

    @Test
    fun `a consent the instance would not record stays a prompt, and a refused credential leaves the form`() {
        capture.readiness = IdentificationReadiness.ConsentRequired(null)
        capture.consentOutcome = ConsentOutcome.Failed("down")
        val model = viewModel()
        model.form()
        model.startIdentification()

        model.grantIdentificationConsent()
        model.form()
        val consent = model.step() as IdentificationStep.Consent
        assertTrue(consent.failed)
        assertFalse(consent.granting)

        capture.consentOutcome = ConsentOutcome.Unauthorized
        model.grantIdentificationConsent()
        assertEquals(PlantCaptureState.Unauthorized, model.state.settled())
    }

    // --- capture, preview and the two cuts (R9–R11) --------------------------------------------

    @Test
    fun `the image is previewed at the recognition cut and sent as it, with organ auto and the app's language`() {
        val model = previewing()

        val preview = model.step() as IdentificationStep.Preview
        assertTrue(preview.image.recognitionJpeg.contentEquals(RECOGNITION_BYTES))
        assertTrue(capture.identified.isEmpty())

        model.sendForIdentification()
        model.form()

        val (jpeg, organ, language) = capture.identified.single()
        assertTrue(jpeg.contentEquals(RECOGNITION_BYTES))
        assertEquals("auto", organ)
        assertEquals("de", language)
    }

    @Test
    fun `an image that cannot be brought under the recogniser's ceiling asks for another`() {
        capture.readiness = IdentificationReadiness.Ready
        val model = viewModel()
        model.form()
        model.startIdentification()

        model.identify { _, _ -> null }
        model.form()

        assertEquals(IdentificationStep.Unusable, model.step())
        model.identify(IMAGE)
        model.form()
        assertTrue(model.step() is IdentificationStep.Preview)
    }

    @Test
    fun `retaking discards the image and leaving keeps what was typed`() {
        val model = previewing()
        model.editPlantName("Fenster")

        model.retakeIdentification()
        assertEquals(IdentificationStep.ChooseSource, model.step())

        model.leaveIdentification()
        assertNull(model.step())
        assertEquals("Fenster", model.form().inputs.plantName)
        assertNull(model.form().photo)
    }

    @Test
    fun `an answer arriving after the route was left neither reopens it nor touches the form`() {
        capture.hold = CompletableDeferred()
        val model = previewing()
        model.sendForIdentification()
        model.form()
        assertTrue(model.step() is IdentificationStep.Identifying)

        model.leaveIdentification()
        model.editPlantName("typed after leaving")
        capture.hold!!.complete(Unit)
        model.form()

        assertNull(model.step())
        assertEquals("typed after leaving", model.form().inputs.plantName)
        assertNull(model.form().photo)
    }

    @Test
    fun `a choice still on its way when the user leaves does not fill the form later`() {
        val model = previewing()
        model.sendForIdentification()
        model.form()
        capture.hold = CompletableDeferred()
        model.chooseSuggestion(MONSTERA_GUESS)
        model.form()

        model.leaveIdentification()
        model.chooseSpecies(FICUS)
        capture.hold!!.complete(Unit)
        model.form()

        assertEquals("sp-ficus", model.form().inputs.speciesKey)
        assertNull(model.form().identificationRequestKey)
        assertNull(model.form().photo)
    }

    // --- the answers (R13–R16) ------------------------------------------------------------------

    @Test
    fun `not a plant, nothing recognised and only weak candidates are three different answers`() {
        capture.identifyOutcome = answer(isPlant = false, suggestions = emptyList())
        val notAPlant = previewing().also { it.sendForIdentification() }
        assertEquals(WeakResult.NOT_A_PLANT, (notAPlant.step() as IdentificationStep.Suggestions).weak)

        capture.identifyOutcome = answer(isPlant = true, suggestions = emptyList())
        val nothing = previewing().also { it.sendForIdentification() }
        assertEquals(WeakResult.NOTHING_RECOGNISED, (nothing.step() as IdentificationStep.Suggestions).weak)

        capture.identifyOutcome = answer(isPlant = true, suggestions = listOf(WEAK_GUESS), message = "unsicher")
        val weak = previewing().also { it.sendForIdentification() }
        val step = weak.step() as IdentificationStep.Suggestions
        assertEquals(WeakResult.LOW_CONFIDENCE, step.weak)
        assertEquals(listOf(WEAK_GUESS), step.suggestions)
        assertEquals("unsicher", step.message)

        capture.identifyOutcome = answer(isPlant = true, suggestions = listOf(MONSTERA_GUESS))
        val ranked = previewing().also { it.sendForIdentification() }
        assertNull((ranked.step() as IdentificationStep.Suggestions).weak)
    }

    /** R16: the organ is asked for only after a weak answer, and the re-run reuses the image. */
    @Test
    fun `an organ hint re-runs the identification on the same bytes`() {
        capture.identifyOutcome = answer(isPlant = true, suggestions = emptyList())
        val model = previewing()
        model.sendForIdentification()
        model.form()

        model.sendForIdentification(PlantOrgan.LEAF)
        model.form()

        assertEquals(listOf("auto", "leaf"), capture.identified.map { it.second })
        assertTrue(capture.identified.all { it.first.contentEquals(RECOGNITION_BYTES) })
        assertEquals(PlantOrgan.LEAF, (model.step() as IdentificationStep.Suggestions).organ)
    }

    @Test
    fun `choosing a candidate records the choice, fills the form, and cuts the photo at the gallery profile`() {
        capture.identifyOutcome = IdentifyOutcome.Identified(
            "req",
            isPlant = true,
            suggestions = listOf(MONSTERA_GUESS.copy(autoAccept = true)),
            message = null,
        )
        val model = previewing()
        model.sendForIdentification()
        model.form()

        model.chooseSuggestion(MONSTERA_GUESS.copy(autoAccept = true))
        val form = model.form()

        assertEquals(listOf("req" to 1), capture.selected)
        assertNull(form.step)
        assertEquals("sp-monstera", form.inputs.speciesKey)
        assertEquals("Monstera deliciosa", form.inputs.speciesQuery)
        assertEquals("SPM_01", form.inputs.instanceId)
        assertEquals("req", form.identificationRequestKey)
        // The plant's picture is the gallery cut of the original, never the recogniser's bytes (R10).
        assertTrue(form.photo!!.jpeg.contentEquals(GALLERY_BYTES))
        assertTrue(form.keepPhoto)
        // `auto_accept` filled the form in and created nothing (R14).
        assertTrue(capture.created.isEmpty())
    }

    @Test
    fun `a candidate matched to a record the loaded catalogue lacks is carried in by its key`() {
        val stale = MONSTERA_GUESS.copy(scientificName = "Ficus elastica", matchedSpeciesKey = "sp-new")
        capture.identifyOutcome = answer(isPlant = true, suggestions = listOf(stale))
        val model = previewing()
        model.sendForIdentification()
        model.form()

        model.chooseSuggestion(stale)

        val form = model.form()
        assertEquals("sp-new", form.inputs.speciesKey)
        assertEquals("Ficus elastica", form.species?.scientificName)
    }

    @Test
    fun `continuing by hand from a weak answer keeps the photo and drops the route`() {
        capture.identifyOutcome = answer(isPlant = false, suggestions = emptyList())
        val model = previewing()
        model.sendForIdentification()
        model.form()

        model.continueByHand()

        val form = model.form()
        assertNull(form.step)
        assertNull(form.identificationRequestKey)
        assertTrue(form.photo!!.jpeg.contentEquals(GALLERY_BYTES))
    }

    /** R33: each refusal is its own step; a role offers no retry; a lost consent asks again. */
    @Test
    fun `the recogniser's refusals are their own steps`() {
        capture.identifyOutcome = IdentifyOutcome.Refused("image too large")
        val refused = previewing().also { it.sendForIdentification() }
        assertEquals("image too large", (refused.step() as IdentificationStep.Refused).reason)

        capture.identifyOutcome = IdentifyOutcome.RateLimited(30)
        val paused = previewing().also { it.sendForIdentification() }
        assertEquals(30L, (paused.step() as IdentificationStep.RateLimited).retryAfterSeconds)

        capture.identifyOutcome = IdentifyOutcome.Unavailable("down")
        val down = previewing().also { it.sendForIdentification() }
        assertTrue(down.step() is IdentificationStep.Unavailable)

        capture.identifyOutcome = IdentifyOutcome.NotPermitted
        val forbidden = previewing().also { it.sendForIdentification() }
        assertEquals(IdentificationStep.NotPermitted, forbidden.step())

        capture.identifyOutcome = IdentifyOutcome.ConsentMissing
        val revoked = previewing().also { it.sendForIdentification() }
        assertTrue(revoked.step() is IdentificationStep.Consent)
        assertTrue(revoked.form().identification is IdentificationReadiness.ConsentRequired)

        capture.identifyOutcome = IdentifyOutcome.Unauthorized
        val expired = previewing().also { it.sendForIdentification() }
        assertEquals(PlantCaptureState.Unauthorized, expired.state.settled())
    }

    // --- the species gap (R25–R27) --------------------------------------------------------------

    @Test
    fun `a candidate the catalogue lacks becomes a species to create, and the plant is created against its key`() {
        capture.identifyOutcome = answer(isPlant = true, suggestions = listOf(UNKNOWN_GUESS))
        val model = previewing()
        model.sendForIdentification()
        model.form()
        model.chooseSuggestion(UNKNOWN_GUESS)

        val form = model.form()
        assertNull(form.inputs.speciesKey)
        val expected = SpeciesDraft("Pilea peperomioides", listOf("Chinese money plant"), "Pilea")
        assertEquals(expected, form.inputs.pendingSpecies)
        assertEquals("Pilea peperomioides", form.inputs.speciesQuery)
        assertEquals("PIL_01", form.inputs.instanceId)
        assertTrue(capture.speciesCreated.isEmpty())

        model.submit()

        assertEquals(PlantCaptureState.Created("p-9", photoSaved = true), model.state.settled())
        assertEquals(listOf(form.inputs.pendingSpecies), capture.speciesCreated)
        assertEquals("sp-created", capture.created.single().speciesKey)
        assertEquals(listOf("req" to "p-9"), capture.linked)
    }

    @Test
    fun `a role that may not create species is a refusal without retry, a conflict keeps the form`() {
        capture.identifyOutcome = answer(isPlant = true, suggestions = listOf(UNKNOWN_GUESS))
        capture.speciesOutcome = SpeciesCreateOutcome.NotPermitted
        val forbidden = previewing()
        forbidden.sendForIdentification()
        forbidden.form()
        forbidden.chooseSuggestion(UNKNOWN_GUESS)
        forbidden.form()
        forbidden.submit()
        val failed = forbidden.state.settled() as PlantCaptureState.Failed
        assertEquals(R.string.plants_add_species_not_permitted, failed.message)
        assertFalse(failed.canRetry)
        assertTrue(capture.created.isEmpty())

        capture.speciesOutcome = SpeciesCreateOutcome.Conflict
        val conflicted = previewing()
        conflicted.sendForIdentification()
        conflicted.form()
        conflicted.chooseSuggestion(UNKNOWN_GUESS)
        conflicted.form()
        conflicted.submit()
        val form = conflicted.form()
        assertEquals(R.string.plants_add_species_conflict, form.notice)
        assertFalse(form.submitting)
        assertNotNull(form.inputs.pendingSpecies)
        assertTrue(capture.created.isEmpty())
    }

    @Test
    fun `a species the instance rejects is named in its words, and no plant is created`() {
        capture.identifyOutcome = answer(isPlant = true, suggestions = listOf(UNKNOWN_GUESS))
        capture.speciesOutcome = SpeciesCreateOutcome.Rejected("scientific_name: not a name")
        val model = previewing()
        model.sendForIdentification()
        model.form()
        model.chooseSuggestion(UNKNOWN_GUESS)
        model.form()

        model.submit()

        val form = model.form()
        assertEquals(R.string.plants_add_species_rejected, form.notice)
        assertEquals("scientific_name: not a name", form.noticeDetail)
        assertTrue(capture.created.isEmpty())
    }

    @Test
    fun `a species created before the plant was refused is not created twice`() {
        capture.identifyOutcome = answer(isPlant = true, suggestions = listOf(UNKNOWN_GUESS))
        capture.createOutcome = PlantCreateOutcome.Rejected("instance_id: taken")
        val model = previewing()
        model.sendForIdentification()
        model.form()
        model.chooseSuggestion(UNKNOWN_GUESS)
        model.form()

        model.submit()
        assertEquals("sp-created", model.form().inputs.speciesKey)
        assertNull(model.form().inputs.pendingSpecies)

        capture.createOutcome = PlantCreateOutcome.Created("p-9")
        model.submit()
        model.state.settled()

        assertEquals(1, capture.speciesCreated.size)
        assertEquals(2, capture.created.size)
    }

    // --- the link (R31) -------------------------------------------------------------------------

    @Test
    fun `the identification is linked to the plant best effort, and never on the manual route`() {
        capture.identifyOutcome = answer(isPlant = true, suggestions = listOf(MONSTERA_GUESS))
        capture.linkOutcome = ActionOutcome.Failed("down")
        val identified = previewing()
        identified.sendForIdentification()
        identified.form()
        identified.chooseSuggestion(MONSTERA_GUESS)
        identified.form()
        identified.submit()

        assertEquals(PlantCaptureState.Created("p-9", photoSaved = true), identified.state.settled())
        assertEquals(listOf("req" to "p-9"), capture.linked)

        capture.linked.clear()
        val manual = viewModel()
        manual.form()
        manual.chooseSpecies(MONSTERA)
        manual.submit()
        manual.state.settled()
        assertTrue(capture.linked.isEmpty())
    }

    // --- fakes ----------------------------------------------------------------------------------

    private class FakeCaptureClient : PlantCaptureClient {
        var catalogue: Fetched<List<SpeciesEntry>> = Fetched.Loaded(CATALOGUE)
        var sites: Fetched<List<Site>> = Fetched.Loaded(listOf(Site("site-1", "Balcony")))
        var locations: Fetched<List<Location>> = Fetched.Loaded(listOf(Location("loc-1", "Left rail")))
        var taken: Fetched<Set<String>> = Fetched.Loaded(emptySet())
        var createOutcome: PlantCreateOutcome = PlantCreateOutcome.Created("p-9")
        val created = mutableListOf<PlantDraft>()
        var readiness: IdentificationReadiness = IdentificationReadiness.NotOffered
        var consentOutcome: ConsentOutcome = ConsentOutcome.Granted
        var consentsGranted = 0
        var identifyOutcome: IdentifyOutcome =
            IdentifyOutcome.Identified("req", isPlant = true, suggestions = listOf(MONSTERA_GUESS), message = null)
        val identified = mutableListOf<Triple<ByteArray, String, String>>()
        val selected = mutableListOf<Pair<String, Int>>()
        var linkOutcome: ActionOutcome = ActionOutcome.Done
        val linked = mutableListOf<Pair<String, String>>()
        var speciesOutcome: SpeciesCreateOutcome = SpeciesCreateOutcome.Created("sp-created")
        val speciesCreated = mutableListOf<SpeciesDraft>()

        /** Set to keep the next identify, select or create waiting until the test lets it go. */
        var hold: CompletableDeferred<Unit>? = null

        private suspend fun held() {
            hold?.await()
            hold = null
        }

        override suspend fun identificationReadiness(): IdentificationReadiness = readiness
        override suspend fun grantIdentificationConsent(): ConsentOutcome {
            if (consentOutcome == ConsentOutcome.Granted) consentsGranted++
            return consentOutcome
        }
        override suspend fun identify(jpeg: ByteArray, organ: String, language: String): IdentifyOutcome {
            identified += Triple(jpeg, organ, language)
            held()
            return identifyOutcome
        }
        override suspend fun selectSuggestion(requestKey: String, rank: Int): ActionOutcome {
            selected += requestKey to rank
            held()
            return ActionOutcome.Done
        }
        override suspend fun linkIdentification(requestKey: String, plantKey: String): ActionOutcome {
            linked += requestKey to plantKey
            return linkOutcome
        }
        override suspend fun catalogue(): Fetched<List<SpeciesEntry>> = catalogue
        override suspend fun createSpecies(draft: SpeciesDraft): SpeciesCreateOutcome {
            speciesCreated += draft
            return speciesOutcome
        }
        override suspend fun sites(): Fetched<List<Site>> = sites
        override suspend fun locations(siteKey: String): Fetched<List<Location>> = locations
        override suspend fun instanceIds(): Fetched<Set<String>> = taken
        override suspend fun createPlant(draft: PlantDraft): PlantCreateOutcome {
            created += draft
            held()
            return createOutcome
        }
    }

    private class FakePlantActions : PlantActionsClient {
        var photoOutcome: ActionOutcome = ActionOutcome.Done
        val photos = mutableListOf<Triple<String, ByteArray, Boolean>>()

        override suspend fun addPhoto(plantKey: String, jpeg: ByteArray, asCover: Boolean): ActionOutcome {
            photos += Triple(plantKey, jpeg, asCover)
            return photoOutcome
        }
        override suspend fun diary(plantKey: String, offset: Int, limit: Int): DiaryOutcome =
            error("not under test")
        override suspend fun addEntry(plantKey: String, draft: DiaryDraft): ActionOutcome =
            error("not under test")
        override suspend fun updateEntry(
            plantKey: String,
            entryKey: String,
            draft: DiaryDraft,
        ): ActionOutcome = error("not under test")
        override suspend fun deleteEntry(plantKey: String, entryKey: String): ActionOutcome =
            error("not under test")
        override suspend fun requestAnalysis(plantKey: String, entryKey: String): ActionOutcome =
            error("not under test")
        override suspend fun confirmCare(plantKey: String, kind: String): ActionOutcome =
            error("not under test")
    }

    private companion object {
        val MONSTERA = SpeciesEntry("sp-monstera", "Monstera deliciosa", listOf("Swiss cheese plant"))
        val FICUS = SpeciesEntry("sp-ficus", "Ficus lyrata", listOf("Fiddle-leaf fig"))
        val CATALOGUE = listOf(MONSTERA, FICUS)
        val TERMS = ConsentTerms(
            label = "Pflanzenbestimmung",
            description = "Das Foto geht an Pl@ntNet.",
            legalBasis = "Art. 6 Abs. 1 lit. a DSGVO",
        )
        val RECOGNITION_BYTES = byteArrayOf(1, 2, 8, 0)
        val GALLERY_BYTES = byteArrayOf(2, 0, 4, 8)

        /** One original, two cuts: which profile was asked for is what the tests read back. */
        val IMAGE = CapturedImage { profile, _ ->
            if (profile == NormalizationProfile.RECOGNITION) RECOGNITION_BYTES else GALLERY_BYTES
        }
        val MONSTERA_GUESS = Suggestion(
            rank = 1,
            scientificName = "Monstera deliciosa",
            commonNames = listOf("Swiss cheese plant"),
            confidence = 0.91,
            genus = "Monstera",
            matchedSpeciesKey = "sp-monstera",
            autoAccept = false,
        )
        val WEAK_GUESS = MONSTERA_GUESS.copy(confidence = 0.12)
        val UNKNOWN_GUESS = Suggestion(
            rank = 1,
            scientificName = "Pilea peperomioides",
            commonNames = listOf("Chinese money plant"),
            confidence = 0.7,
            genus = "Pilea",
            matchedSpeciesKey = null,
            autoAccept = false,
        )
    }
}
