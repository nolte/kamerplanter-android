package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.HistoryEntryResponse
import io.github.nolte.kamerplanter.core.network.generated.models.IdentifyResponse
import io.github.nolte.kamerplanter.core.network.generated.models.LinkInstanceRequest
import io.github.nolte.kamerplanter.core.network.generated.models.LinkInstanceResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ReferenceContributionResponse
import io.github.nolte.kamerplanter.core.network.generated.models.SelectResultResponse

import okhttp3.MultipartBody

interface IdentificationApi {
    /**
     * POST api/v1/t/{tenant_slug}/identification/reference
     * Contribute Reference
     * Reuse an identification photo as a DINOv2 few-shot recognition reference.  Issue #447 — opt-in step of the \&quot;reuse identification photo\&quot; flow. Only available when the self-hosted DINOv2 adapter is configured (&#x60;&#x60;inference_service_enabled&#x60;&#x60;); the external Pl@ntNet path has no local reference index, so the frontend hides the toggle there.  Security (SEC-001..006 review):  * The contribution is written **quarantined** (&#x60;&#x60;is_active&#x3D;False&#x60;&#x60;,   &#x60;&#x60;source&#x3D;\&quot;user_contributed\&quot;&#x60;&#x60;) — it does NOT affect the active, global   recognition index until a platform admin activates it in the reference-   image curation view. A cross-tenant contribution therefore cannot silently   change what other tenants&#39; identifications return. * At least the &#x60;&#x60;grower&#x60;&#x60; role is required — a &#x60;&#x60;viewer&#x60;&#x60; cannot contribute. * &#x60;&#x60;species_key&#x60;&#x60; is validated server-side and the scientific name is derived   from the master record (any client-supplied name is ignored). A per-user   daily quota, image dedup, upload size/type/decode/bomb validation and   contributor provenance are enforced in the service / here.  The photo is embedded locally; only the embedding + provenance are indexed — the original image is never persisted (REQ-029-A §4.4) and no third-party egress happens on this self-hosted path.
     * Responses:
     *  - 202: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param image 
     * @param speciesKey Resolved species key to attach the reference to
     * @return [ReferenceContributionResponse]
     */
    @Multipart
    @POST("api/v1/t/{tenant_slug}/identification/reference")
    suspend fun contributeReferenceApiV1TTenantSlugIdentificationReferencePost(@Path("tenant_slug") tenantSlug: kotlin.String, @Part image: MultipartBody.Part, @Part("species_key") speciesKey: kotlin.String): Response<ReferenceContributionResponse>

    /**
     * GET api/v1/t/{tenant_slug}/identification/history
     * Identification History
     * Return the current user&#39;s recent identification requests (no images).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param limit Maximum number of history entries to return. (optional, default to 20)
     * @return [kotlin.collections.List<HistoryEntryResponse>]
     */
    @GET("api/v1/t/{tenant_slug}/identification/history")
    suspend fun identificationHistoryApiV1TTenantSlugIdentificationHistoryGet(@Path("tenant_slug") tenantSlug: kotlin.String, @Query("limit") limit: kotlin.Int? = 20): Response<kotlin.collections.List<HistoryEntryResponse>>

    /**
     * POST api/v1/t/{tenant_slug}/identification/identify
     * Identify Plant
     * Identify a plant from an uploaded image (JPEG/PNG, max 5 MB).  Consent &#x60;&#x60;plant_identification&#x60;&#x60; is a hard precondition: the photo is sent to Pl@ntNet (Phase-1 primary), EXIF-stripped and normalized beforehand, and is never persisted. Returns rank-sorted suggestions for explicit selection.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param image 
     * @param language Preferred language for the returned suggestions (ISO code). (optional, default to "de")
     * @param organ leaf, flower, fruit, bark, habit, auto (optional, default to "auto")
     * @return [IdentifyResponse]
     */
    @Multipart
    @POST("api/v1/t/{tenant_slug}/identification/identify")
    suspend fun identifyPlantApiV1TTenantSlugIdentificationIdentifyPost(@Path("tenant_slug") tenantSlug: kotlin.String, @Part image: MultipartBody.Part, @Part("language") language: kotlin.String? = "de", @Part("organ") organ: kotlin.String? = "auto"): Response<IdentifyResponse>

    /**
     * POST api/v1/t/{tenant_slug}/identification/{request_key}/instance
     * Link Plant Instance
     * Link an identification request to the plant instance created from it (#630).  Persists the reference so the identification history can surface (and link to) the instance the user turned this result into, surviving reloads. Both the request and the target instance are tenant-checked in the service; a missing/foreign record on either side is a 404 (no cross-tenant leakage).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param requestKey Document key of the identification request.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param linkInstanceRequest 
     * @return [LinkInstanceResponse]
     */
    @POST("api/v1/t/{tenant_slug}/identification/{request_key}/instance")
    suspend fun linkPlantInstanceApiV1TTenantSlugIdentificationRequestKeyInstancePost(@Path("request_key") requestKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body linkInstanceRequest: LinkInstanceRequest): Response<LinkInstanceResponse>

    /**
     * POST api/v1/t/{tenant_slug}/identification/{request_key}/select
     * Select Result
     * Persist the user&#39;s explicit candidate choice (REQ-029-A §0.1.1 point 3).  The returned &#x60;&#x60;matched_species_key&#x60;&#x60; / scientific name drives the &#39;create plant&#39; step (PlantInstance + species link). No silent auto-create.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param requestKey Document key of the identification request.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param selectedRank 1-based rank of the chosen suggestion.
     * @return [SelectResultResponse]
     */
    @POST("api/v1/t/{tenant_slug}/identification/{request_key}/select")
    suspend fun selectResultApiV1TTenantSlugIdentificationRequestKeySelectPost(@Path("request_key") requestKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Query("selected_rank") selectedRank: kotlin.Int): Response<SelectResultResponse>

}
