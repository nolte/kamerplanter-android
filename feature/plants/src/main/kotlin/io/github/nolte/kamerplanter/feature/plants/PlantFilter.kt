package io.github.nolte.kamerplanter.feature.plants

import io.github.nolte.kamerplanter.core.network.PlantSummary

/**
 * What the user has narrowed the list down to.
 *
 * Applied client-side over the loaded set, because `GET /plant-instances` accepts no filter
 * parameters at all — only `offset` and `limit`. That is a property of the endpoint rather
 * than a shortcut taken here, and it is why every dimension below works on data the app
 * already holds.
 *
 * The dimensions combine with AND. Two of them read as "narrow this down" and are off until
 * asked for; [includeRemoved] is the odd one out — it *widens* the list, and its default is
 * what makes the list answer "which plants do I have" rather than "which plants have I ever
 * had".
 */
data class PlantFilter(
    /** Free text, matched against the display name and the species. Blank means "no filter". */
    val query: String = "",
    /** A resolved location name, exactly as a row shows it. */
    val location: String? = null,
    val species: String? = null,
    /** The instance's own phase string; this app never enumerates the vocabulary. */
    val phase: String? = null,
    /** Only plants the care dashboard reports an open action for. */
    val needsAttention: Boolean = false,
    /**
     * Show plants that have been removed from the garden.
     *
     * Off by default and deliberately opt-in: a removed plant is a record, not a plant, and
     * mixing the two is what the list is for avoiding. Kept as a filter rather than as a
     * fetch parameter because the endpoint returns removed instances either way.
     */
    val includeRemoved: Boolean = false,
) {

    /** Whether anything is narrowed at all — drives whether "clear" is offered. */
    val isActive: Boolean
        get() = query.isNotBlank() ||
            location != null ||
            species != null ||
            phase != null ||
            needsAttention ||
            includeRemoved
}

/**
 * The values worth offering, taken from the plants actually loaded.
 *
 * Derived rather than fetched: `/locations` knows every location the tenant has, most of
 * which hold no plants, and a filter that offers a value matching nothing is a dead end the
 * user has to discover by trying it.
 */
data class PlantFilterOptions(
    val locations: List<String>,
    val species: List<String>,
    val phases: List<String>,
) {
    /** True where there is nothing to pick from at all — the dropdowns then stay hidden. */
    val isEmpty: Boolean get() = locations.isEmpty() && species.isEmpty() && phases.isEmpty()
}

/** The plants this filter admits, in the order they came in. */
fun List<PlantSummary>.applyFilter(narrowing: PlantFilter): List<PlantSummary> =
    filter { it.matches(narrowing) }

private fun PlantSummary.matches(narrowing: PlantFilter): Boolean {
    if (isRemoved && !narrowing.includeRemoved) return false
    if (narrowing.location != null && location != narrowing.location) return false
    if (narrowing.species != null && species != narrowing.species) return false
    if (narrowing.phase != null && phase != narrowing.phase) return false
    if (narrowing.needsAttention && careAction == null) return false
    return narrowing.query.isBlank() || matchesQuery(narrowing.query.trim())
}

/**
 * Name or species contains the query, case-insensitively.
 *
 * `ignoreCase` rather than lowercasing both sides against a locale: the comparison is between
 * two strings the user is looking at, and folding them through the device's locale would make
 * a Turkish phone answer differently about a plant called `İris` than a German one.
 */
private fun PlantSummary.matchesQuery(query: String): Boolean =
    displayName.contains(query, ignoreCase = true) ||
        species?.contains(query, ignoreCase = true) == true

/**
 * What the filter row can offer over this set.
 *
 * [includeRemoved] decides which plants contribute their values, so the dropdowns describe
 * the list the user is looking at: with removed plants hidden, a location only they sit in
 * would otherwise be offered and select nothing.
 *
 * Sorted alphabetically because no other order is meaningful here — the endpoint imposes
 * none, and a menu whose entries move between loads is one the user has to re-read every
 * time.
 */
fun List<PlantSummary>.filterOptions(includeRemoved: Boolean): PlantFilterOptions {
    val considered = if (includeRemoved) this else filter { !it.isRemoved }
    return PlantFilterOptions(
        locations = considered.distinctValues { it.location },
        species = considered.distinctValues { it.species },
        phases = considered.distinctValues { it.phase },
    )
}

private fun List<PlantSummary>.distinctValues(select: (PlantSummary) -> String?): List<String> =
    mapNotNull(select).filter { it.isNotBlank() }.distinct().sorted()
