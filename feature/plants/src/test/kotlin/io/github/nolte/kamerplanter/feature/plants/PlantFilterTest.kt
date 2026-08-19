package io.github.nolte.kamerplanter.feature.plants

import io.github.nolte.kamerplanter.core.network.CareAction
import io.github.nolte.kamerplanter.core.network.PlantSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The narrowing itself, away from Compose and from the network.
 *
 * Worth its own suite because every one of these rules is a decision about what the user
 * asked for: the endpoint offers no filtering at all, so whatever this file says is what
 * filtering means in this app.
 */
class PlantFilterTest {

    private val all = listOf(MONSTERA, FICUS, BASIL, GONE)

    @Test
    fun `a removed plant stays out until it is asked for`() {
        assertEquals(listOf(MONSTERA, FICUS, BASIL), all.applyFilter(PlantFilter()))
        assertEquals(all, all.applyFilter(PlantFilter(includeRemoved = true)))
    }

    @Test
    fun `search reads the name and the species`() {
        assertEquals(listOf(MONSTERA), all.applyFilter(PlantFilter(query = "monst")))
        assertEquals(listOf(MONSTERA), all.applyFilter(PlantFilter(query = "cheese")))
    }

    /** Typing is not a spelling test — the list is what the user is looking at. */
    @Test
    fun `search ignores case and surrounding blanks`() {
        assertEquals(listOf(FICUS), all.applyFilter(PlantFilter(query = "  FIcus ")))
    }

    /**
     * The location is a filter of its own, and a searchable one would collide with it: typing
     * "kitchen" would then hide a kitchen plant the moment the user *also* picked a location.
     */
    @Test
    fun `search does not reach the location`() {
        assertEquals(emptyList<PlantSummary>(), all.applyFilter(PlantFilter(query = "kitchen")))
    }

    @Test
    fun `each dimension narrows on its own`() {
        assertEquals(
            listOf(MONSTERA, FICUS),
            all.applyFilter(PlantFilter(location = "Living room")),
        )
        assertEquals(listOf(BASIL), all.applyFilter(PlantFilter(species = "Basil")))
        assertEquals(listOf(FICUS), all.applyFilter(PlantFilter(phase = "dormancy")))
        assertEquals(listOf(BASIL), all.applyFilter(PlantFilter(needsAttention = true)))
    }

    @Test
    fun `dimensions combine with and`() {
        assertEquals(
            emptyList<PlantSummary>(),
            all.applyFilter(PlantFilter(location = "Living room", needsAttention = true)),
        )
        assertEquals(
            listOf(FICUS),
            all.applyFilter(PlantFilter(location = "Living room", phase = "dormancy")),
        )
    }

    /**
     * The removed plant sits in a location no living plant does. Offering it while removed
     * plants are hidden would put an entry in the menu that selects an empty list.
     */
    @Test
    fun `the options describe the plants on screen`() {
        val visible = all.filterOptions(includeRemoved = false)
        assertEquals(listOf("Kitchen", "Living room"), visible.locations)
        assertEquals(listOf("Basil", "Rubber fig", "Swiss cheese plant"), visible.species)
        assertEquals(listOf("dormancy", "growth"), visible.phases)

        val withRemoved = all.filterOptions(includeRemoved = true)
        assertEquals(listOf("Balcony", "Kitchen", "Living room"), withRemoved.locations)
    }

    @Test
    fun `options hold no blanks and no duplicates`() {
        val options = listOf(MONSTERA, MONSTERA.copy(key = "dup"), NAMELESS).filterOptions(false)
        assertEquals(listOf("Living room"), options.locations)
        assertEquals(listOf("Swiss cheese plant"), options.species)
        assertEquals(listOf("growth"), options.phases)
    }

    /** Nothing to offer means nothing to show — the row hides its dropdowns on this. */
    @Test
    fun `options are empty when the plants carry none`() {
        assertTrue(listOf(NAMELESS).filterOptions(false).isEmpty)
    }

    /**
     * A garden whose plants are all gone is not a filter that missed. Told the wrong way, the
     * user is offered a "clear filters" button that changes nothing, because nothing is set.
     */
    @Test
    fun `all-removed is its own case, not an empty filter result`() {
        val state = PlantListState.Content(listOf(GONE))
        assertTrue(state.visible.isEmpty())
        assertTrue(state.onlyRemoved)

        val narrowed = PlantListState.Content(listOf(MONSTERA), PlantFilter(query = "nothing"))
        assertTrue(narrowed.visible.isEmpty())
        assertFalse("a filter that matched nothing is a different message", narrowed.onlyRemoved)

        val shown = PlantListState.Content(listOf(GONE), PlantFilter(includeRemoved = true))
        assertEquals(listOf(GONE), shown.visible)
        assertFalse(shown.onlyRemoved)
    }

    @Test
    fun `a filter is only active once something is narrowed`() {
        assertFalse(PlantFilter().isActive)
        assertFalse(PlantFilter(query = "   ").isActive)
        assertTrue(PlantFilter(query = "fern").isActive)
        assertTrue(PlantFilter(needsAttention = true).isActive)
        // Widening counts as active too: it is a deviation from the default the user has to
        // be able to see and undo, and "clear" has to reach it.
        assertTrue(PlantFilter(includeRemoved = true).isActive)
    }
}

private val MONSTERA = PlantSummary(
    key = "plant-1",
    displayName = "Monstera",
    species = "Swiss cheese plant",
    location = "Living room",
    thumbnailUrl = null,
    careAction = null,
    phase = "growth",
)

private val FICUS = PlantSummary(
    key = "plant-2",
    displayName = "Ficus",
    species = "Rubber fig",
    location = "Living room",
    thumbnailUrl = null,
    careAction = null,
    phase = "dormancy",
)

private val BASIL = PlantSummary(
    key = "plant-3",
    displayName = "Basil",
    species = "Basil",
    location = "Kitchen",
    thumbnailUrl = null,
    careAction = CareAction(kind = "watering", urgency = "overdue", dueDate = "2026-08-11"),
    phase = "growth",
)

/** Removed, and the only plant on the balcony — which is what makes the options assertion bite. */
private val GONE = PlantSummary(
    key = "plant-4",
    displayName = "Chili",
    species = "Chili pepper",
    location = "Balcony",
    thumbnailUrl = null,
    careAction = null,
    phase = "harvested",
    isRemoved = true,
)

/** Everything optional absent — the row an instance produces for a plant it knows nothing about. */
private val NAMELESS = PlantSummary(
    key = "plant-5",
    displayName = "AGLAO-0617-RB5",
    species = null,
    location = null,
    thumbnailUrl = null,
    careAction = null,
    phase = null,
)
