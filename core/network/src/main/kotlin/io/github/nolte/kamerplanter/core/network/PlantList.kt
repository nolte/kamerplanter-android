package io.github.nolte.kamerplanter.core.network

/**
 * One row of the plant list, in the shape the screen needs rather than the shape the API
 * returns.
 *
 * App-owned on purpose: this is what crosses out of `:core:network`, so no generated DTO and
 * no Retrofit type has to (ADR 0001, R-GEN-5). It is also where the joins already happened —
 * the location name is resolved, the care flag is attached — so a row renders without the UI
 * knowing that three endpoints were involved.
 */
data class PlantSummary(
    /** Addresses the instance in every later call; not shown. */
    val key: String,
    /** `plant_name` where the instance has one, else its `instance_id` — never blank. */
    val displayName: String,
    /** Common name where known, else the scientific one, with the cultivar appended. */
    val species: String?,
    /** Resolved from `location_key`; `null` when the plant sits nowhere or the name is unknown. */
    val location: String?,
    /** Small thumbnail of the cover photo, already absolute; `null` when there is no photo. */
    val thumbnailUrl: String?,
    /** The open care action, when the dashboard reports one for this plant. */
    val careAction: CareAction?,
    /**
     * The instance's own name for the growth phase, or `null` where it reports none.
     *
     * Carried as the backend's string rather than an enum: a phase vocabulary belongs to the
     * grower's setup, not to this app, and a phase it has never heard of must still be
     * filterable.
     */
    val phase: String? = null,
    /**
     * Whether the plant has been removed from the garden.
     *
     * Carried rather than filtered out at the source. Removed plants were dropped in the
     * client, which made "show them anyway" impossible to offer — the list could not opt back
     * into data it never received.
     */
    val isRemoved: Boolean = false,
)

/**
 * An open care action, reduced to what a badge needs.
 *
 * [kind] is the backend's own `reminder_type` string rather than an enum: a server one
 * release ahead will name a kind this build has never heard of, and a row that renders
 * "unknown care action" is better than a list that fails to load (R-COMPAT-3).
 */
data class CareAction(
    val kind: String,
    /** The backend's `urgency`; drives how prominent the badge is, never its only signal. */
    val urgency: String,
    /** ISO `yyyy-MM-dd` as the backend writes it, or absent for a reminder with no date. */
    val dueDate: String?,
) {
    /** Whether this one is late rather than merely scheduled. */
    val isOverdue: Boolean get() = urgency == URGENCY_OVERDUE
}

/**
 * The one `urgency` value the app treats specially.
 *
 * Everything else — `upcoming` today, whatever a later release adds — is a scheduled task and
 * is shown calmly. Reading it the other way round would mean a new value arriving as an alarm.
 */
const val URGENCY_OVERDUE: String = "overdue"

/**
 * The outcome of a load, in terms the UI can act on.
 *
 * A sealed type rather than [Result], because the two failures lead somewhere different:
 * [Unauthorized] means the stored credential no longer works and the user has to reconnect,
 * while [Unavailable] is worth a retry button. `Result` could only carry a throwable, and
 * the screen would be left pattern-matching on exception types.
 */
sealed interface PlantListOutcome {

    data class Loaded(val plants: List<PlantSummary>) : PlantListOutcome

    /** The stored credential was refused — the connection needs re-establishing. */
    data object Unauthorized : PlantListOutcome

    /** Anything else: unreachable instance, server error, malformed answer. */
    data class Unavailable(val reason: String) : PlantListOutcome
}

/**
 * Reads the connected user's plant instances.
 *
 * A seam rather than a concrete class so the list can be driven from tests without HTTP, and
 * so `:feature:plants` never sees a networking type.
 */
interface PlantsClient {

    /**
     * Every plant instance the tenant holds, already joined with locations, cover photos and
     * open care actions, sorted by display name.
     *
     * Removed instances are included and marked as such rather than dropped here. A list that
     * shows dead plants alongside living ones answers the wrong question — but that is the
     * screen's decision to make, and it cannot offer "show them anyway" over data it was
     * never given.
     */
    suspend fun loadPlants(): PlantListOutcome
}
