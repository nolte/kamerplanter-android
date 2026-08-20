package io.github.nolte.kamerplanter.core.network

/**
 * One plant, as its own page needs it.
 *
 * Separate from [PlantSummary] because the two answer different questions. A row answers
 * "which plant is this"; a page answers "what is going on with it" — when it was planted, what
 * it sits in, which phase it is in, and whether it is still in the garden at all. Reading the
 * page's fields off a list row would mean the list carrying master data no row displays.
 *
 * The joins the row already does are done here too — the location is a resolved name, never a
 * key — because a page that printed `loc-3` would be the same defect the list was fixed for.
 */
data class PlantDetail(
    val key: String,
    /** `plant_name` where the instance has one, else its `instance_id` — never blank. */
    val displayName: String,
    /** Common name where known, else the scientific one, with the cultivar appended. */
    val species: String?,
    /** Resolved from `location_key`; `null` where the plant sits nowhere named. */
    val location: String?,
    /** ISO date the instance recorded, or absent where it never did. */
    val plantedOn: String?,
    /**
     * How this plant left the garden, or `null` while it is still in it.
     *
     * Its own type rather than three nullable fields, because the three only ever mean
     * anything together: a termination cause without a removal date describes nothing.
     */
    val removal: PlantRemoval?,
    /** The phase the instance reports, with how long it has been in it. */
    val phase: PlantPhase?,
    val containerVolumeLiters: Double?,
    /** The substrate the grower overrode this plant to, where they did. */
    val substrate: String?,
    /** `annual`, `perennial`, … — the instance's own vocabulary, not this app's. */
    val cultivationCycle: String?,
    /**
     * The plant this one was propagated from, where the grower recorded it.
     *
     * Carried as the key it is: resolving it would mean a second lookup for a line of text,
     * and the page shows it as a lineage hint rather than as a link.
     */
    val motherKey: String?,
)

/** When a plant left the garden, and how. */
data class PlantRemoval(
    val removedOn: String,
    /** `harvest`, `loss`, … — shown as the instance names it. */
    val type: String?,
    /** Why, for an unplanned loss; absent for a planned end. */
    val cause: String?,
)

/** A growth phase, current or past. */
data class PlantPhase(
    /** The instance's own phase name; this app never enumerates the vocabulary. */
    val name: String,
    /** When the plant entered it, as the instance writes it. */
    val startedAt: String?,
    /** When it left, or `null` for the phase it is in now. */
    val endedAt: String? = null,
)

/** One photo of a plant, in the shape a gallery needs. */
data class PlantPhoto(
    /** Absolute, and tenant-scoped: the loader has to sign it (see [AuthenticatedImageClient]). */
    val url: String,
    val isCover: Boolean,
)

/**
 * What one section of the page came back with.
 *
 * Generic because every section needs the same three answers and nothing more. Six bespoke
 * outcome types would be six places to add a case to, and the sections differ in what they
 * carry rather than in how they can fail.
 *
 * The distinction that earns its keep is [Unauthorized] against [Unavailable]: the first ends
 * the page — the credential is gone and no section will load — while the second is one section
 * offering its own retry beside five that loaded.
 */
sealed interface SectionOutcome<out T> {

    data class Loaded<T>(val value: T) : SectionOutcome<T>

    /** The stored credential was refused, or does not cover this tenant. */
    data object Unauthorized : SectionOutcome<Nothing>

    /** No such plant on this instance — a stale link, or one removed since. */
    data object NotFound : SectionOutcome<Nothing>

    data class Unavailable(val reason: String) : SectionOutcome<Nothing>
}

/**
 * Reads everything a single plant's page shows.
 *
 * One call per section rather than one call that returns all of them, because the page loads
 * them independently: a failing phase history must cost the phase section and nothing else,
 * and a combined call could only fail as a whole (#11).
 *
 * A seam rather than a concrete class, for the same reason [PlantsClient] is one — the feature
 * module never sees a networking type.
 */
interface PlantPageClient {

    /** The plant itself: master data, phase, location, removal. */
    suspend fun plant(key: String): SectionOutcome<PlantDetail>

    /** Its photos, cover first. */
    suspend fun photos(key: String): SectionOutcome<List<PlantPhoto>>

    /** Past phases, newest first; the current one is on [PlantDetail]. */
    suspend fun phaseHistory(key: String): SectionOutcome<List<PlantPhase>>

    /** The open care actions for this plant, most pressing first. */
    suspend fun care(key: String): SectionOutcome<List<CareAction>>
}
