package io.github.nolte.kamerplanter.core.network

import android.util.Log
import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.connection.CredentialStore
import io.github.nolte.kamerplanter.core.network.generated.apis.PlantPhotosApi
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads and writes a single plant's diary and care confirmations.
 *
 * Raw JSON rather than the generated APIs, for the reason the care dashboard already uses:
 * `DiaryEntryResponse` carries an analysis pipeline's worth of fields, and deserialising all
 * of them means one entry with an `entry_type` this build has not heard of costs the whole
 * diary. Hand-read, an unknown kind costs nothing — it renders under its own name.
 */
@Singleton
class NetworkPlantActionsClient @Inject constructor(
    private val apis: InstanceApiFactory,
    private val connections: ConnectionStore,
    private val credentials: CredentialStore,
    private val changes: PlantDataChanges,
) : PlantActionsClient {

    override suspend fun diary(plantKey: String, offset: Int, limit: Int): DiaryOutcome =
        runCatchingCancellable {
            val (retrofit, tenant) = target()
            val page = retrofit.create(RawDiaryApi::class.java)
                .list(tenant, plantKey, offset, limit)
                .bodyOrThrow()
            DiaryOutcome.Loaded(
                entries = page.mapNotNull { it.asDiaryEntry(baseUrl(), tenant) },
                // A full page means there may be another. The endpoint sends no total, so the
                // alternative would be asking for one more entry than is shown on every load.
                hasMore = page.size >= limit,
            )
        }.getOrElse { failure ->
            when {
                failure is HttpFailure && failure.status in CREDENTIAL_REFUSED ->
                    DiaryOutcome.Unauthorized
                else -> DiaryOutcome.Unavailable(failure.describeAndLog())
            }
        }

    // ── The diary: read a page, write, rewrite, remove, and ask for an analysis ──────────

    override suspend fun addEntry(plantKey: String, draft: DiaryDraft): ActionOutcome =
        runCatchingCancellable {
            val (retrofit, tenant) = target()
            retrofit.create(RawDiaryApi::class.java)
                .create(tenant, plantKey, retrofit.asRequest(tenant, draft, forCreate = true))
                .bodyOrThrow()
            changes.notifyChanged()
            ActionOutcome.Done
        }.getOrElse { ActionOutcome.Failed(it.describeAndLog()) }

    override suspend fun updateEntry(
        plantKey: String,
        entryKey: String,
        draft: DiaryDraft,
    ): ActionOutcome = runCatchingCancellable {
        val (retrofit, tenant) = target()
        retrofit.create(RawDiaryApi::class.java)
            .update(tenant, plantKey, entryKey, retrofit.asRequest(tenant, draft, forCreate = false))
            .bodyOrThrow()
        changes.notifyChanged()
        ActionOutcome.Done
    }.getOrElse { ActionOutcome.Failed(it.describeAndLog()) }

    override suspend fun deleteEntry(plantKey: String, entryKey: String): ActionOutcome =
        runCatchingCancellable {
            val (retrofit, tenant) = target()
            val response = retrofit.create(RawDiaryApi::class.java)
                .delete(tenant, plantKey, entryKey)
            if (!response.isSuccessful) throw HttpFailure(response.code(), null)
            changes.notifyChanged()
            ActionOutcome.Done
        }.getOrElse { ActionOutcome.Failed(it.describeAndLog()) }

    override suspend fun requestAnalysis(plantKey: String, entryKey: String): ActionOutcome =
        runCatchingCancellable {
            val (retrofit, tenant) = target()
            retrofit.create(RawDiaryApi::class.java)
                .requestAnalysis(tenant, plantKey, entryKey)
                .bodyOrThrow()
            changes.notifyChanged()
            ActionOutcome.Done
        }.getOrElse { ActionOutcome.Failed(it.describeAndLog()) }

    /**
     * A draft as the endpoint takes it, uploading whatever photos are new first.
     *
     * Sequentially, and before the entry: the endpoint takes ids, so there is nothing to write
     * until every upload has answered. Serial rather than parallel because five full-size
     * photos at once on a phone connection is how an upload times out.
     *
     * [forCreate] decides whether `capture_environment` travels at all. It tells the server
     * whether to *look* at its sensors, which is a question only a new entry can be asked —
     * `PUT` has no such field, and sending one would be describing a moment that has passed.
     */
    private suspend fun Retrofit.asRequest(
        tenant: String,
        draft: DiaryDraft,
        forCreate: Boolean,
    ): NoteRequest {
        val uploaded = draft.newPhotos.map { jpeg -> upload(tenant, jpeg) }
        return NoteRequest(
            text = draft.text,
            title = draft.title?.takeIf { it.isNotBlank() },
            entryType = draft.entryType,
            // Existing photos first, in the order the entry already had them: an edit that
            // added one should not reshuffle the ones that were there.
            photoRefs = draft.photoRefs + uploaded,
            tags = draft.tags,
            captureEnvironment = draft.captureEnvironment.takeIf { forCreate },
        )
    }

    /**
     * Uploads one photo as a **diary** attachment and returns the id the entry will reference.
     *
     * Not `POST …/plant-instances/{key}/photos`, which was the first guess and is a different
     * thing: that endpoint files a plant *photo* — the gallery a cover picture is chosen from —
     * and the diary refuses its ids with "is not a diary photo of this tenant". Attachments
     * carry a category, and the diary only accepts its own.
     */
    private suspend fun Retrofit.upload(tenant: String, jpeg: ByteArray): String {
        val part = MultipartBody.Part.createFormData(
            "file",
            // The instance keeps the name; it never reaches a filesystem here.
            "diary.jpg",
            jpeg.toRequestBody(MEDIA_TYPE_JPEG.toMediaType()),
        )
        val response = create(RawAttachmentsApi::class.java)
            .upload(tenant, part, CATEGORY_DIARY.toRequestBody(MEDIA_TYPE_TEXT.toMediaType()))
            .bodyOrThrow()
        return (response as? JsonObject)?.text("attachment_id")
            ?: throw UploadWithoutId()
    }

    override suspend fun addPhoto(plantKey: String, jpeg: ByteArray): ActionOutcome =
        runCatchingCancellable {
            val (retrofit, tenant) = target()
            val part = MultipartBody.Part.createFormData(
                "file",
                // The instance keeps the name; it never reaches a filesystem here.
                "detection.jpg",
                jpeg.toRequestBody(MEDIA_TYPE_JPEG.toMediaType()),
            )
            retrofit.create(PlantPhotosApi::class.java)
                .uploadPlantPhotoApiV1TTenantSlugPlantInstancesKeyPhotosPost(plantKey, tenant, part)
                .bodyOrThrow()
            // The plant's page holds a photo section that just became stale.
            changes.notifyChanged()
            ActionOutcome.Done
        }.getOrElse { ActionOutcome.Failed(it.describeAndLog()) }

    private suspend fun baseUrl(): String = connections.connection.first()?.baseUrl.orEmpty()

    override suspend fun confirmCare(plantKey: String, kind: String): ActionOutcome =
        runCatchingCancellable {
            val (retrofit, _) = target()
            retrofit.create(RawCareApi::class.java)
                .confirm(plantKey, ConfirmRequest(reminderType = kind))
                .bodyOrThrow()
            // Announced only on success, and only after the instance has answered: a list
            // reloaded on an optimistic guess would show the old state again a moment later.
            changes.notifyChanged()
            ActionOutcome.Done
        }.getOrElse { ActionOutcome.Failed(it.describeAndLog()) }

    /**
     * The instance to talk to, or a failure that reads like one.
     *
     * Throws rather than returning null so every caller's `runCatching` handles a missing
     * connection the same way it handles a refused one — three copies of the same null check
     * is how one of them ends up missing it.
     */
    private suspend fun target(): Pair<Retrofit, String> {
        val connection = connections.connection.first()
            ?: throw NotConnected()
        val credential = credentials.load()
        return apis.create(connection.baseUrl) { credential } to connection.tenantSlug
    }

    private fun <T> Response<T>.bodyOrThrow(): T {
        // The error body is read here or never: it is a one-shot stream, and by the time the
        // failure reaches a message the response is gone. A 422 that does not say which field
        // it objected to is a status code and nothing more — which is what turned a one-line
        // contract violation into an afternoon of guessing.
        if (!isSuccessful) throw HttpFailure(code(), runCatching { errorBody()?.string() }.getOrNull())
        // A 204 is a legitimate answer to a confirmation, and Unit is what the caller wanted.
        @Suppress("UNCHECKED_CAST")
        return body() ?: Unit as T
    }

    /**
     * A failure in words the plant's page can show.
     *
     * Deliberately concrete: "the instance has no open watering reminder for this plant"
     * (which is what a 400 here means) tells the user why the button did nothing, where
     * "could not save" leaves them pressing it again.
     */
    /**
     * Turns a failure into a sentence, and leaves a copy in the log.
     *
     * Logged as well as returned because the returned string has one showing: a snackbar the
     * user may look away from. The instance's own words about *why* it refused something are
     * the hardest part of this app to reproduce on demand, and losing them to a four-second
     * animation costs another round trip through a person.
     *
     * Follows [NetworkPlantsClient]'s precedent for logging from this module. No payload
     * reaches the log — only the instance's description of its own refusal.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun Throwable.describeAndLog(): String = describe().also {
        // The raw body as well as the sentence made from it. A parser that reads the wrong key
        // produces a confident, useless message — which is how "photo_refs" reached the screen
        // with no explanation after it — and the only way to see that is to log what it read.
        //
        // Guarded for the same reason the plant list guards its own: this runs on the failure
        // path, and a logger that throws would replace a message the user could act on with a
        // crash. The only thrower observed is the JVM unit test's `android.jar` stub — which
        // is exactly where a write's failure paths are tested.
        try {
            val raw = (this as? HttpFailure)?.body?.take(RAW_BODY_LOG_LIMIT)
            Log.w(LOG_TAG, "the action failed: $it${raw?.let { body -> " | body: $body" }.orEmpty()}", this)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (reportingFailed: Throwable) {
            // Nowhere left to report it — reporting is what just failed.
        }
    }

    private fun Throwable.describe(): String = when {
        this is NotConnected -> "the app is not connected to an instance"
        this is HttpFailure && status == HTTP_UNAUTHORIZED ->
            "the instance refused the stored credential — reconnect in Settings"
        // Told apart from 401 on purpose. A write refused with 403 is a *role*: the credential
        // authenticated and this account may not write here, and reconnecting cannot widen
        // that — it sends the user round a loop back to the same refusal (#12).
        this is HttpFailure && status == HTTP_FORBIDDEN ->
            "your account may not do this on this instance"
        this is HttpFailure && status == HTTP_BAD_REQUEST ->
            "the instance has nothing open to confirm for this plant"
        this is UploadWithoutId -> "the instance stored the photo but did not name it"
        // Not "the entry": writing one is two calls, and a photo the instance refuses fails
        // here too. Naming the wrong one sends the user looking in the wrong place.
        this is HttpFailure && status == HTTP_UNPROCESSABLE ->
            "the instance refused the request: ${body.instanceErrorDetail() ?: "no reason given"}"
        this is HttpFailure -> listOfNotNull(
            "the instance answered HTTP $status",
            body.instanceErrorDetail(),
        ).joinToString(" — ")
        else -> this::class.simpleName.orEmpty()
    }

    private class NotConnected : Exception("not connected")

    private class HttpFailure(val status: Int, val body: String?) : Exception("HTTP $status")

    /** The instance stored a photo but named no id, so nothing can reference it. */
    private class UploadWithoutId : Exception("upload returned no attachment_id")

    /** The diary as raw JSON — see the class KDoc for why it is not the generated API. */
    private interface RawDiaryApi {

        @GET("api/v1/t/{tenant_slug}/plant-instances/{key}/diary")
        suspend fun list(
            @Path("tenant_slug") tenantSlug: String,
            @Path("key") key: String,
            @Query("offset") offset: Int,
            @Query("limit") limit: Int,
        ): Response<JsonArray>

        @POST("api/v1/t/{tenant_slug}/plant-instances/{key}/diary")
        suspend fun create(
            @Path("tenant_slug") tenantSlug: String,
            @Path("key") key: String,
            @Body body: NoteRequest,
        ): Response<JsonElement>

        @PUT("api/v1/t/{tenant_slug}/plant-instances/{key}/diary/{entry_key}")
        suspend fun update(
            @Path("tenant_slug") tenantSlug: String,
            @Path("key") key: String,
            @Path("entry_key") entryKey: String,
            @Body body: NoteRequest,
        ): Response<JsonElement>

        @DELETE("api/v1/t/{tenant_slug}/plant-instances/{key}/diary/{entry_key}")
        suspend fun delete(
            @Path("tenant_slug") tenantSlug: String,
            @Path("key") key: String,
            @Path("entry_key") entryKey: String,
        ): Response<Unit>

        @POST("api/v1/t/{tenant_slug}/plant-instances/{key}/diary/{entry_key}/request-analysis")
        suspend fun requestAnalysis(
            @Path("tenant_slug") tenantSlug: String,
            @Path("key") key: String,
            @Path("entry_key") entryKey: String,
        ): Response<JsonElement>
    }

    private interface RawAttachmentsApi {

        @Multipart
        @POST("api/v1/t/{tenant_slug}/attachments")
        suspend fun upload(
            @Path("tenant_slug") tenantSlug: String,
            @Part file: MultipartBody.Part,
            @Part("category") category: RequestBody,
        ): Response<JsonElement>
    }

    private interface RawCareApi {

        @POST("api/v1/care-reminders/plants/{plant_key}/confirm")
        suspend fun confirm(
            @Path("plant_key") plantKey: String,
            @Body body: ConfirmRequest,
        ): Response<JsonElement>
    }

    private companion object {

        const val LOG_TAG = "PlantActions"

        /** Enough for an error envelope, short of dumping a response body into logcat. */
        const val RAW_BODY_LOG_LIMIT = 1_000

        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNPROCESSABLE = 422

        const val MEDIA_TYPE_JPEG = "image/jpeg"

        /**
         * The category the diary accepts. An attachment filed under any other — `plant`, say —
         * is refused by `photo_refs` as "not a diary photo of this tenant".
         */
        const val CATEGORY_DIARY = "diary"

        /**
         * Sent as a plain part, not through the JSON converter.
         *
         * With kotlinx.serialization as the only body converter, a `@Part("category") String`
         * is encoded as `"diary"` *with quotes* and `Content-Type: application/json`, which
         * the server's form parser then reads including the quotes — the trap already noted in
         * NetworkModule for the multipart endpoints.
         */
        const val MEDIA_TYPE_TEXT = "text/plain"

        /** Edge length in pixels; the endpoint takes a number, not a size name. */
        const val THUMBNAIL_EDGE_PX = 256

        /** The backend's neutral `entry_type`; see [PlantActionsClient.addNote]. */
        const val ENTRY_TYPE_NOTE = "note"

        fun JsonElement.asDiaryEntry(baseUrl: String, tenant: String): DiaryEntry? {
            val fields = this as? JsonObject ?: return null
            val key = fields.text("key") ?: return null
            val text = fields.text("text") ?: return null
            return DiaryEntry(
                key = key,
                kind = fields.text("entry_type") ?: ENTRY_TYPE_NOTE,
                title = fields.text("title"),
                text = text,
                createdAt = fields.text("created_at"),
                // Resolved here rather than by the screen: the id alone addresses nothing,
                // and the base URL and tenant are known at this point and nowhere later.
                photoUrls = fields.refs()
                    .map { id ->
                        "${baseUrl.trimEnd('/')}/api/v1/t/$tenant/attachments/$id" +
                            "/thumbnails/$THUMBNAIL_EDGE_PX"
                    },
                environment = (fields["environment"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { it.asReading() },
                environmentStatus = fields.text("environment_status"),
                photoRefs = fields.refs(),
                tags = (fields["tags"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                canRequestAnalysis = (fields["can_request_analysis"] as? JsonPrimitive)
                    ?.takeIf { !it.isString }
                    ?.content
                    ?.toBooleanStrictOrNull() == true,
                analysisState = fields.text("analysis_state"),
                analysis = fields.text("analysis"),
            )
        }

        /** The attachment ids an entry references, in stored order. */
        fun JsonObject.refs(): List<String> = (this["photo_refs"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

        /** One sensor value; an entry missing metric or value is not one worth showing. */
        fun JsonElement.asReading(): EnvironmentReading? {
            val fields = this as? JsonObject ?: return null
            val metric = fields.text("metric_type") ?: return null
            val value = (fields["value"] as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.content
                ?.toDoubleOrNull()
                ?: return null
            return EnvironmentReading(
                metric = metric,
                value = value,
                unit = fields.text("unit"),
                origin = fields.text("origin"),
            )
        }

        fun JsonObject.text(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
            try {
                Result.success(block())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                Result.failure(failure)
            }
    }
}
