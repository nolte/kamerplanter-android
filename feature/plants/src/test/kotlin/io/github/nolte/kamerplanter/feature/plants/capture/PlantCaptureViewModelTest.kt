package io.github.nolte.kamerplanter.feature.plants.capture

import io.github.nolte.kamerplanter.core.network.ActionOutcome
import io.github.nolte.kamerplanter.core.network.ConsentOutcome
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
import io.github.nolte.kamerplanter.core.network.Site
import io.github.nolte.kamerplanter.core.network.SpeciesCreateOutcome
import io.github.nolte.kamerplanter.core.network.SpeciesDraft
import io.github.nolte.kamerplanter.core.network.SpeciesEntry
import io.github.nolte.kamerplanter.feature.plants.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The add-a-plant form over fakes of its two seams: what it proposes, what it refuses, the
 * order it writes in, and what it reports when the photo does not make it (R19–R22, R28–R32).
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

    private fun viewModel() = PlantCaptureViewModel(capture, plants) { today }

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

        model.choosePlace("site-1", null)
        model.form()
        model.choosePlace("site-1", "loc-1")
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

        model.choosePlace("site-1", null)
        assertEquals(listOf("loc-1"), model.form().locations?.map { it.key })
        model.choosePlace("site-1", "loc-1")
        assertEquals("loc-1", model.form().inputs.locationKey)

        model.choosePlace(null, null)
        assertNull(model.form().inputs.locationKey)
        assertNull(model.form().locations)
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
        model.choosePlace("site-1", null)
        model.form()
        model.choosePlace("site-1", "loc-1")
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

    // --- fakes ----------------------------------------------------------------------------------

    private class FakeCaptureClient : PlantCaptureClient {
        var catalogue: Fetched<List<SpeciesEntry>> = Fetched.Loaded(CATALOGUE)
        var sites: Fetched<List<Site>> = Fetched.Loaded(listOf(Site("site-1", "Balcony")))
        var locations: Fetched<List<Location>> = Fetched.Loaded(listOf(Location("loc-1", "Left rail")))
        var taken: Fetched<Set<String>> = Fetched.Loaded(emptySet())
        var createOutcome: PlantCreateOutcome = PlantCreateOutcome.Created("p-9")
        val created = mutableListOf<PlantDraft>()

        override suspend fun identificationReadiness(): IdentificationReadiness =
            IdentificationReadiness.NotOffered
        override suspend fun grantIdentificationConsent(): ConsentOutcome = error("not under test")
        override suspend fun identify(jpeg: ByteArray, organ: String, language: String): IdentifyOutcome =
            error("not under test")
        override suspend fun selectSuggestion(requestKey: String, rank: Int): ActionOutcome =
            error("not under test")
        override suspend fun linkIdentification(requestKey: String, plantKey: String): ActionOutcome =
            error("not under test")
        override suspend fun catalogue(): Fetched<List<SpeciesEntry>> = catalogue
        override suspend fun createSpecies(draft: SpeciesDraft): SpeciesCreateOutcome =
            error("not under test")
        override suspend fun sites(): Fetched<List<Site>> = sites
        override suspend fun locations(siteKey: String): Fetched<List<Location>> = locations
        override suspend fun instanceIds(): Fetched<Set<String>> = taken
        override suspend fun createPlant(draft: PlantDraft): PlantCreateOutcome {
            created += draft
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
    }
}
