package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.connection.CredentialStore
import io.github.nolte.kamerplanter.core.network.generated.apis.LocationsApi
import io.github.nolte.kamerplanter.core.network.generated.apis.PhasesApi
import io.github.nolte.kamerplanter.core.network.generated.apis.PlantInstancesApi
import io.github.nolte.kamerplanter.core.network.generated.apis.PlantPhotosApi
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads one plant's page from the connected instance, a section at a time.
 *
 * Deliberately not one method that returns the whole page. The screen loads its sections
 * independently and lets each fail on its own — a plant whose phase history cannot be read is
 * still a plant whose master data, photos and diary are worth showing — and a combined call
 * could only succeed or fail as a whole (#11).
 *
 * `GET /plant-instances/{key}` returns keys rather than names, exactly as the list endpoint
 * does, so the location is resolved here and never handed to the UI raw.
 */
@Singleton
class NetworkPlantPageClient @Inject constructor(
    private val apis: InstanceApiFactory,
    private val connections: ConnectionStore,
    private val credentials: CredentialStore,
) : PlantPageClient {

    override suspend fun plant(key: String): SectionOutcome<PlantDetail> = section { target ->
        val plant = target.retrofit.create(PlantInstancesApi::class.java)
            .getPlantApiV1TTenantSlugPlantInstancesKeyGet(key = key, tenantSlug = target.tenant)
            .bodyOrThrow()

        // The location name, and only if the plant names a site to look it up in. A failure
        // here costs the name and nothing else: a page without a location is still a page,
        // while a page that refused to load because one enrichment call failed is not.
        val location = plant.locationKey?.let { locationKey ->
            plant.siteKey?.let { site -> target.locationName(site, locationKey) }
        }

        PlantDetail(
            key = plant.key,
            displayName = plant.displayName(),
            species = plant.speciesLabel(),
            location = location,
            // Dates and enums are stringified at this boundary rather than carried in the
            // generated types: `java.time` and a generated enum are both shapes the UI would
            // then have to know, and neither says anything a page needs beyond its text.
            plantedOn = plant.plantedOn?.toString(),
            removal = plant.removedOn?.let {
                PlantRemoval(
                    removedOn = it.toString(),
                    type = plant.terminationType?.value,
                    cause = plant.terminationCause?.value,
                )
            },
            phase = plant.currentPhase?.takeIf { it.isNotBlank() }?.let {
                PlantPhase(name = it, startedAt = plant.currentPhaseStartedAt?.toString())
            },
            containerVolumeLiters = plant.containerVolumeLiters?.toDouble(),
            substrate = plant.substrateTypeOverride?.value,
            cultivationCycle = plant.cultivationCycleType?.value,
            motherKey = plant.motherKey,
        )
    }

    override suspend fun photos(key: String): SectionOutcome<List<PlantPhoto>> = section { target ->
        target.retrofit.create(PlantPhotosApi::class.java)
            .listPlantPhotosApiV1TTenantSlugPlantInstancesKeyPhotosGet(
                key = key,
                tenantSlug = target.tenant,
            )
            .bodyOrThrow()
            .photos
            .mapNotNull { photo ->
                // The medium thumbnail, falling back to the small one and then to the full
                // image: a gallery is looked at rather than scanned past, and a photo with no
                // thumbnail at all is still a photo the user took.
                val uri = photo.thumbnailUris?.medium
                    ?: photo.thumbnailUris?.small
                    ?: photo.uri
                PlantPhoto(
                    url = absoluteAgainst(target.baseUrl, uri),
                    isCover = photo.isCover,
                )
            }
            // Cover first, because that is the one the user recognises the plant by.
            .sortedByDescending { it.isCover }
    }

    override suspend fun phaseHistory(key: String): SectionOutcome<List<PlantPhase>> =
        section { target ->
            target.retrofit.create(PhasesApi::class.java)
                .getPhaseHistoryApiV1PlantInstancesPlantKeyPhasesHistoryGet(plantKey = key)
                .bodyOrThrow()
                .map {
                    PlantPhase(
                        name = it.phaseName,
                        startedAt = it.enteredAt.toString(),
                        endedAt = it.exitedAt?.toString(),
                    )
                }
                // Newest first: what the plant is doing now, and did last, is what a reader
                // opening this section is after.
                .sortedByDescending { it.startedAt.orEmpty() }
        }

    override suspend fun care(key: String): SectionOutcome<List<CareAction>> = section { target ->
        // The tenant-wide dashboard, filtered to this plant. There is no per-plant endpoint
        // for *open* reminders — the plant's own routes answer its profile and its confirmed
        // history — and the dashboard is a single call either way.
        //
        // Read as raw JSON for the reason the list reads it that way: `reminder_type` is a
        // generated enum, and a value this build has never heard of throws for the whole
        // response rather than for the one entry.
        target.retrofit.create(RawCareDashboardApi::class.java)
            .dashboard(tenantSlug = target.tenant)
            .bodyOrThrow()
            .mapNotNull { it.asCareAction() }
            .filter { (plantKey, _) -> plantKey == key }
            .map { (_, action) -> action }
            .sortedWith(
                compareBy<CareAction> { if (it.isOverdue) 0 else 1 }
                    .thenBy { it.dueDate ?: "9999-99-99" },
            )
    }

    /** The one location name this page needs, or `null` where it cannot be read. */
    private suspend fun Target.locationName(siteKey: String, locationKey: String): String? =
        runCatchingCancellable {
            retrofit.create(LocationsApi::class.java)
                .listLocationsApiV1TTenantSlugLocationsGet(tenantSlug = tenant, siteKey = siteKey)
                .bodyOrThrow()
                .firstOrNull { it.key == locationKey }
                ?.name
        }.getOrNull()

    /**
     * Runs one section's call and turns whatever it throws into that section's outcome.
     *
     * Shared because every section fails the same way and differs only in what it reads. The
     * three statuses that are told apart are the three the page acts on differently: 401/403
     * ends the page, 404 means this plant is gone, anything else is a retry.
     */
    private suspend fun <T> section(block: suspend (Target) -> T): SectionOutcome<T> =
        runCatchingCancellable {
            val target = target() ?: return SectionOutcome.Unavailable(NOT_CONNECTED)
            SectionOutcome.Loaded(block(target))
        }.getOrElse { failure ->
            when {
                failure !is HttpFailure -> SectionOutcome.Unavailable(
                    failure::class.simpleName.orEmpty(),
                )
                failure.status in CREDENTIAL_REFUSED -> SectionOutcome.Unauthorized
                failure.status == NOT_FOUND -> SectionOutcome.NotFound
                else -> SectionOutcome.Unavailable("the instance answered HTTP ${failure.status}")
            }
        }

    /** What a call needs: where to send it, whose tenant it is, and what to resolve URIs against. */
    private class Target(val retrofit: Retrofit, val tenant: String, val baseUrl: String)

    private suspend fun target(): Target? {
        val connection = connections.connection.first() ?: return null
        val credential = credentials.load()
        return Target(
            retrofit = apis.create(connection.baseUrl) { credential },
            tenant = connection.tenantSlug,
            baseUrl = connection.baseUrl,
        )
    }

    /** The care dashboard as raw JSON; see [care] for why it is not the generated API. */
    private interface RawCareDashboardApi {

        @GET("api/v1/t/{tenant_slug}/care-reminders/dashboard")
        suspend fun dashboard(@Path("tenant_slug") tenantSlug: String): Response<JsonArray>
    }

    private class HttpFailure(val status: Int) : Exception("HTTP $status")

    private companion object {

        const val NOT_CONNECTED = "the app is not connected to an instance"
        const val NOT_FOUND = 404

        /**
         * Both mean the stored credential will not open this page, and neither is fixed by
         * trying again: 401 is refused, 403 is authenticated with a scope that does not reach
         * this tenant.
         */
        val CREDENTIAL_REFUSED = setOf(401, 403)

        fun <T> Response<T>.bodyOrThrow(): T {
            if (!isSuccessful) throw HttpFailure(code())
            return body() ?: throw HttpFailure(code())
        }
    }
}
