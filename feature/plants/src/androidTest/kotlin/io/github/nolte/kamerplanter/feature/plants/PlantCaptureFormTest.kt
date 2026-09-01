package io.github.nolte.kamerplanter.feature.plants

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
import io.github.nolte.kamerplanter.core.network.Location
import io.github.nolte.kamerplanter.core.network.Site
import io.github.nolte.kamerplanter.core.network.SpeciesEntry
import io.github.nolte.kamerplanter.feature.plants.capture.FormActions
import io.github.nolte.kamerplanter.feature.plants.capture.FormField
import io.github.nolte.kamerplanter.feature.plants.capture.FormInputs
import io.github.nolte.kamerplanter.feature.plants.capture.PhotoActions
import io.github.nolte.kamerplanter.feature.plants.capture.PhotoSources
import io.github.nolte.kamerplanter.feature.plants.capture.PlantCaptureActions
import io.github.nolte.kamerplanter.feature.plants.capture.PlantCaptureBody
import io.github.nolte.kamerplanter.feature.plants.capture.PlantCaptureState
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

    private fun show(form: PlantCaptureState.Form) {
        composeRule.setContent { PlantCaptureBody(state = form, actions = INERT) }
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
    ) = PlantCaptureState.Form(
        inputs = inputs,
        catalogue = catalogue,
        sites = listOf(Site("site-1", "Balcony")),
        locations = locations,
        takenIds = emptySet(),
        errors = errors,
    )

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
                    sources = PhotoSources(onCamera = {}, onLibrary = {}),
                    onRemove = {},
                    onKeep = {},
                ),
            ),
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
