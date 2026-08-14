package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.DiaryEntryCreateRequest
import io.github.nolte.kamerplanter.core.network.generated.models.DiaryEntryResponse
import io.github.nolte.kamerplanter.core.network.generated.models.DiaryEntryUpdateRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.PlantEnvironmentPreviewResponse

interface PlantDiaryApi {
    /**
     * DELETE api/v1/t/{tenant_slug}/plant-instances/{key}/diary/{entry_key}/request-analysis
     * Cancel Plant Diary Entry Analysis
     * Withdraw the marking — &#x60;&#x60;requested → none&#x60;&#x60;, only while unclaimed (AK-03).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param entryKey Document key of the diary entry.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [DiaryEntryResponse]
     */
    @DELETE("api/v1/t/{tenant_slug}/plant-instances/{key}/diary/{entry_key}/request-analysis")
    suspend fun cancelPlantDiaryEntryAnalysisApiV1TTenantSlugPlantInstancesKeyDiaryEntryKeyRequestAnalysisDelete(@Path("key") key: kotlin.String, @Path("entry_key") entryKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<DiaryEntryResponse>

    /**
     * POST api/v1/t/{tenant_slug}/plant-instances/{key}/diary
     * Create Plant Diary Entry
     * Create a diary entry for a plant instance (REQ-050 §2.5.1).  No &#x60;&#x60;run_key&#x60;&#x60; is passed: this path is the one for a plant *without* a run, and requiring run membership here would reinstate exactly the gap it closes.  &#x60;&#x60;body.photo_refs&#x60;&#x60; is checked against the attachment catalogue by the service before anything is stored (SEC-003).  &#x60;&#x60;body.capture_environment&#x60;&#x60; is popped out of the payload rather than passed through: it is an instruction to the *service*, not a field of the entry, and the snapshot it governs is resolved server-side (REQ-013 §2.3a). Nothing a client sends ever reaches &#x60;&#x60;entry.environment&#x60;&#x60;.
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
     * @param diaryEntryCreateRequest 
     * @return [DiaryEntryResponse]
     */
    @POST("api/v1/t/{tenant_slug}/plant-instances/{key}/diary")
    suspend fun createPlantDiaryEntryApiV1TTenantSlugPlantInstancesKeyDiaryPost(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body diaryEntryCreateRequest: DiaryEntryCreateRequest): Response<DiaryEntryResponse>

    /**
     * DELETE api/v1/t/{tenant_slug}/plant-instances/{key}/diary/{entry_key}
     * Delete Plant Diary Entry
     * Delete a diary entry of this plant, along with its analysis result (AK-23).
     * Responses:
     *  - 204: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param entryKey Document key of the diary entry.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [Unit]
     */
    @DELETE("api/v1/t/{tenant_slug}/plant-instances/{key}/diary/{entry_key}")
    suspend fun deletePlantDiaryEntryApiV1TTenantSlugPlantInstancesKeyDiaryEntryKeyDelete(@Path("key") key: kotlin.String, @Path("entry_key") entryKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<Unit>

    /**
     * GET api/v1/t/{tenant_slug}/plant-instances/{key}/diary/{entry_key}
     * Get Plant Diary Entry
     * Return a single diary entry of this plant.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param entryKey Document key of the diary entry.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [DiaryEntryResponse]
     */
    @GET("api/v1/t/{tenant_slug}/plant-instances/{key}/diary/{entry_key}")
    suspend fun getPlantDiaryEntryApiV1TTenantSlugPlantInstancesKeyDiaryEntryKeyGet(@Path("key") key: kotlin.String, @Path("entry_key") entryKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<DiaryEntryResponse>

    /**
     * GET api/v1/t/{tenant_slug}/plant-instances/{key}/diary
     * List Plant Diary Entries
     * List one plant&#39;s diary entries, newest first (paginated).  The plant is resolved against the caller&#39;s tenant first, so the listing can never be reached for a foreign plant — the entries hang off the plant, and the plant is the tenant anchor.  Every row carries its own &#x60;&#x60;can_request_analysis&#x60;&#x60; (REQ-050 §7.2, AK-18a). The verdict depends on the *authorship* of each entry, so a shared garden&#39;s listing legitimately mixes &#x60;&#x60;true&#x60;&#x60; and &#x60;&#x60;false&#x60;&#x60; rows for one caller — which is why it is evaluated per entry and not once for the page.
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
     * @param offset Number of items to skip from the start of the result set. (optional, default to 0)
     * @param limit Maximum number of items to return (1-200). (optional, default to 50)
     * @return [kotlin.collections.List<DiaryEntryResponse>]
     */
    @GET("api/v1/t/{tenant_slug}/plant-instances/{key}/diary")
    suspend fun listPlantDiaryEntriesApiV1TTenantSlugPlantInstancesKeyDiaryGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Query("offset") offset: kotlin.Int? = 0, @Query("limit") limit: kotlin.Int? = 50): Response<kotlin.collections.List<DiaryEntryResponse>>

    /**
     * GET api/v1/t/{tenant_slug}/plant-instances/{key}/environment
     * Preview Plant Environment
     * Preview the environment snapshot for this plant (REQ-013 §2.3a).  The plant is resolved against the caller&#39;s tenant first, so a foreign key answers 404 rather than an empty snapshot — an empty snapshot would confirm that the key exists somewhere in the installation.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [PlantEnvironmentPreviewResponse]
     */
    @GET("api/v1/t/{tenant_slug}/plant-instances/{key}/environment")
    suspend fun previewPlantEnvironmentApiV1TTenantSlugPlantInstancesKeyEnvironmentGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<PlantEnvironmentPreviewResponse>

    /**
     * POST api/v1/t/{tenant_slug}/plant-instances/{key}/diary/{entry_key}/request-analysis
     * Request Plant Diary Entry Analysis
     * Mark a diary entry for AI analysis — &#x60;&#x60;none|completed|failed → requested&#x60;&#x60;.  Role, consent, authorship and Light-mode handling are decided by :meth:&#x60;PlantDiaryService.request_analysis&#x60;, which is the same function the overview uses to compute &#x60;&#x60;can_request_analysis&#x60;&#x60;. That flag is a display aid; this endpoint is the authorisation, and it re-evaluates the rule unconditionally (REQ-050 AK-18a).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param entryKey Document key of the diary entry.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [DiaryEntryResponse]
     */
    @POST("api/v1/t/{tenant_slug}/plant-instances/{key}/diary/{entry_key}/request-analysis")
    suspend fun requestPlantDiaryEntryAnalysisApiV1TTenantSlugPlantInstancesKeyDiaryEntryKeyRequestAnalysisPost(@Path("key") key: kotlin.String, @Path("entry_key") entryKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<DiaryEntryResponse>

    /**
     * PUT api/v1/t/{tenant_slug}/plant-instances/{key}/diary/{entry_key}
     * Update Plant Diary Entry
     * Update a diary entry of this plant.  The analysis fields are not part of &#x60;&#x60;DiaryEntryUpdateRequest&#x60;&#x60; and the service refuses them anyway: a state change never travels through the generic update (REQ-013 §4.7, REQ-050 §2.2).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param entryKey Document key of the diary entry.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param diaryEntryUpdateRequest 
     * @return [DiaryEntryResponse]
     */
    @PUT("api/v1/t/{tenant_slug}/plant-instances/{key}/diary/{entry_key}")
    suspend fun updatePlantDiaryEntryApiV1TTenantSlugPlantInstancesKeyDiaryEntryKeyPut(@Path("key") key: kotlin.String, @Path("entry_key") entryKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body diaryEntryUpdateRequest: DiaryEntryUpdateRequest): Response<DiaryEntryResponse>

}
