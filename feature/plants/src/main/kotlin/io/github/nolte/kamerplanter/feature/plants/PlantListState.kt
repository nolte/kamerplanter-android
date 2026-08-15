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

    /** The instance answered, and this tenant holds no (non-removed) plants. */
    data object Empty : PlantListState

    data class Content(val plants: List<PlantSummary>) : PlantListState

    /**
     * The load failed in a way a retry might fix.
     *
     * [credentialRejected] separates the one failure a retry cannot fix: the stored
     * credential was refused, so the user has to reconnect in Settings rather than press
     * "try again" against a door that will not open.
     */
    data class Failed(val credentialRejected: Boolean) : PlantListState
}
