package io.github.nolte.kamerplanter.core.network

import android.util.Log
import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.connection.CredentialStore
import io.github.nolte.kamerplanter.core.network.generated.apis.LocationsApi
import io.github.nolte.kamerplanter.core.network.generated.apis.PlantInstancesApi
import io.github.nolte.kamerplanter.core.network.generated.apis.PlantPhotosApi
import io.github.nolte.kamerplanter.core.network.generated.models.PlantResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Loads the plant list from the connected instance.
 *
 * The endpoint shapes force the structure here. `GET /plant-instances` returns keys, not
 * names: `location_key` has to be resolved against `GET /locations`, and the cover photo is
 * a per-plant call. Done naively that is one request per row, twice over.
 *
 * So the care dashboard is fetched once for the whole tenant, locations once per *site*
 * (that endpoint requires a `site_key`, so a single tenant-wide call is not on offer), and
 * only the photos stay per-plant — bounded, because fifty plants must not open fifty
 * sockets. Everything is joined in memory afterwards.
 */
@Singleton
class NetworkPlantsClient(
    private val apis: InstanceApiFactory,
    private val connections: ConnectionStore,
    private val credentials: CredentialStore,
    /**
     * How long the list waits for cover photos before rendering without them. A parameter so
     * a test can drive both sides of it; Dagger does not read Kotlin default arguments, so
     * the injected constructor supplies it instead.
     */
    private val thumbnailBudgetMillis: Long,
) : PlantsClient {

    @Inject
    constructor(
        apis: InstanceApiFactory,
        connections: ConnectionStore,
        credentials: CredentialStore,
    ) : this(apis, connections, credentials, THUMBNAIL_BUDGET_MILLIS)

    override suspend fun loadPlants(): PlantListOutcome = runCatchingCancellable {
        val connection = connections.connection.first()
            ?: return PlantListOutcome.Unavailable("the app is not connected to an instance")
        val tenant = connection.tenantSlug
        val credential = credentials.load()
        val retrofit = apis.create(connection.baseUrl) { credential }

        loadFor(retrofit, tenant, connection.baseUrl)
    }.getOrElse { failure ->
        when {
            // 403 belongs here with 401: it means the credential authenticated but may not
            // read this tenant — an API key whose scope no longer covers it, say. Retrying
            // cannot fix either, so both send the user to Settings rather than to a button
            // that will never succeed.
            failure is HttpFailure && failure.status in CREDENTIAL_REFUSED ->
                PlantListOutcome.Unauthorized
            failure is HttpFailure -> PlantListOutcome.Unavailable(
                "the instance answered HTTP ${failure.status}",
            )
            else -> PlantListOutcome.Unavailable(failure::class.simpleName.orEmpty())
        }
    }

    // getCompleted() is experimental API, chosen deliberately: it is the only way to read a
    // Deferred that has already finished without suspending on the ones that have not.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun loadFor(
        retrofit: Retrofit,
        tenant: String,
        baseUrl: String,
    ): PlantListOutcome = coroutineScope {
        // The plant list is the only call whose failure is fatal: without it there is no
        // list. Locations and the care dashboard are enrichment — a row without a resolved
        // location name is still a usable row, and one without a badge is merely one whose
        // care state is unknown, which beats showing nothing at all (R-COMPAT-4).
        val plants = retrofit.create(PlantInstancesApi::class.java)
            .listPlantsApiV1TTenantSlugPlantInstancesGet(tenantSlug = tenant, offset = 0, limit = PAGE_SIZE)
            .bodyOrThrow()
            // Removed instances come back from this endpoint too. A list that mixes dead
            // plants into living ones answers the wrong question, and without a filter row
            // there is nowhere to opt back in.
            //
            // Filtered client-side because the endpoint offers no parameter for it, which
            // means removed plants consume page slots: a tenant with more than PAGE_SIZE of
            // them would show an empty list indistinguishable from having no plants. Rare
            // enough to accept for now, and it disappears the moment the endpoint grows the
            // filters #9 wants anyway.
            .filter { it.removedOn == null }

        // Only the sites the plants actually sit in — resolving a tenant's full site list
        // would fetch locations nothing on this screen refers to.
        val siteKeys = plants.mapNotNull { it.siteKey }.toSet()
        val locationNames = async { retrofit.locationNames(tenant, siteKeys) }
        val careActions = async { retrofit.careActions(tenant) }

        // Bounded so a large tenant does not open one socket per plant. The permit is held
        // across the call, so at most FETCH_CONCURRENCY photo requests are ever in flight.
        val photoLimit = Semaphore(FETCH_CONCURRENCY)
        val thumbnails = plants.map { plant ->
            async { plant.key to photoLimit.withPermit { retrofit.coverThumbnail(tenant, plant.key, baseUrl) } }
        }

        val locations = locationNames.await()
        val care = careActions.await()

        // Photos are enrichment like the other two, but unlike them they are per-plant: two
        // hundred of them at six at a time is thirty-odd serialized waves, and awaiting all
        // of them holds the whole list behind the slowest.
        //
        // The timeout has to be followed by an explicit cancel, and the two together are the
        // whole point. `withTimeoutOrNull` alone abandons the *waiting* but not the jobs —
        // they are children of this `coroutineScope`, which then waits for them anyway on
        // the way out. That version cost the same wall-clock and threw away every thumbnail
        // that had already arrived: strictly worse than not having a budget at all.
        withTimeoutOrNull(thumbnailBudgetMillis) { thumbnails.awaitAll() }
        val covers = thumbnails
            // Whatever arrived inside the budget is kept; the rest becomes a placeholder.
            .filter { it.isCompleted && !it.isCancelled }
            .mapNotNull { runCatchingCancellable { it.getCompleted() }.getOrNull() }
            .toMap()
        thumbnails.forEach { it.cancel() }

        PlantListOutcome.Loaded(
            plants
                .map { plant ->
                    PlantSummary(
                        key = plant.key,
                        displayName = plant.displayName(),
                        species = plant.speciesLabel(),
                        location = plant.locationKey?.let(locations::get),
                        thumbnailUrl = covers[plant.key],
                        careAction = care[plant.key],
                    )
                }
                // No order is specified by the endpoint, and a list has to have one. By name
                // is the order a reader can predict; case-insensitive so "acer" and "Acer"
                // do not end up in different halves of the list.
                .sortedBy { it.displayName.lowercase() },
        )
    }

    /**
     * `location_key` to display name.
     *
     * One call per *site*, not per plant: `GET /locations` requires `site_key`, so there is
     * no way to ask for a tenant's locations in one go. The sites come from the plants
     * themselves. A tenant has a handful of sites and can have hundreds of plants, so this
     * stays far from a request per row.
     */
    private suspend fun Retrofit.locationNames(
        tenant: String,
        siteKeys: Set<String>,
    ): Map<String, String> = coroutineScope {
        siteKeys
            .map { site ->
                async {
                    runCatchingCancellable {
                        create(LocationsApi::class.java)
                            .listLocationsApiV1TTenantSlugLocationsGet(tenantSlug = tenant, siteKey = site)
                            .bodyOrThrow()
                            .associate { it.key to it.name }
                    }.getOrElse { failure -> warn("locations for site $site", failure) }
                }
            }
            .awaitAll()
            .fold(emptyMap()) { all, ofSite -> all + ofSite }
    }

    /**
     * One tenant-wide call that joins onto rows by `plant_key`.
     *
     * Read as raw JSON and decoded one entry at a time, rather than as the generated
     * `List<CareDashboardEntryResponse>`. `reminder_type` is a generated enum, and this
     * repository's own `GeneratedClientSerializationTest` pins what that means: a value this
     * build has never heard of throws for the **whole response**, not for the one field. A
     * server one release ahead adding a reminder kind would therefore cost every plant in
     * the tenant its badge, silently, because the failure is swallowed here.
     *
     * Per entry, that same unknown kind costs exactly the plant it belongs to (R-COMPAT-3).
     */
    private suspend fun Retrofit.careActions(tenant: String): Map<String, CareAction> =
        runCatchingCancellable {
            create(RawCareDashboardApi::class.java)
                .dashboard(tenantSlug = tenant)
                .bodyOrThrow()
                .mapNotNull { entry -> entry.asCareAction() }
                // A plant usually has several open reminders — this tenant averages nearly
                // three. `toMap()` kept whichever the server happened to list last, so which
                // one a row showed was an accident of JSON order: a watering overdue by five
                // days lost to a humidity check due next week. The most pressing one wins.
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, actions) -> actions.mostPressing() }
        }.getOrElse { failure -> warn("the care dashboard", failure) }

    /**
     * The care dashboard as raw JSON.
     *
     * Deliberately not the generated `CareRemindersApi`: its `List<CareDashboardEntryResponse>`
     * is all-or-nothing, and the whole point here is to lose one entry instead of all of them.
     */
    private interface RawCareDashboardApi {

        @GET("api/v1/t/{tenant_slug}/care-reminders/dashboard")
        suspend fun dashboard(@Path("tenant_slug") tenantSlug: String): Response<JsonArray>
    }

    /**
     * The small thumbnail of the plant's cover photo.
     *
     * A missing photo is the common case, not an error — most plants have none — so every
     * failure here yields `null` rather than sinking the row.
     */
    private suspend fun Retrofit.coverThumbnail(
        tenant: String,
        plantKey: String,
        baseUrl: String,
    ): String? = runCatchingCancellable {
        val photos = create(PlantPhotosApi::class.java)
            .listPlantPhotosApiV1TTenantSlugPlantInstancesKeyPhotosGet(key = plantKey, tenantSlug = tenant)
            .bodyOrThrow()
            .photos
        val cover = photos.firstOrNull { it.isCover } ?: photos.firstOrNull()
        cover?.thumbnailUris?.small?.let { absoluteAgainst(baseUrl, it) }
    }.getOrElse { null }

    private class HttpFailure(val status: Int) : Exception("HTTP $status")

    private companion object {
        /**
         * `GET /plant-instances` takes only offset and limit — it has no filter parameters —
         * so one generous page is what the list works from until the endpoint grows them.
         */
        const val PAGE_SIZE = 200
        const val FETCH_CONCURRENCY = 6

        /**
         * The production budget. A row with a placeholder is a usable row; a spinner that
         * lasts until the last of two hundred photo requests answers is not.
         */
        const val THUMBNAIL_BUDGET_MILLIS = 4_000L

        const val LOG_TAG = "PlantsClient"

        /**
         * Records an enrichment call that failed, and yields the empty result the list carries
         * on without.
         *
         * Swallowing these is deliberate — a row without a location name is still a usable row
         * — but swallowing them *silently* is what let a total failure of `GET /locations` pass
         * for "these plants have no location" through a whole release. The blast radius is
         * wide: the care dashboard is one tenant-wide call, and the locations call is per site,
         * so a single failure costs every row that sits in that site. Nothing else in the app
         * would say so.
         *
         * The log call is guarded because of where it sits. This runs in the `getOrElse`
         * block — *outside* the `runCatchingCancellable` that makes an enrichment failure
         * survivable — so anything thrown here escapes to `loadPlants`, whose own catch
         * degrades the whole list to `Unavailable`. A logger that threw would then do precisely
         * what the failure it was reporting was forbidden from doing.
         *
         * The only thrower actually observed is the JVM unit test's `android.jar` stub, not
         * anything on a device: removing this guard turns ten of this class's seventeen tests
         * red, all of them with a `ClassCastException` on the degraded outcome rather than on
         * the logging. So the guard buys a testable module without a global
         * `isReturnDefaultValues`, and the structural point above is why it is worth keeping
         * rather than working around.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        fun <K, V> warn(what: String, failure: Throwable): Map<K, V> {
            try {
                Log.w(LOG_TAG, "$what could not be loaded; rows render without it", failure)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (reportingFailed: Throwable) {
                // Nowhere left to report it — reporting is what just failed.
            }
            return emptyMap()
        }

        fun <T> Response<T>.bodyOrThrow(): T {
            if (!isSuccessful) throw HttpFailure(code())
            return body() ?: throw HttpFailure(code())
        }

        /**
         * One dashboard entry, or `null` where it cannot be read.
         *
         * Hand-parsed rather than deserialized so a `reminder_type` this build does not know
         * costs one badge instead of every badge in the tenant. `plant_key`, `reminder_type`
         * and `urgency` are the fields a row needs; an entry missing any of them is not
         * usable and is dropped.
         */
        fun JsonElement.asCareAction(): Pair<String, CareAction>? {
            val fields = this as? JsonObject ?: return null
            val plantKey = fields.text("plant_key") ?: return null
            val kind = fields.text("reminder_type") ?: return null
            val urgency = fields.text("urgency") ?: return null
            return plantKey to CareAction(kind = kind, urgency = urgency, dueDate = fields.text("due_date"))
        }

        /**
         * Overdue before upcoming, then the earliest due date.
         *
         * Ordered on the urgency the backend assigns rather than on the date alone: an entry
         * without a date must still rank, and "overdue" is the backend's judgement, not
         * something to re-derive from a date this app cannot see the schedule behind.
         */
        fun List<CareAction>.mostPressing(): CareAction =
            minWithOrNull(
                compareBy<CareAction> { if (it.urgency == URGENCY_OVERDUE) 0 else 1 }
                    .thenBy { it.dueDate ?: "9999-99-99" },
            ) ?: first()

        fun JsonObject.text(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** `plant_name` is nullable, and a blank one is as unusable as a missing one. */
        fun PlantResponse.displayName(): String =
            plantName?.takeIf { it.isNotBlank() } ?: instanceId

        /** Common name first — that is what the owner calls it — with the cultivar appended. */
        fun PlantResponse.speciesLabel(): String? {
            val base = species?.commonNames?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: species?.scientificName
                ?: return cultivar?.name
            return cultivar?.name?.let { "$base '$it'" } ?: base
        }

        /**
         * Thumbnail URIs may come back relative to the instance. Coil needs an absolute URL,
         * and prefixing one that is already absolute would produce a broken address.
         */
        fun absoluteAgainst(baseUrl: String, uri: String): String = when {
            uri.startsWith("http://") || uri.startsWith("https://") -> uri
            else -> baseUrl.trimEnd('/') + "/" + uri.trimStart('/')
        }

        @Suppress("TooGenericExceptionCaught")
        inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
            try {
                Result.success(block())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
    }
}
