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
    /**
     * The attachment ids behind [photoUrls], in the same order.
     *
     * Carried because editing needs them: `PUT` takes `photo_refs`, so an entry edited without
     * them would be saved having quietly dropped every photo it had.
     */
    val photoRefs: List<String> = emptyList(),
    /** Whatever the writer tagged it with; the instance's own strings. */
    val tags: List<String> = emptyList(),
    /** What the instance's own sensors read when the entry was written. */
    val environment: List<EnvironmentReading> = emptyList(),
    /**
     * Why [environment] is empty, when it is.
     *
     * Worth carrying rather than inferring: "the plant's location has no sensor" and "the
     * writer chose not to record any" are different facts, and an empty list says neither.
     */
    val environmentStatus: String? = null,
    /**
     * Whether *this* reader may ask the instance to analyse this entry.
     *
     * Evaluated per entry by the backend, because it depends on authorship: in a shared garden
     * one page legitimately mixes entries that can be analysed with entries that cannot, and
     * caching one verdict for the list would offer the action where it 403s.
     */
    val canRequestAnalysis: Boolean = false,
    /** `pending`, `running`, `done`, … — the instance's own vocabulary for where it got to. */
    val analysisState: String? = null,
    /** What the analysis said, where it has said anything. */
    val analysis: String? = null,
)

/**
 * An entry on its way to the instance, whether it is new or a rewrite of an existing one.
 *
 * One shape for both because the endpoints take the same body — the difference is the verb —
 * and a separate "edit" type would be the same six fields with a second set of rules to keep
 * in step.
 *
 * [photoRefs] are attachments the instance already holds; [newPhotos] are JPEGs to upload
 * first. Both are needed for an edit: an entry saved with only the new ones would silently
 * lose the photos it was written with.
 */
data class DiaryDraft(
    /** One of the backend's `entry_type` values; [ENTRY_TYPE_NOTE] is the neutral one. */
    val entryType: String = ENTRY_TYPE_NOTE,
    /** Optional, and at most [DIARY_TITLE_MAX] characters. */
    val title: String? = null,
    /** Required by the endpoint: 1 to [DIARY_TEXT_MAX] characters. */
    val text: String,
    val photoRefs: List<String> = emptyList(),
    val newPhotos: List<ByteArray> = emptyList(),
    val tags: List<String> = emptyList(),
    /**
     * Asks the instance to attach what its sensors read.
     *
     * Only meaningful when creating: it tells the server whether to *look*, and an entry that
     * has already been written has already been looked at.
     */
    val captureEnvironment: Boolean = true,
)

/** The entry types the backend defines, in the order its own enum lists them. */
val DIARY_ENTRY_TYPES: List<String> = listOf(
    "observation",
    "problem",
    "milestone",
    "measurement",
    "photo",
    ENTRY_TYPE_NOTE,
)

/** The neutral kind, and what an entry with nothing else said about it is filed as. */
const val ENTRY_TYPE_NOTE: String = "note"

/** The endpoint's own limits, enforced in the editor so the instance never has to say no. */
const val DIARY_TITLE_MAX: Int = 200
const val DIARY_TEXT_MAX: Int = 5_000
const val DIARY_PHOTOS_MAX: Int = 5

/**
 * How many entries one page of the diary asks for.
 *
 * Well under the endpoint's own default of fifty: a plant's page shows the diary under five
 * other sections, and a reader who wants more says so.
 */
const val DIARY_PAGE_SIZE: Int = 20

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

    /**
     * One page of entries, newest first.
     *
     * [hasMore] is inferred from the page being full rather than from a count the endpoint
     * does not send. It can therefore be `true` for a diary whose entries happen to be an
     * exact multiple of the page size — one empty "load more" is the price of not asking the
     * instance for a total it does not offer.
     */
    data class Loaded(val entries: List<DiaryEntry>, val hasMore: Boolean = false) : DiaryOutcome

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
    suspend fun diary(
        plantKey: String,
        offset: Int = 0,
        limit: Int = DIARY_PAGE_SIZE,
    ): DiaryOutcome

    /**
     * Writes a diary entry, with photos and the instance's own sensor readings.
     *
     * [DiaryDraft.text] must not be blank. The endpoint declares `minLength: 1`, so an entry
     * carrying only photos is refused with a 422 — reasonable as UX, and not what the contract
     * says.
     *
     * [DiaryDraft.newPhotos] are JPEG bytes: each is uploaded first and the entry references
     * what came back, because the diary endpoint takes attachment ids, not images. An upload
     * that fails takes the whole entry with it rather than silently filing a note about a
     * photo that is not there.
     *
     * [DiaryDraft.captureEnvironment] asks the instance to attach what its sensors read.
     * Defaulted to the backend's own default so that leaving it alone changes nothing, and
     * offered at all because the backend has a state for declining — an entry written
     * somewhere the readings would be meaningless should be allowed to say so.
     */
    suspend fun addEntry(plantKey: String, draft: DiaryDraft): ActionOutcome

    /**
     * Rewrites an existing entry.
     *
     * The whole entry, not a patch: the endpoint is a `PUT` and replaces what it is given, so
     * a draft that omitted the photos would save an entry that has none. Analysis fields are
     * deliberately absent — `PUT` rejects them, and marking an entry for analysis has its own
     * call.
     */
    suspend fun updateEntry(plantKey: String, entryKey: String, draft: DiaryDraft): ActionOutcome

    /**
     * Removes an entry.
     *
     * An entry outside this plant or tenant answers 404 rather than 403, deliberately, so a
     * distinguishable "forbidden" cannot confirm that a key exists elsewhere. The app does not
     * reinterpret that 404 into something friendlier.
     */
    suspend fun deleteEntry(plantKey: String, entryKey: String): ActionOutcome

    /**
     * Asks the instance to analyse an entry.
     *
     * Its own call because the generic update refuses analysis fields, and only worth offering
     * where [DiaryEntry.canRequestAnalysis] says this reader may.
     */
    suspend fun requestAnalysis(plantKey: String, entryKey: String): ActionOutcome

    /**
     * Records a care task as done — watering, fertilising, a pest check.
     *
     * [kind] is the backend's `reminder_type`, the same string [CareAction.kind] carries, so
     * the badge the user is looking at and the button that clears it name the same thing.
     */
    suspend fun confirmCare(plantKey: String, kind: String): ActionOutcome
}
