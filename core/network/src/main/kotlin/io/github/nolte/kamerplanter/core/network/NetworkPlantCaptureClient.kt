package io.github.nolte.kamerplanter.core.network

import android.util.Log
import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.connection.CredentialStore
import io.github.nolte.kamerplanter.core.network.generated.apis.IdentificationApi
import io.github.nolte.kamerplanter.core.network.generated.apis.LocationsApi
import io.github.nolte.kamerplanter.core.network.generated.apis.PlantInstancesApi
import io.github.nolte.kamerplanter.core.network.generated.apis.RecognitionApi
import io.github.nolte.kamerplanter.core.network.generated.apis.SitesApi
import io.github.nolte.kamerplanter.core.network.generated.apis.SpeciesApi
import io.github.nolte.kamerplanter.core.network.generated.models.LinkInstanceRequest
import io.github.nolte.kamerplanter.core.network.generated.models.PlantCreate
import io.github.nolte.kamerplanter.core.network.generated.models.SpeciesCreate
import io.github.nolte.kamerplanter.core.network.generated.models.SuggestionResponse
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlantCaptureClient] against a kamerplanter instance.
 *
 * Same shape as the other clients here: every call resolves the connection when it runs,
 * turns the response into an app-owned outcome, and never lets a generated type out. The
 * status mapping follows the plant page's precedent — 401 is a credential to renew, 403 a
 * role no re-pairing widens — and adds the two codes this surface is the first to meet: 409
 * on a species that collides with something other than its normalised name (R26), and 429
 * from a recogniser that asked for a pause (R33).
 */
@Singleton
@Suppress("TooManyFunctions")
class NetworkPlantCaptureClient @Inject constructor(
    private val apis: InstanceApiFactory,
    private val connections: ConnectionStore,
    private val credentials: CredentialStore,
    /** Told after a plant is created: the list and its pages are stale from then on (R32). */
    private val changes: PlantDataChanges,
) : PlantCaptureClient {

    override suspend fun identificationReadiness(): IdentificationReadiness = runCatchingCancellable {
        val target = target() ?: return IdentificationReadiness.NotConnected
        // Unauthenticated and instance-wide by design: the UI can decide before any tenant
        // work happens, and a 404 here is an instance too old to know the route at all.
        val status = target.retrofit.create(RecognitionApi::class.java)
            .identificationStatusApiV1RecognitionStatusGet()
        if (status.code() == NOT_FOUND || (status.isSuccessful && status.body()?.available != true)) {
            return@runCatchingCancellable IdentificationReadiness.NotOffered
        }
        status.bodyOrThrow()
        val recorded = target.retrofit.consentFor(PLANT_IDENTIFICATION_CONSENT)
        if (recorded?.granted == true) {
            IdentificationReadiness.Ready
        } else {
            IdentificationReadiness.ConsentRequired(recorded?.terms())
        }
    }.getOrElse { failure ->
        when {
            failure is HttpFailure && failure.status == HTTP_UNAUTHORIZED -> IdentificationReadiness.Unauthorized
            else -> IdentificationReadiness.Unavailable(failure.describeAndLog())
        }
    }

    override suspend fun grantIdentificationConsent(): ConsentOutcome = runCatchingCancellable {
        val target = target() ?: return ConsentOutcome.Failed("the app is not connected to an instance")
        target.retrofit.grantConsent(PLANT_IDENTIFICATION_CONSENT).bodyOrThrow()
        ConsentOutcome.Granted
    }.getOrElse { failure ->
        when {
            failure is HttpFailure && failure.status == HTTP_UNAUTHORIZED -> ConsentOutcome.Unauthorized
            failure is HttpFailure && failure.status == HTTP_FORBIDDEN -> ConsentOutcome.NotPermitted
            else -> ConsentOutcome.Failed(failure.describeAndLog())
        }
    }

    override suspend fun identify(jpeg: ByteArray, organ: String, language: String): IdentifyOutcome =
        runCatchingCancellable {
            val target = target() ?: return IdentifyOutcome.Unavailable("the app is not connected to an instance")
            val part = MultipartBody.Part.createFormData(
                "image",
                // Never persisted upstream — the name is a form-field formality.
                "plant.jpg",
                jpeg.toRequestBody(MEDIA_TYPE_JPEG.toMediaType()),
            )
            val answer = target.retrofit.create(IdentificationApi::class.java)
                .identifyPlantApiV1TTenantSlugIdentificationIdentifyPost(
                    tenantSlug = target.tenant,
                    image = part,
                    language = language,
                    organ = organ,
                )
                .bodyOrThrow()
            IdentifyOutcome.Identified(
                requestKey = answer.requestKey,
                isPlant = answer.isPlant,
                suggestions = answer.suggestions.orEmpty().map { it.toSuggestion() }.sortedBy { it.rank },
                message = answer.message,
            )
        }.getOrElse { failure ->
            when {
                failure is HttpFailure && failure.status == HTTP_UNAUTHORIZED -> IdentifyOutcome.Unauthorized
                failure is HttpFailure && failure.status == HTTP_FORBIDDEN ->
                    if (failure.body.errorCode() == CONSENT_REQUIRED_CODE) {
                        IdentifyOutcome.ConsentMissing
                    } else {
                        IdentifyOutcome.NotPermitted
                    }
                failure is HttpFailure && failure.status in REFUSED_IMAGE ->
                    IdentifyOutcome.Refused(failure.body.instanceErrorDetail() ?: "HTTP ${failure.status}")
                failure is HttpFailure && failure.status == HTTP_TOO_MANY_REQUESTS ->
                    IdentifyOutcome.RateLimited(failure.retryAfterSeconds)
                else -> IdentifyOutcome.Unavailable(failure.describeAndLog())
            }
        }

    override suspend fun selectSuggestion(requestKey: String, rank: Int): ActionOutcome = runCatchingCancellable {
        val target = target() ?: throw NotConnected()
        target.retrofit.create(IdentificationApi::class.java)
            .selectResultApiV1TTenantSlugIdentificationRequestKeySelectPost(requestKey, target.tenant, rank)
            .bodyOrThrow()
        ActionOutcome.Done
    }.getOrElse { ActionOutcome.Failed(it.describeAndLog()) }

    override suspend fun linkIdentification(requestKey: String, plantKey: String): ActionOutcome =
        runCatchingCancellable {
            val target = target() ?: throw NotConnected()
            target.retrofit.create(IdentificationApi::class.java)
                .linkPlantInstanceApiV1TTenantSlugIdentificationRequestKeyInstancePost(
                    requestKey,
                    target.tenant,
                    LinkInstanceRequest(plantInstanceKey = plantKey),
                )
                .bodyOrThrow()
            ActionOutcome.Done
        }.getOrElse { ActionOutcome.Failed(it.describeAndLog()) }

    override suspend fun catalogue(): Fetched<List<SpeciesEntry>> = fetched {
        val species = create(SpeciesApi::class.java)
        // The route paginates and offers nothing else, so the search the form needs happens
        // over the whole catalogue on the phone (R18). Paged until the instance's own `total`
        // is reached, with a ceiling so a misreported total cannot loop.
        val all = mutableListOf<SpeciesEntry>()
        var offset = 0
        do {
            val page = species.listSpeciesApiV1SpeciesGet(offset = offset, limit = PAGE_SIZE, xActiveTenant = tenant)
                .bodyOrThrow()
            all += page.items.map { SpeciesEntry(it.key, it.scientificName, it.commonNames) }
            offset += page.items.size
            val exhausted = page.items.isEmpty() || offset >= page.total
        } while (!exhausted && offset < PAGE_SIZE * MAX_PAGES)
        all
    }

    override suspend fun createSpecies(draft: SpeciesDraft): SpeciesCreateOutcome = runCatchingCancellable {
        val target = target() ?: throw NotConnected()
        val created = target.retrofit.create(SpeciesApi::class.java)
            .createSpeciesApiV1SpeciesPost(
                SpeciesCreate(
                    scientificName = draft.scientificName,
                    commonNames = draft.commonNames,
                    genus = draft.genus.orEmpty(),
                ),
                xActiveTenant = target.tenant,
            )
            .bodyOrThrow()
        SpeciesCreateOutcome.Created(created.key)
    }.getOrElse { failure ->
        when {
            failure is HttpFailure && failure.status == HTTP_UNAUTHORIZED -> SpeciesCreateOutcome.Unauthorized
            failure is HttpFailure && failure.status == HTTP_FORBIDDEN -> SpeciesCreateOutcome.NotPermitted
            failure is HttpFailure && failure.status == HTTP_CONFLICT -> SpeciesCreateOutcome.Conflict
            else -> SpeciesCreateOutcome.Failed(failure.describeAndLog())
        }
    }

    override suspend fun sites(): Fetched<List<Site>> = fetched {
        create(SitesApi::class.java)
            .listSitesApiV1TTenantSlugSitesGet(tenant, offset = 0, limit = PAGE_SIZE)
            .bodyOrThrow()
            .map { Site(it.key, it.name) }
    }

    override suspend fun locations(siteKey: String): Fetched<List<Location>> = fetched {
        create(LocationsApi::class.java)
            .listLocationsApiV1TTenantSlugLocationsGet(tenant, siteKey = siteKey)
            .bodyOrThrow()
            .map { Location(it.key, it.name) }
    }

    override suspend fun instanceIds(): Fetched<Set<String>> = fetched {
        val plants = create(PlantInstancesApi::class.java)
        val taken = mutableSetOf<String>()
        var offset = 0
        do {
            val page = plants.listPlantsApiV1TTenantSlugPlantInstancesGet(tenant, offset = offset, limit = PAGE_SIZE)
                .bodyOrThrow()
            page.forEach { taken += it.instanceId }
            offset += page.size
        } while (page.size == PAGE_SIZE && offset < PAGE_SIZE * MAX_PAGES)
        taken
    }

    override suspend fun createPlant(draft: PlantDraft): PlantCreateOutcome = runCatchingCancellable {
        val target = target() ?: throw NotConnected()
        val created = target.retrofit.create(PlantInstancesApi::class.java)
            .createPlantApiV1TTenantSlugPlantInstancesPost(
                target.tenant,
                PlantCreate(
                    instanceId = draft.instanceId,
                    speciesKey = draft.speciesKey,
                    plantedOn = draft.plantedOn,
                    plantName = draft.plantName,
                    siteKey = draft.siteKey,
                    locationKey = draft.locationKey,
                ),
            )
            .bodyOrThrow()
        // The list and every page that shows plants are stale from this moment (R32).
        changes.notifyChanged()
        PlantCreateOutcome.Created(created.key)
    }.getOrElse { failure ->
        when {
            failure is HttpFailure && failure.status == HTTP_UNAUTHORIZED -> PlantCreateOutcome.Unauthorized
            failure is HttpFailure && failure.status == HTTP_FORBIDDEN -> PlantCreateOutcome.NotPermitted
            failure is HttpFailure && failure.status == HTTP_UNPROCESSABLE ->
                PlantCreateOutcome.Rejected(failure.body.instanceErrorDetail() ?: "the instance rejected the plant")
            else -> PlantCreateOutcome.Failed(failure.describeAndLog())
        }
    }

    // --- plumbing ---------------------------------------------------------------------------

    private class Target(val retrofit: Retrofit, val tenant: String)

    private suspend fun target(): Target? {
        val connection = connections.connection.first() ?: return null
        val credential = credentials.load()
        return Target(apis.create(connection.baseUrl) { credential }, connection.tenantSlug)
    }

    /** The three-way read every form input shares (see [Fetched]). */
    private suspend fun <T> fetched(read: suspend Target.() -> T): Fetched<T> = runCatchingCancellable {
        val target = target() ?: throw NotConnected()
        Fetched.Loaded(target.read())
    }.getOrElse { failure ->
        when {
            failure is NotConnected -> Fetched.NotConnected
            failure is HttpFailure && failure.status == HTTP_UNAUTHORIZED -> Fetched.Unauthorized
            else -> Fetched.Failed(failure.describeAndLog())
        }
    }

    private fun <A> Target.create(api: Class<A>): A = retrofit.create(api)

    private fun <T> Response<T>.bodyOrThrow(): T {
        if (!isSuccessful) {
            throw HttpFailure(
                code(),
                runCatching { errorBody()?.string() }.getOrNull(),
                headers()["Retry-After"]?.trim()?.toLongOrNull(),
            )
        }
        @Suppress("UNCHECKED_CAST")
        return body() ?: Unit as T
    }

    private fun Throwable.describeAndLog(): String = describe().also {
        // Guarded like the plant page's logger: this runs on the failure path, and the JVM
        // unit tests' `android.jar` stub throws from `Log`.
        runCatching { Log.w(LOG_TAG, "plant capture failed: $it", this) }
    }

    private fun Throwable.describe(): String = when {
        this is NotConnected -> "the app is not connected to an instance"
        this is HttpFailure && status == HTTP_UNAUTHORIZED ->
            "the instance refused the stored credential — reconnect in Settings"
        this is HttpFailure && status == HTTP_FORBIDDEN -> "your account may not do this on this instance"
        this is HttpFailure -> listOfNotNull("the instance answered HTTP $status", body.instanceErrorDetail())
            .joinToString(": ")
        else -> "the instance could not be reached (${this::class.simpleName})"
    }

    private class NotConnected : Exception("not connected")

    private class HttpFailure(val status: Int, val body: String?, val retryAfterSeconds: Long? = null) :
        Exception("HTTP $status")

    private companion object {
        const val LOG_TAG = "PlantCapture"
        const val MEDIA_TYPE_JPEG = "image/jpeg"
        const val NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_UNPROCESSABLE = 422
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val PAGE_SIZE = 200
        const val MAX_PAGES = 25

        /** The backend's `error_code` for a missing consent, on an otherwise ordinary 403. */
        const val CONSENT_REQUIRED_CODE = "CONSENT_REQUIRED"

        const val HTTP_PAYLOAD_TOO_LARGE = 413
        const val HTTP_UNSUPPORTED_MEDIA_TYPE = 415

        /** Too large, not an image type the instance takes, or not decodable. */
        val REFUSED_IMAGE = setOf(HTTP_PAYLOAD_TOO_LARGE, HTTP_UNSUPPORTED_MEDIA_TYPE, HTTP_UNPROCESSABLE)

        fun String?.errorCode(): String? =
            (runCatching { Json.parseToJsonElement(this.orEmpty()) }.getOrNull() as? JsonObject)
                ?.get("error_code")
                ?.let { it as? JsonPrimitive }
                ?.takeIf { it.isString }
                ?.content

        fun SuggestionResponse.toSuggestion() = Suggestion(
            rank = rank,
            scientificName = scientificName,
            commonNames = commonNames.orEmpty(),
            confidence = confidence.toDouble(),
            genus = genus,
            matchedSpeciesKey = matchedSpeciesKey?.takeIf { speciesInDatabase == true },
            autoAccept = autoAccept == true,
        )
    }
}
