package io.github.nolte.kamerplanter.feature.plants

import io.github.nolte.kamerplanter.core.network.PlantSummary

/**
 * What the Plants tab can be showing.
 *
 * The states are deliberately distinct rather than one nullable list with flags: "we have not
 * asked yet", "there is no instance to ask", "the instance has no plants" and "asking failed"
 * look identical in a list-plus-boolean model, and each of them needs the user to do
 * something different — or nothing at all.
 */
sealed interface PlantListState {

    /** Before the first load, and during a reload triggered by reconnecting. */
    data object Loading : PlantListState

    /**
     * No instance is connected. Nothing is fetched or shown in this state, and reaching it
     * from [Content] discards whatever was on screen — plant data belongs to a connection.
     */
    data object NotConnected : PlantListState

    /** The instance answered, and this tenant holds no plants at all. */
    data object Empty : PlantListState

    /**
     * The tenant's plants, and what the user has narrowed them down to.
     *
     * [plants] is everything the instance returned, [visible] what survives [filter]. Both,
     * because the filter row has to keep working when nothing matches: a state that carried
     * only the visible rows would leave the user looking at an empty screen with no way back
     * to the full list.
     *
     * "Nothing matches" therefore lives inside this state rather than beside it — it is a
     * filtered list that happens to be empty, not a different screen — while [Empty] stays
     * separate because a tenant with no plants has nothing to filter.
     */
    data class Content(
        val plants: List<PlantSummary>,
        val filter: PlantFilter = PlantFilter(),
    ) : PlantListState {

        val visible: List<PlantSummary> get() = plants.applyFilter(filter)

        /** What the filter row can offer, over the plants the removed toggle admits. */
        val options: PlantFilterOptions get() = plants.filterOptions(filter.includeRemoved)

        /**
         * Every plant this tenant holds has been removed, and nothing was narrowed.
         *
         * Its own case because the way out is the opposite of the usual one: there is no
         * filter to clear, and offering "clear filters" here would be a button that changes
         * nothing. What helps is the removed toggle — the default that hid them is doing
         * exactly what it should, and the user just has to be told so.
         */
        val onlyRemoved: Boolean get() = visible.isEmpty() && !filter.isActive && plants.isNotEmpty()
    }

    /**
     * The load failed in a way a retry might fix.
     *
     * [credentialRejected] separates the one failure a retry cannot fix: the stored
     * credential was refused, so the user has to reconnect in Settings rather than press
     * "try again" against a door that will not open.
     */
    data class Failed(val credentialRejected: Boolean) : PlantListState
}
