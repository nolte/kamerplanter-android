package io.github.nolte.kamerplanter.feature.plants

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nolte.kamerplanter.core.camera.CapturedImage
import io.github.nolte.kamerplanter.core.network.ConsentTerms
import io.github.nolte.kamerplanter.core.network.IdentificationReadiness
import io.github.nolte.kamerplanter.core.network.Location
import io.github.nolte.kamerplanter.core.network.PlantOrgan
import io.github.nolte.kamerplanter.core.network.Site
import io.github.nolte.kamerplanter.core.network.SpeciesDraft
import io.github.nolte.kamerplanter.core.network.SpeciesEntry
import io.github.nolte.kamerplanter.core.network.Suggestion
import io.github.nolte.kamerplanter.feature.plants.capture.FormActions
import io.github.nolte.kamerplanter.feature.plants.capture.FormField
import io.github.nolte.kamerplanter.feature.plants.capture.FormInputs
import io.github.nolte.kamerplanter.feature.plants.capture.IdentificationActions
import io.github.nolte.kamerplanter.feature.plants.capture.IdentificationImage
import io.github.nolte.kamerplanter.feature.plants.capture.IdentificationStep
import io.github.nolte.kamerplanter.feature.plants.capture.PhotoActions
import io.github.nolte.kamerplanter.feature.plants.capture.PhotoSources
import io.github.nolte.kamerplanter.feature.plants.capture.PlantCaptureActions
import io.github.nolte.kamerplanter.feature.plants.capture.PlantCaptureBody
import io.github.nolte.kamerplanter.feature.plants.capture.PlantCaptureState
import io.github.nolte.kamerplanter.feature.plants.capture.WeakResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.time.LocalDate

/**
 * What the add-a-plant form shows (F-12): the pre-filled proposal, the refusal per field, the
 * site → location dependency, and the one entry point the Plants tab offers only while
 * connected. Rendered from states directly, as the list tests are — the view-model suite owns
 * what the states *are*.
 */
@RunWith(JUnit4::class)
class PlantCaptureFormTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    /**
     * Content may be set once per test, so a test that walks through several states swaps
     * the state under one composition instead.
     */
    private val shown = mutableStateOf<PlantCaptureState.Form?>(null)

    private fun confidence(percent: Int) = resources.getString(R.string.plants_add_suggestion_confidence, percent)

    private fun show(form: PlantCaptureState.Form) {
        if (shown.value == null) {
            shown.value = form
            composeRule.setContent { PlantCaptureBody(state = checkNotNull(shown.value), actions = INERT) }
        } else {
            shown.value = form
            composeRule.waitForIdle()
        }
    }

    /** F-12 acceptance-8: the proposed identifier and date are on screen, editable. */
    @Test
    fun theProposedIdentifierAndDateArePreFilled() {
        show(
            form(inputs = FormInputs(speciesKey = "sp-1", speciesQuery = "Monstera deliciosa", instanceId = "MON_01")),
        )

        composeRule.onNodeWithText("MON_01").assertIsDisplayed()
        composeRule.onNodeWithText("Monstera deliciosa").assertIsDisplayed()
    }

    /** F-12 acceptance-8: a refused submission names every field it was refused on. */
    @Test
    fun aRefusedSubmissionNamesEachMissingField() {
        show(form(errors = setOf(FormField.SPECIES, FormField.INSTANCE_ID, FormField.PLANTED_ON)))

        composeRule.onNodeWithText(resources.getString(R.string.plants_add_species_required)).assertIsDisplayed()
        composeRule.onNodeWithText(resources.getString(R.string.plants_add_instance_id_required)).assertIsDisplayed()
        composeRule.onNodeWithText(resources.getString(R.string.plants_add_planted_on_required)).assertIsDisplayed()
    }

    /** F-12 acceptance-9: no location before a site, and the site's own locations after. */
    @Test
    fun aLocationCanOnlyBeChosenUnderASite() {
        show(form())
        composeRule.onNodeWithText(resources.getString(R.string.plants_add_location_needs_site)).assertIsDisplayed()
        composeRule.onNode(hasText(resources.getString(R.string.plants_add_location))).assertIsNotEnabled()
    }

    @Test
    fun aChosenSiteEnablesItsLocations() {
        show(form(inputs = FormInputs(siteKey = "site-1"), locations = listOf(Location("loc-1", "Left rail"))))

        composeRule.onNode(hasText(resources.getString(R.string.plants_add_location))).assertIsEnabled()
        composeRule.onAllNodesWithText(resources.getString(R.string.plants_add_location_needs_site))
            .assertCountEquals(0)
    }

    /** F-12 acceptance-7: an empty catalogue is said, not shown as a search that never matches. */
    @Test
    fun anEmptyCatalogueIsSaidSo() {
        show(form(catalogue = emptyList()))

        composeRule.onNodeWithText(resources.getString(R.string.plants_add_species_empty)).assertIsDisplayed()
    }

    /** F-12 acceptance-1: the entry point exists while connected and not while disconnected. */
    @Test
    fun addingAPlantIsOfferedOnlyWhileConnected() {
        val label = resources.getString(R.string.plants_add_action)
        composeRule.setContent {
            PlantsContent(state = PlantListState.Empty, actions = LIST_ACTIONS)
        }
        composeRule.onNodeWithContentDescription(label).assertIsDisplayed()
    }

    @Test
    fun addingAPlantIsNotOfferedWhileDisconnected() {
        val label = resources.getString(R.string.plants_add_action)
        composeRule.setContent {
            PlantsContent(state = PlantListState.NotConnected, actions = LIST_ACTIONS)
        }
        composeRule.onAllNodesWithContentDescription(label).assertCountEquals(0)
    }

    private fun form(
        inputs: FormInputs = FormInputs(plantedOn = LocalDate.of(2026, 9, 2)),
        catalogue: List<SpeciesEntry> = listOf(MONSTERA),
        locations: List<Location>? = null,
        errors: Set<FormField> = emptySet(),
        identification: IdentificationReadiness? = null,
        step: IdentificationStep? = null,
    ) = PlantCaptureState.Form(
        inputs = inputs,
        catalogue = catalogue,
        sites = listOf(Site("site-1", "Balcony")),
        locations = locations,
        takenIds = emptySet(),
        errors = errors,
        identification = identification,
        step = step,
    )

    // --- the identification route (F-12 acceptance-3, 5, 6, 11) --------------------------------

    /** F-12 acceptance-2: the route is on the form only where the instance offers a recogniser. */
    @Test
    fun theIdentificationRouteIsOfferedOnlyWhereTheRecogniserIs() {
        show(form(identification = IdentificationReadiness.NotOffered))
        composeRule.onAllNodesWithText(resources.getString(R.string.plants_add_identify_action)).assertCountEquals(0)

        show(form(identification = IdentificationReadiness.ConsentRequired(null)))
        composeRule.onNodeWithText(resources.getString(R.string.plants_add_identify_action)).assertIsDisplayed()
    }

    /** F-12 acceptance-3: the consent is the instance's own wording, before any camera button. */
    @Test
    fun theConsentIsAskedInTheInstancesOwnWordsBeforeTheCamera() {
        val terms = ConsentTerms(
            label = "Pflanzenbestimmung",
            description = "Das Foto geht an Pl@ntNet.",
            legalBasis = "Art. 6 Abs. 1 lit. a DSGVO",
        )
        show(form(step = IdentificationStep.Consent(terms)))

        composeRule.onNodeWithText("Pflanzenbestimmung").assertIsDisplayed()
        composeRule.onNodeWithText("Das Foto geht an Pl@ntNet.").assertIsDisplayed()
        composeRule.onNodeWithText(resources.getString(R.string.plants_add_consent_action)).assertIsDisplayed()
        composeRule.onAllNodesWithText(resources.getString(R.string.plants_note_take_photo)).assertCountEquals(0)
    }

    /** F-12 acceptance-5: rank order with names and a confidence in words; the best match is labelled, not chosen. */
    @Test
    fun candidatesShowNamesAndConfidenceAndTheBestMatchIsOnlyLabelled() {
        val second = MONSTERA_GUESS.copy(
            rank = 2,
            scientificName = "Monstera adansonii",
            commonNames = emptyList(),
            confidence = 0.42,
            autoAccept = false,
        )
        show(
            form(
                step = IdentificationStep.Suggestions(
                    image = IMAGE,
                    requestKey = "req",
                    suggestions = listOf(MONSTERA_GUESS, second),
                    weak = null,
                    organ = PlantOrgan.AUTO,
                ),
            ),
        )

        composeRule.onNodeWithText("Swiss cheese plant").assertIsDisplayed()
        composeRule.onNodeWithText("Monstera deliciosa").assertIsDisplayed()
        composeRule.onNodeWithText(confidence(91)).assertIsDisplayed()
        composeRule.onNodeWithText("Monstera adansonii").assertIsDisplayed()
        composeRule.onNodeWithText(confidence(42)).assertIsDisplayed()
        composeRule.onNodeWithText(resources.getString(R.string.plants_add_suggestion_best)).assertIsDisplayed()
        // Still a list to choose from — no organ question on an ordinary answer.
        composeRule.onAllNodesWithText(resources.getString(R.string.plants_add_organ_question)).assertCountEquals(0)
    }

    /** F-12 acceptance-6: the three thin answers read differently, and each asks for the organ. */
    @Test
    fun theThreeWeakAnswersReadDifferentlyAndEachOffersTheOrganHint() {
        val titles = mapOf(
            WeakResult.NOT_A_PLANT to R.string.plants_add_not_a_plant_title,
            WeakResult.NOTHING_RECOGNISED to R.string.plants_add_nothing_recognised_title,
            WeakResult.LOW_CONFIDENCE to R.string.plants_add_low_confidence_title,
        )
        val weakGuess = listOf(MONSTERA_GUESS.copy(confidence = 0.12))
        titles.forEach { (weak, title) ->
            show(
                form(
                    step = IdentificationStep.Suggestions(
                        image = IMAGE,
                        requestKey = "req",
                        suggestions = if (weak == WeakResult.LOW_CONFIDENCE) weakGuess else emptyList(),
                        weak = weak,
                        organ = PlantOrgan.AUTO,
                    ),
                ),
            )
            composeRule.onNodeWithText(resources.getString(title)).assertIsDisplayed()
            titles.filterKeys { it != weak }.values.forEach { other ->
                composeRule.onAllNodesWithText(resources.getString(other)).assertCountEquals(0)
            }
            composeRule.onNodeWithText(resources.getString(R.string.plants_add_organ_question)).assertIsDisplayed()
            composeRule.onNodeWithText(resources.getString(R.string.plants_add_organ_leaf)).assertIsDisplayed()
            composeRule.onNodeWithText(resources.getString(R.string.plants_add_by_hand_keep_photo)).assertIsDisplayed()
        }
    }

    /** F-12 acceptance-11: a species the catalogue lacks is named in the field and announced as to-be-created. */
    @Test
    fun aSpeciesTheCatalogueLacksIsShownAsAboutToBeCreated() {
        show(
            form(
                inputs = FormInputs(
                    pendingSpecies = SpeciesDraft("Pilea peperomioides", listOf("Chinese money plant"), "Pilea"),
                    speciesQuery = "Pilea peperomioides",
                    instanceId = "PIL_01",
                ),
            ),
        )

        composeRule.onNodeWithText("Pilea peperomioides").assertIsDisplayed()
        composeRule.onNodeWithText(resources.getString(R.string.plants_add_species_pending)).assertIsDisplayed()
        composeRule.onNodeWithText(resources.getString(R.string.plants_add_submit)).assertIsEnabled()
    }

    private companion object {
        val MONSTERA = SpeciesEntry("sp-1", "Monstera deliciosa", listOf("Swiss cheese plant"))
        val INERT = PlantCaptureActions(
            onRetry = {},
            onOpenSettings = {},
            onOpenPlant = {},
            form = FormActions(
                onSearchSpecies = {},
                onChooseSpecies = {},
                onEditInstanceId = {},
                onEditPlantName = {},
                onEditPlantedOn = {},
                onChooseSite = {},
                onChooseLocation = {},
                onSubmit = {},
                photo = PhotoActions(
                    sources = PhotoSources(
                        onCamera = {},
                        onLibrary = {},
                        onIdentifyCamera = {},
                        onIdentifyLibrary = {},
                    ),
                    onRemove = {},
                    onKeep = {},
                ),
            ),
            identification = IdentificationActions(
                onStart = {},
                onLeave = {},
                onGrantConsent = {},
                onCamera = {},
                onLibrary = {},
                onRetake = {},
                onSend = {},
                onChoose = {},
                onContinueByHand = {},
            ),
        )
        val IMAGE = IdentificationImage(CapturedImage { _, _ -> null }, recognitionJpeg = byteArrayOf())
        val MONSTERA_GUESS = Suggestion(
            rank = 1,
            scientificName = "Monstera deliciosa",
            commonNames = listOf("Swiss cheese plant"),
            confidence = 0.91,
            genus = "Monstera",
            matchedSpeciesKey = "sp-1",
            autoAccept = true,
        )
        val LIST_ACTIONS = PlantListActions(
            onRetry = {},
            onOpenSettings = {},
            onOpenPlant = {},
            onFilterChange = {},
            onAddPlant = {},
        )
    }
}
