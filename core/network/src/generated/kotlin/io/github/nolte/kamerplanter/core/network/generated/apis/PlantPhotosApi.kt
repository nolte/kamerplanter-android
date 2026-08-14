package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.AssessmentAdaptersResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.PlantPhotoAssessRequest
import io.github.nolte.kamerplanter.core.network.generated.models.PlantPhotoListResponse
import io.github.nolte.kamerplanter.core.network.generated.models.PlantPhotoMetadataUpdate
import io.github.nolte.kamerplanter.core.network.generated.models.PlantPhotoResponse

import okhttp3.MultipartBody

interface PlantPhotosApi {
    /**
     * POST api/v1/t/{tenant_slug}/plant-instances/{key}/photos/{attachment_id}/assess
     * Assess Plant Photo
     * Assess a gallery photo&#39;s recognition quality and persist the verdict (REQ-034 §4a).  &#x60;&#x60;Action.UPDATE&#x60;&#x60; because a verdict is persisted on the photo — a &#x60;&#x60;viewer&#x60;&#x60; may see an existing assessment (it ships in the photo listing) but not trigger a new one (AC-13, §4a.3). The chosen adapter, the consent gate (external path, full mode) and the rate limit are enforced downstream; an unusable adapter surfaces as a 409.  SEC-004: re-assessing the same unchanged photo with the same adapter returns the cached verdict and skips a fresh (cost-bearing) external call. Pass &#x60;&#x60;force: true&#x60;&#x60; to re-run deliberately.  REQ-034 §4a.3 / SEC-002: in Light mode (REQ-027) there is no consent subsystem, so the external recognition path is *not* gated by a server-side consent here — it is unlocked solely by the operator opt-in (&#x60;&#x60;IDENTIFICATION_EXTERNAL_IN_LIGHT_MODE&#x60;&#x60;) and disabled by default (409 otherwise). The third-party-transfer transparency/opt-in is then surfaced client-side.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param attachmentId Attachment id of the gallery photo.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param plantPhotoAssessRequest 
     * @return [PlantPhotoResponse]
     */
    @POST("api/v1/t/{tenant_slug}/plant-instances/{key}/photos/{attachment_id}/assess")
    suspend fun assessPlantPhotoApiV1TTenantSlugPlantInstancesKeyPhotosAttachmentIdAssessPost(@Path("key") key: kotlin.String, @Path("attachment_id") attachmentId: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body plantPhotoAssessRequest: PlantPhotoAssessRequest): Response<PlantPhotoResponse>

    /**
     * DELETE api/v1/t/{tenant_slug}/plant-instances/{key}/photos/{attachment_id}
     * Delete Plant Photo
     * Hard-delete a gallery photo and unlink it (REQ-034 §7 / AC-07).
     * Responses:
     *  - 204: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param attachmentId Attachment id of the gallery photo.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [Unit]
     */
    @DELETE("api/v1/t/{tenant_slug}/plant-instances/{key}/photos/{attachment_id}")
    suspend fun deletePlantPhotoApiV1TTenantSlugPlantInstancesKeyPhotosAttachmentIdDelete(@Path("key") key: kotlin.String, @Path("attachment_id") attachmentId: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<Unit>

    /**
     * GET api/v1/t/{tenant_slug}/plant-instances/{key}/photos/assess/adapters
     * List Assessment Adapters
     * List the recognition adapters selectable for a quality check (REQ-034 §4a.1).  Read-permission only so viewers can see which adapters exist (even though they may not trigger an assessment). Disabled adapters (e.g. DINOv2 before Phase 2) are still returned so the UI can offer them greyed-out instead of hiding the option — no dead code.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [AssessmentAdaptersResponse]
     */
    @GET("api/v1/t/{tenant_slug}/plant-instances/{key}/photos/assess/adapters")
    suspend fun listAssessmentAdaptersApiV1TTenantSlugPlantInstancesKeyPhotosAssessAdaptersGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<AssessmentAdaptersResponse>

    /**
     * GET api/v1/t/{tenant_slug}/plant-instances/{key}/photos
     * List Plant Photos
     * List a plant instance&#39;s gallery photos, newest first (REQ-034 §7).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [PlantPhotoListResponse]
     */
    @GET("api/v1/t/{tenant_slug}/plant-instances/{key}/photos")
    suspend fun listPlantPhotosApiV1TTenantSlugPlantInstancesKeyPhotosGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<PlantPhotoListResponse>

    /**
     * PUT api/v1/t/{tenant_slug}/plant-instances/{key}/photos/{attachment_id}/cover
     * Set Cover Photo
     * Mark a gallery photo as the instance cover (REQ-034 §7 / AC-06).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param attachmentId Attachment id of the gallery photo.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [PlantPhotoListResponse]
     */
    @PUT("api/v1/t/{tenant_slug}/plant-instances/{key}/photos/{attachment_id}/cover")
    suspend fun setCoverPhotoApiV1TTenantSlugPlantInstancesKeyPhotosAttachmentIdCoverPut(@Path("key") key: kotlin.String, @Path("attachment_id") attachmentId: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<PlantPhotoListResponse>

    /**
     * PATCH api/v1/t/{tenant_slug}/plant-instances/{key}/photos/{attachment_id}
     * Update Plant Photo Metadata
     * Patch a gallery photo&#39;s caption / capture date (REQ-034 §2.1 v1.2).  True PATCH: only the fields present in the request body are changed. An omitted field is left untouched; an explicit &#x60;&#x60;null&#x60;&#x60; clears it. The omitted-vs-null distinction is recovered from &#x60;&#x60;model_fields_set&#x60;&#x60; and forwarded to the service as the &#x60;&#x60;UNSET&#x60;&#x60; sentinel.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param attachmentId Attachment id of the gallery photo.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param plantPhotoMetadataUpdate 
     * @return [PlantPhotoResponse]
     */
    @PATCH("api/v1/t/{tenant_slug}/plant-instances/{key}/photos/{attachment_id}")
    suspend fun updatePlantPhotoMetadataApiV1TTenantSlugPlantInstancesKeyPhotosAttachmentIdPatch(@Path("key") key: kotlin.String, @Path("attachment_id") attachmentId: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body plantPhotoMetadataUpdate: PlantPhotoMetadataUpdate): Response<PlantPhotoResponse>

    /**
     * POST api/v1/t/{tenant_slug}/plant-instances/{key}/photos
     * Upload Plant Photo
     * Upload a gallery photo and link it to the plant instance (REQ-034 §7).  The per-instance quota (&#x60;&#x60;STORAGE_MAX_PHOTOS_PER_INSTANCE&#x60;&#x60;) is enforced *before* any bytes are written (AC-15), so a rejected upload never leaves orphan storage objects.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param file 
     * @return [PlantPhotoResponse]
     */
    @Multipart
    @POST("api/v1/t/{tenant_slug}/plant-instances/{key}/photos")
    suspend fun uploadPlantPhotoApiV1TTenantSlugPlantInstancesKeyPhotosPost(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Part file: MultipartBody.Part): Response<PlantPhotoResponse>

}
