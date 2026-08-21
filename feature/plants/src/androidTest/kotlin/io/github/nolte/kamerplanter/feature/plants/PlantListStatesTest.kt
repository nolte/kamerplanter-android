package io.github.nolte.kamerplanter.feature.plants

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nolte.kamerplanter.core.network.CareAction
import io.github.nolte.kamerplanter.core.network.PlantSummary
import io.github.nolte.kamerplanter.core.network.URGENCY_OVERDUE
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * What issue #9 asks the *screen* to get right, rendered rather than reasoned about.
 *
 * The list's filter arithmetic is already covered off-device by the view-model tests. What no
 * unit test can see is whether four states a user must tell apart actually reach the screen as
 * four different things, and whether the care flag survives for someone who cannot use colour.
 * Those are rendering facts, so they are asserted here.
 *
 * Strings come from resources, never from literals: a hardcoded "No plants match" would pass
 * in English and turn a locale switch into a mystery failure. Plant names are the exception —
 * those are data this test invents, so they are literals on purpose.
 */
@RunWith(JUnit4::class)
class PlantListStatesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val actions = PlantListActions(
        onRetry = {},
        onOpenSettings = {},
        onOpenPlant = {},
        onFilterChange = {},
    )

    private fun plant(
        key: String,
        name: String,
        species: String? = null,
        location: String? = null,
        careAction: CareAction? = null,
        isRemoved: Boolean = false,
    ) = PlantSummary(
        key = key,
        displayName = name,
        species = species,
        location = location,
        thumbnailUrl = null,
        careAction = careAction,
        isRemoved = isRemoved,
    )

    /**
     * Renders the screen once and hands back a setter.
     *
     * `setContent` may only be called once per test, so a test that needs to compare two
     * states drives them through a `mutableStateOf` instead of composing twice — which is
     * closer to what actually happens on device anyway: the same screen changing state, not a
     * new screen being built.
     */
    private fun showChangeable(initial: PlantListState): (PlantListState) -> Unit {
        var state by mutableStateOf(initial)
        composeRule.setContent { PlantsContent(state = state, actions = actions) }
        return { state = it }
    }

    private fun show(state: PlantListState) {
        composeRule.setContent { PlantsContent(state = state, actions = actions) }
    }

    /**
     * The two empty states issue #9 requires to read differently.
     *
     * The point is not that each says *something* — it is that they do not say the same thing.
     * "The instance holds no plants" and "your filter matched nothing" are indistinguishable in
     * a list-plus-flag model, and they need opposite things from the user: one is nothing to
     * do, the other is a narrowing to take back.
     */
    @Test
    fun anEmptyTenantAndAnEmptyFilterResultDoNotReadTheSame() {
        val setState = showChangeable(PlantListState.Empty)

        val emptyTenant = resources.getString(R.string.plants_empty_title)
        val noMatches = resources.getString(R.string.plants_filter_no_matches_title)

        composeRule.onNodeWithText(emptyTenant).assertIsDisplayed()
        composeRule.onNodeWithText(noMatches).assertDoesNotExist()

        setState(
            PlantListState.Content(
                plants = listOf(plant("p1", "Monstera", species = "Monstera deliciosa")),
                filter = PlantFilter(query = "nothing matches this"),
            ),
        )

        composeRule.onNodeWithText(noMatches).assertIsDisplayed()
        composeRule.onNodeWithText(emptyTenant).assertDoesNotExist()
    }

    /** A tenant whose plants are all removed gets its own answer, not "clear your filters". */
    @Test
    fun aTenantWhosePlantsAreAllRemovedIsToldThatRatherThanOfferedAFilterReset() {
        show(
            PlantListState.Content(
                plants = listOf(plant("p1", "Monstera", isRemoved = true)),
                filter = PlantFilter(),
            ),
        )

        composeRule
            .onNodeWithText(resources.getString(R.string.plants_only_removed_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(resources.getString(R.string.plants_filter_clear))
            .assertDoesNotExist()
    }

    /** Not connected is a route to Settings, not an error and not an empty list. */
    @Test
    fun beingDisconnectedExplainsItselfAndOffersTheWayOut() {
        show(PlantListState.NotConnected)

        composeRule
            .onNodeWithText(resources.getString(R.string.plants_not_connected_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(resources.getString(R.string.plants_not_connected_action))
            .assertIsDisplayed()
    }

    /**
     * The care flag, for someone who cannot use colour (#9: "conveyed by more than colour
     * alone", WCAG 1.4.1).
     *
     * Asserted through the accessibility tree rather than by looking for a coloured shape:
     * that is the channel a screen reader uses, and it is the one that would silently vanish
     * if the badge were ever reduced to a tinted dot.
     */
    @Test
    fun anOverdueCareActionIsAnnouncedInWordsNotOnlyInColour() {
        show(
            PlantListState.Content(
                plants = listOf(
                    plant(
                        key = "p1",
                        name = "Monstera",
                        careAction = CareAction(
                            kind = "watering",
                            urgency = URGENCY_OVERDUE,
                            dueDate = null,
                        ),
                    ),
                ),
            ),
        )

        val spokenTask = resources.getString(R.string.plants_care_watering)
        composeRule.onNodeWithContentDescription(spokenTask, substring = true).assertIsDisplayed()
    }

    /** Removed plants stay hidden until the user asks for them — the default is not a filter. */
    @Test
    fun removedPlantsAreHiddenByDefaultAndAppearWhenAskedFor() {
        val plants = listOf(
            plant("p1", "Monstera"),
            plant("p2", "Dead Ficus", isRemoved = true),
        )
        val setState = showChangeable(PlantListState.Content(plants = plants, filter = PlantFilter()))

        composeRule.onNodeWithText("Monstera").assertIsDisplayed()
        composeRule.onNodeWithText("Dead Ficus").assertDoesNotExist()

        setState(PlantListState.Content(plants = plants, filter = PlantFilter(includeRemoved = true)))

        composeRule.onNodeWithText("Dead Ficus").assertIsDisplayed()
    }
}
