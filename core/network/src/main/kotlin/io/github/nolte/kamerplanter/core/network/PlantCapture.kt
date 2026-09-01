package io.github.nolte.kamerplanter.core.network

import java.time.LocalDate

/**
 * Adding a plant (R-8, issue #50): what the instance offers, what it identifies, and what it
 * takes to create one.
 *
 * Two routes end in one form. Identification only fills fields in — nothing is created
 * before the form is confirmed (R14) — and the manual route is that same form with nothing
 * filled in. Everything here is app-owned; no generated type crosses this boundary (R34).
 */

/** Whether the identification route can be offered at all (R3, R5, R6). */
sealed interface IdentificationReadiness {

    /** The recogniser answers, and the consent it needs is in place. */
    data object Ready : IdentificationReadiness

    /**
     * The photo would leave the instance for a third party and the user has not agreed to
     * that. [terms] is the instance's own wording, shown verbatim, `null` only where it
     * supplied none.
     */
    data class ConsentRequired(val terms: ConsentTerms?) : IdentificationReadiness

    /** The recogniser is not available on this instance; the manual route remains (R1). */
    data object NotOffered : IdentificationReadiness

    data object NotConnected : IdentificationReadiness

    data object Unauthorized : IdentificationReadiness

    /** Unreachable, or an answer this build could not read. */
    data class Unavailable(val reason: String) : IdentificationReadiness
}

/** One ranked candidate from the recogniser (R13). */
data class Suggestion(
    val rank: Int,
    val scientificName: String,
    val commonNames: List<String>,
    /** `0..1`. */
    val confidence: Double,
    val genus: String?,
    /** The catalogue entry this candidate already matches, where one exists (R25). */
    val matchedSpeciesKey: String?,
    /** A display hint and nothing more — never an instruction to skip the form (R14). */
    val autoAccept: Boolean,
) {
    val speciesInDatabase: Boolean get() = matchedSpeciesKey != null
}

sealed interface IdentifyOutcome {

    /**
     * The recogniser answered. [isPlant] `false`, an empty [suggestions] list, and a list of
     * weak candidates are three different things the screen tells apart (R15); [requestKey]
     * is what a selection and a later link refer to, and is absent when nothing was ranked.
     */
    data class Identified(
        val requestKey: String?,
        val isPlant: Boolean,
        val suggestions: List<Suggestion>,
        val message: String?,
    ) : IdentifyOutcome

    /** The image was not accepted; [reason] says why in the instance's own words. */
    data class Refused(val reason: String) : IdentifyOutcome

    /** The credential authenticated but the consent is not on record (R5). */
    data object ConsentMissing : IdentifyOutcome

    data object Unauthorized : IdentifyOutcome

    /** A role the account lacks; re-pairing cannot help. */
    data object NotPermitted : IdentifyOutcome

    /** The instance or its recogniser asked for a pause; [retryAfterSeconds] where it said. */
    data class RateLimited(val retryAfterSeconds: Long?) : IdentifyOutcome

    /** Unreachable, or an answer this build could not read. */
    data class Unavailable(val reason: String) : IdentifyOutcome
}

/** A catalogue entry the species field searches (R18). */
data class SpeciesEntry(
    val key: String,
    val scientificName: String,
    val commonNames: List<String>,
)

data class Site(val key: String, val name: String)

data class Location(val key: String, val name: String)

/**
 * A read the form needs before it can be filled in.
 *
 * One shape for the catalogue, the sites, a site's locations and the identifiers already
 * taken: they fail the same three ways, and the form treats them the same way.
 */
sealed interface Fetched<out T> {
    data class Loaded<T>(val value: T) : Fetched<T>
    data object NotConnected : Fetched<Nothing>
    data object Unauthorized : Fetched<Nothing>
    data class Failed(val reason: String) : Fetched<Nothing>
}

/** What `POST /species` needs for a match the catalogue does not carry (R25). */
data class SpeciesDraft(
    val scientificName: String,
    val commonNames: List<String>,
    val genus: String?,
)

sealed interface SpeciesCreateOutcome {
    data class Created(val key: String) : SpeciesCreateOutcome

    /** The route's own 409 — a unique constraint other than the normalised name (R26). */
    data object Conflict : SpeciesCreateOutcome

    data object Unauthorized : SpeciesCreateOutcome

    /** A `viewer` may not create species; undeclared on the route, recognised by status (R27). */
    data object NotPermitted : SpeciesCreateOutcome

    data class Failed(val reason: String) : SpeciesCreateOutcome
}

/** The fields the form offers (R22–R24); everything else is sent as `null`. */
data class PlantDraft(
    val instanceId: String,
    val speciesKey: String,
    val plantedOn: LocalDate,
    val plantName: String?,
    val siteKey: String?,
    val locationKey: String?,
)

sealed interface PlantCreateOutcome {
    data class Created(val plantKey: String) : PlantCreateOutcome

    /** The instance objected to a field; [reason] names it in the instance's words. */
    data class Rejected(val reason: String) : PlantCreateOutcome

    data object Unauthorized : PlantCreateOutcome

    data object NotPermitted : PlantCreateOutcome

    data class Failed(val reason: String) : PlantCreateOutcome
}

/**
 * The instance's side of adding a plant. Implemented in this module, faked in features.
 *
 * Eleven calls because the form makes eleven: one seam per screen, as the other clients
 * here, rather than a split along a line the screen would have to stitch back together.
 */
@Suppress("TooManyFunctions")
interface PlantCaptureClient {

    /** Whether the identification route can be offered, and on what consent it waits (R3). */
    suspend fun identificationReadiness(): IdentificationReadiness

    /** Records the `plant_identification` consent on the instance (R6). */
    suspend fun grantIdentificationConsent(): ConsentOutcome

    /**
     * Sends [jpeg] to the recogniser. [organ] is `auto` unless a weak result asked for a
     * hint (R16); [language] is the one the app's interface speaks.
     */
    suspend fun identify(jpeg: ByteArray, organ: String, language: String): IdentifyOutcome

    /** Persists the user's choice among the candidates (R13). */
    suspend fun selectSuggestion(requestKey: String, rank: Int): ActionOutcome

    /** Ties the identification to the plant it produced; best effort (R31). */
    suspend fun linkIdentification(requestKey: String, plantKey: String): ActionOutcome

    /** The whole catalogue: the route offers no search, so the search is client-side (R18). */
    suspend fun catalogue(): Fetched<List<SpeciesEntry>>

    suspend fun createSpecies(draft: SpeciesDraft): SpeciesCreateOutcome

    suspend fun sites(): Fetched<List<Site>>

    suspend fun locations(siteKey: String): Fetched<List<Location>>

    /** The identifiers already in use, for the best-effort uniqueness check (R21). */
    suspend fun instanceIds(): Fetched<Set<String>>

    suspend fun createPlant(draft: PlantDraft): PlantCreateOutcome
}

/** The organs the recogniser can be told to look at, once a weak result made it worth asking. */
enum class PlantOrgan(val wire: String) {
    AUTO("auto"),
    LEAF("leaf"),
    FLOWER("flower"),
    FRUIT("fruit"),
    BARK("bark"),
    HABIT("habit"),
}

/** The consent purpose the identification route waits on (R5). */
const val PLANT_IDENTIFICATION_CONSENT: String = "plant_identification"
