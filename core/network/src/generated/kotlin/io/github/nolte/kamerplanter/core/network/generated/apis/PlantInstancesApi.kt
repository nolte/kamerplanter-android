package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.ActiveChannelResponse
import io.github.nolte.kamerplanter.core.network.generated.models.AssignNutrientPlanRequest
import io.github.nolte.kamerplanter.core.network.generated.models.AssignNutrientPlanStatusResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.PlantCreate
import io.github.nolte.kamerplanter.core.network.generated.models.PlantInstancesInPhaseResponse
import io.github.nolte.kamerplanter.core.network.generated.models.PlantResponse
import io.github.nolte.kamerplanter.core.network.generated.models.PlantRunSummaryResponse
import io.github.nolte.kamerplanter.core.network.generated.models.RemovePlantRequest
import io.github.nolte.kamerplanter.core.network.generated.models.SurvivalStatsResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ValidatePlantingRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ValidatePlantingResponse

interface PlantInstancesApi {
    /**
     * POST api/v1/t/{tenant_slug}/plant-instances/{key}/nutrient-plan
     * Assign Nutrient Plan
     * Assign a nutrient plan to a plant instance.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param assignNutrientPlanRequest 
     * @return [AssignNutrientPlanStatusResponse]
     */
    @POST("api/v1/t/{tenant_slug}/plant-instances/{key}/nutrient-plan")
    suspend fun assignNutrientPlanApiV1TTenantSlugPlantInstancesKeyNutrientPlanPost(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body assignNutrientPlanRequest: AssignNutrientPlanRequest): Response<AssignNutrientPlanStatusResponse>

    /**
     * POST api/v1/t/{tenant_slug}/plant-instances
     * Create Plant
     * Create a plant instance for the tenant.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param plantCreate 
     * @return [PlantResponse]
     */
    @POST("api/v1/t/{tenant_slug}/plant-instances")
    suspend fun createPlantApiV1TTenantSlugPlantInstancesPost(@Path("tenant_slug") tenantSlug: kotlin.String, @Body plantCreate: PlantCreate): Response<PlantResponse>

    /**
     * GET api/v1/t/{tenant_slug}/plant-instances/{key}/active-channels
     * Get Active Channels
     * List the active nutrient channels for a plant instance in the given week.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param currentWeek 1-based week within the current phase to evaluate.
     * @return [kotlin.collections.List<ActiveChannelResponse>]
     */
    @GET("api/v1/t/{tenant_slug}/plant-instances/{key}/active-channels")
    suspend fun getActiveChannelsApiV1TTenantSlugPlantInstancesKeyActiveChannelsGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Query("current_week") currentWeek: kotlin.Int): Response<kotlin.collections.List<ActiveChannelResponse>>

    /**
     * GET api/v1/t/{tenant_slug}/plant-instances/{key}/current-dosages
     * Get Current Dosages
     * Return the current per-channel dosages for a plant instance&#39;s active plan.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param currentWeek 1-based week within the current phase to compute dosages for.
     * @return [kotlinx.serialization.json.JsonElement]
     */
    @GET("api/v1/t/{tenant_slug}/plant-instances/{key}/current-dosages")
    suspend fun getCurrentDosagesApiV1TTenantSlugPlantInstancesKeyCurrentDosagesGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Query("current_week") currentWeek: kotlin.Int): Response<kotlinx.serialization.json.JsonElement>

    /**
     * GET api/v1/t/{tenant_slug}/plant-instances/{key}/nutrient-plan
     * Get Nutrient Plan
     * Return the nutrient plan assigned to a plant instance, or &#x60;&#x60;null&#x60;&#x60;.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [kotlinx.serialization.json.JsonElement]
     */
    @GET("api/v1/t/{tenant_slug}/plant-instances/{key}/nutrient-plan")
    suspend fun getNutrientPlanApiV1TTenantSlugPlantInstancesKeyNutrientPlanGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<kotlinx.serialization.json.JsonElement>

    /**
     * GET api/v1/t/{tenant_slug}/plant-instances/{key}
     * Get Plant
     * Return a single plant instance by key.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [PlantResponse]
     */
    @GET("api/v1/t/{tenant_slug}/plant-instances/{key}")
    suspend fun getPlantApiV1TTenantSlugPlantInstancesKeyGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<PlantResponse>

    /**
     * GET api/v1/t/{tenant_slug}/plant-instances/{key}/planting-runs
     * Get Plant Runs
     * List the planting runs a plant instance belongs to.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [kotlin.collections.List<PlantRunSummaryResponse>]
     */
    @GET("api/v1/t/{tenant_slug}/plant-instances/{key}/planting-runs")
    suspend fun getPlantRunsApiV1TTenantSlugPlantInstancesKeyPlantingRunsGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<kotlin.collections.List<PlantRunSummaryResponse>>

    /**
     * GET api/v1/t/{tenant_slug}/plant-instances/survival-stats
     * Get Survival Stats
     * Survival-rate / failure-cause analytics for the tenant&#39;s plants (REQ-003 G1).  Declared before &#x60;&#x60;/{key}&#x60;&#x60; so the literal path is not captured by the plant-key path parameter.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [SurvivalStatsResponse]
     */
    @GET("api/v1/t/{tenant_slug}/plant-instances/survival-stats")
    suspend fun getSurvivalStatsApiV1TTenantSlugPlantInstancesSurvivalStatsGet(@Path("tenant_slug") tenantSlug: kotlin.String): Response<SurvivalStatsResponse>

    /**
     * GET api/v1/t/{tenant_slug}/plant-instances
     * List Plants
     * List the tenant&#39;s plant instances (paginated).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param offset Number of items to skip from the start of the result set. (optional, default to 0)
     * @param limit Maximum number of items to return (1-200). (optional, default to 50)
     * @return [kotlin.collections.List<PlantResponse>]
     */
    @GET("api/v1/t/{tenant_slug}/plant-instances")
    suspend fun listPlantsApiV1TTenantSlugPlantInstancesGet(@Path("tenant_slug") tenantSlug: kotlin.String, @Query("offset") offset: kotlin.Int? = 0, @Query("limit") limit: kotlin.Int? = 50): Response<kotlin.collections.List<PlantResponse>>

    /**
     * GET api/v1/t/{tenant_slug}/plant-instances/by-phase-definition/{phase_definition_key}
     * List Plants In Phase Definition
     * List the tenant&#39;s *active* plant instances currently in a phase definition (FIX-01 R1/R8).  Read-only and tenant-scoped: only the caller&#39;s tenant&#39;s instances are returned (SEC-001), and only active ones (&#x60;&#x60;removed_on &#x3D;&#x3D; null&#x60;&#x60;). An empty list is a valid result (R4). Declared before &#x60;&#x60;/{key}&#x60;&#x60; so the two-segment literal path is matched here and not captured by the plant-key path parameter.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param phaseDefinitionKey Document key of the phase definition.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [PlantInstancesInPhaseResponse]
     */
    @GET("api/v1/t/{tenant_slug}/plant-instances/by-phase-definition/{phase_definition_key}")
    suspend fun listPlantsInPhaseDefinitionApiV1TTenantSlugPlantInstancesByPhaseDefinitionPhaseDefinitionKeyGet(@Path("phase_definition_key") phaseDefinitionKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<PlantInstancesInPhaseResponse>

    /**
     * DELETE api/v1/t/{tenant_slug}/plant-instances/{key}/nutrient-plan
     * Remove Nutrient Plan
     * Remove the nutrient plan assigned to a plant instance.
     * Responses:
     *  - 204: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [Unit]
     */
    @DELETE("api/v1/t/{tenant_slug}/plant-instances/{key}/nutrient-plan")
    suspend fun removeNutrientPlanApiV1TTenantSlugPlantInstancesKeyNutrientPlanDelete(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<Unit>

    /**
     * POST api/v1/t/{tenant_slug}/plant-instances/{key}/remove
     * Remove Plant
     * Mark a plant instance as removed, recording the termination reason.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param removePlantRequest  (optional)
     * @return [PlantResponse]
     */
    @POST("api/v1/t/{tenant_slug}/plant-instances/{key}/remove")
    suspend fun removePlantApiV1TTenantSlugPlantInstancesKeyRemovePost(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body removePlantRequest: RemovePlantRequest? = null): Response<PlantResponse>

    /**
     * PUT api/v1/t/{tenant_slug}/plant-instances/{key}
     * Update Plant
     * Update the user-editable fields of a plant instance.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the plant instance.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param plantCreate 
     * @return [PlantResponse]
     */
    @PUT("api/v1/t/{tenant_slug}/plant-instances/{key}")
    suspend fun updatePlantApiV1TTenantSlugPlantInstancesKeyPut(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body plantCreate: PlantCreate): Response<PlantResponse>

    /**
     * POST api/v1/t/{tenant_slug}/plant-instances/slots/{slot_key}/validate-planting
     * Validate Planting
     * Validate whether a species may be planted into a slot.  A &#x60;&#x60;slot_key&#x60;&#x60; belonging to another tenant produces no findings — the slot history and neighbourhood reads behind this are tenant-scoped (#927).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param slotKey Document key of the slot to plant into.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param validatePlantingRequest 
     * @return [ValidatePlantingResponse]
     */
    @POST("api/v1/t/{tenant_slug}/plant-instances/slots/{slot_key}/validate-planting")
    suspend fun validatePlantingApiV1TTenantSlugPlantInstancesSlotsSlotKeyValidatePlantingPost(@Path("slot_key") slotKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body validatePlantingRequest: ValidatePlantingRequest): Response<ValidatePlantingResponse>

}
