package io.github.nolte.kamerplanter.core.network

/**
 * One diary entry, reduced to what a plant's page shows.
 *
 * App-owned rather than the generated `DiaryEntryResponse`, like [PlantSummary]: the response
 * carries twenty-odd fields, most of them about an analysis pipeline this screen does not
 * drive, and none of them may cross out of `:core:network` (ADR 0001, R-GEN-5).
 */
data class DiaryEntry(
    val key: String,
    /** The backend's `entry_type`; a kind this build has not heard of still renders. */
    val kind: String,
    val title: String?,
    val text: String,
    /** ISO-8601 as the backend writes it; the screen decides how to say it. */
    val createdAt: String?,
    /** Ready-to-load thumbnail URLs, in stored order; already absolute. */
    val photoUrls: List<String> = emptyList(),
    /** What the instance's own sensors read when the entry was written. */
    val environment: List<EnvironmentReading> = emptyList(),
    /**
     * Why [environment] is empty, when it is.
     *
     * Worth carrying rather than inferring: "the plant's location has no sensor" and "the
     * writer chose not to record any" are different facts, and an empty list says neither.
     */
    val environmentStatus: String? = null,
)

/**
 * One sensor value attached to a diary entry.
 *
 * Recorded by the instance, not by the phone: it reads the plant's location sensors — or the
 * weather for an outdoor site — at the moment the entry is written. The app asks for it and
 * displays it; it never measures anything itself.
 */
data class EnvironmentReading(
    /** `temperature`, `humidity`, … — the backend's own vocabulary. */
    val metric: String,
    val value: Double,
    val unit: String?,
    /** `location`, `site` or `weather`; where the reading came from. */
    val origin: String?,
)

/** The outcome of reading a plant's diary. */
sealed interface DiaryOutcome {

    data class Loaded(val entries: List<DiaryEntry>) : DiaryOutcome

    /** The stored credential was refused; a retry cannot fix it. */
    data object Unauthorized : DiaryOutcome

    data class Unavailable(val reason: String) : DiaryOutcome
}

/**
 * The outcome of an action taken on a plant.
 *
 * [Failed] carries a reason rather than a bare boolean because these actions fail for reasons
 * the user can act on and cannot guess: confirming a watering that the instance has no open
 * reminder for is a different problem from an instance that cannot be reached, and "could not
 * save" covers both while helping with neither.
 */
sealed interface ActionOutcome {

    data object Done : ActionOutcome

    data class Failed(val reason: String) : ActionOutcome
}

/**
 * What a plant's page can do to the plant, as opposed to what it can read about it.
 *
 * Separate from [PlantsClient] because the list and the detail page have different lifetimes
 * and different failure modes: a list that cannot load shows nothing, while an action that
 * fails leaves a page that is still perfectly usable and needs to say so in place.
 */
interface PlantActionsClient {

    /** The plant's diary, newest first. */
    suspend fun diary(plantKey: String): DiaryOutcome

    /**
     * Writes a diary entry, with photos and the instance's own sensor readings.
     *
     * Filed as `note` — the backend's neutral kind. Observations, problems and milestones are
     * the same call with a different `entry_type`, and choosing between them is a decision the
     * page does not yet ask the user to make.
     *
     * [text] must not be blank. The endpoint declares `minLength: 1`, so an entry carrying
     * only photos is refused with a 422 — reasonable as UX, and not what the contract says.
     *
     * [photos] are JPEG bytes: each is uploaded first and the entry references what came back,
     * because the diary endpoint takes attachment ids, not images. An upload that fails takes
     * the whole entry with it rather than silently filing a note about a photo that is not
     * there.
     *
     * [captureEnvironment] asks the instance to attach what its sensors read. Defaulted to the
     * backend's own default so that leaving it alone changes nothing, and offered at all
     * because the backend has a state for declining — an entry written somewhere the readings
     * would be meaningless should be allowed to say so.
     */
    suspend fun addNote(
        plantKey: String,
        text: String,
        photos: List<ByteArray> = emptyList(),
        captureEnvironment: Boolean = true,
    ): ActionOutcome

    /**
     * Records a care task as done — watering, fertilising, a pest check.
     *
     * [kind] is the backend's `reminder_type`, the same string [CareAction.kind] carries, so
     * the badge the user is looking at and the button that clears it name the same thing.
     */
    suspend fun confirmCare(plantKey: String, kind: String): ActionOutcome
}
