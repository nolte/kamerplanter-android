package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.CreateInspectionResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.FeedbackRequest
import io.github.nolte.kamerplanter.core.network.generated.models.PestDetectionResponse
import io.github.nolte.kamerplanter.core.network.generated.models.PestDetectionStatusResponse

import okhttp3.MultipartBody

interface PestDetectionApi {
    /**
     * POST api/v1/t/{tenant_slug}/pests/detections/{detection_key}/create-inspection
     * Create Inspection
     * Create a REQ-010 inspection from a detection. Never a treatment (§0).
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param detectionKey Document key of the pest detection.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param plantKey Plant instance the inspection belongs to
     * @return [CreateInspectionResponse]
     */
    @POST("api/v1/t/{tenant_slug}/pests/detections/{detection_key}/create-inspection")
    suspend fun createInspectionApiV1TTenantSlugPestsDetectionsDetectionKeyCreateInspectionPost(@Path("detection_key") detectionKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Query("plant_key") plantKey: kotlin.String): Response<CreateInspectionResponse>

    /**
     * POST api/v1/t/{tenant_slug}/pests/plants/{plant_key}/detect
     * Detect Pests
     * Detect pests (Mode 1) and/or symptoms (Mode 2) in an uploaded photo.  JPEG/PNG, max 8 MB. EXIF is stripped before processing, the image is tiled (mandatory), and the result always carries a disclaimer and never persists the image. Cloud detection requires the &#x60;&#x60;pest_detection_cloud&#x60;&#x60; consent.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param plantKey Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param image 
     * @param language Language code for the returned finding labels and disclaimer. (optional, default to "de")
     * @return [PestDetectionResponse]
     */
    @Multipart
    @POST("api/v1/t/{tenant_slug}/pests/plants/{plant_key}/detect")
    suspend fun detectPestsApiV1TTenantSlugPestsPlantsPlantKeyDetectPost(@Path("plant_key") plantKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Part image: MultipartBody.Part, @Part("language") language: kotlin.String? = "de"): Response<PestDetectionResponse>

    /**
     * POST api/v1/t/{tenant_slug}/pests/detect
     * Detect Pests Global
     * Detect pests/symptoms in a photo without binding to a plant (REQ-044 §7).  Plant-agnostic entry point for the standalone pest-detection page. The image recognition is identical to the plant-bound flow — only the plant binding is dropped (&#x60;&#x60;plant_instance_key&#x3D;None&#x60;&#x60;), so no IPM inspection is suggested and the result is not attached to any plant history. The detection is still persisted (so the returned &#x60;&#x60;key&#x60;&#x60; powers HITL feedback) and always carries a disclaimer. Same feature gate, adapter resolution and consent rules as the plant-bound endpoint.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param image 
     * @param language Language code for the returned finding labels and disclaimer. (optional, default to "de")
     * @return [PestDetectionResponse]
     */
    @Multipart
    @POST("api/v1/t/{tenant_slug}/pests/detect")
    suspend fun detectPestsGlobalApiV1TTenantSlugPestsDetectPost(@Path("tenant_slug") tenantSlug: kotlin.String, @Part image: MultipartBody.Part, @Part("language") language: kotlin.String? = "de"): Response<PestDetectionResponse>

    /**
     * GET api/v1/t/{tenant_slug}/pests/plants/{plant_key}/history
     * Detection History
     * Return the recent pest detections for a plant (no images retained).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param plantKey Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param limit Maximum number of recent detections to return. (optional, default to 20)
     * @return [kotlin.collections.List<PestDetectionResponse>]
     */
    @GET("api/v1/t/{tenant_slug}/pests/plants/{plant_key}/history")
    suspend fun detectionHistoryApiV1TTenantSlugPestsPlantsPlantKeyHistoryGet(@Path("plant_key") plantKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Query("limit") limit: kotlin.Int? = 20): Response<kotlin.collections.List<PestDetectionResponse>>

    /**
     * GET api/v1/t/{tenant_slug}/pests/status
     * Pest Detection Status
     * Report which pest-detection adapter is active (or none → button hidden).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [PestDetectionStatusResponse]
     */
    @GET("api/v1/t/{tenant_slug}/pests/status")
    suspend fun pestDetectionStatusApiV1TTenantSlugPestsStatusGet(@Path("tenant_slug") tenantSlug: kotlin.String): Response<PestDetectionStatusResponse>

    /**
     * POST api/v1/t/{tenant_slug}/pests/detections/{detection_key}/feedback
     * Submit Feedback
     * Human-in-the-loop feedback: confirmed / wrong / was a beneficial (§5.3).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param detectionKey Document key of the pest detection.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param feedbackRequest 
     * @return [PestDetectionResponse]
     */
    @POST("api/v1/t/{tenant_slug}/pests/detections/{detection_key}/feedback")
    suspend fun submitFeedbackApiV1TTenantSlugPestsDetectionsDetectionKeyFeedbackPost(@Path("detection_key") detectionKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body feedbackRequest: FeedbackRequest): Response<PestDetectionResponse>

}
