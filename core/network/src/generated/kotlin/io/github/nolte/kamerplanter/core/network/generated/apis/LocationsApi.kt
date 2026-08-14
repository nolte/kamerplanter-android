package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.FrostWarningResponse
import io.github.nolte.kamerplanter.core.network.generated.models.LiveStateResponse
import io.github.nolte.kamerplanter.core.network.generated.models.LocationCreate
import io.github.nolte.kamerplanter.core.network.generated.models.LocationResponse
import io.github.nolte.kamerplanter.core.network.generated.models.SensorCreate
import io.github.nolte.kamerplanter.core.network.generated.models.SensorResponse

interface LocationsApi {
    /**
     * POST api/v1/t/{tenant_slug}/locations
     * Create Location
     * Create a location within a site.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param locationCreate 
     * @return [LocationResponse]
     */
    @POST("api/v1/t/{tenant_slug}/locations")
    suspend fun createLocationApiV1TTenantSlugLocationsPost(@Path("tenant_slug") tenantSlug: kotlin.String, @Body locationCreate: LocationCreate): Response<LocationResponse>

    /**
     * POST api/v1/t/{tenant_slug}/locations/{key}/sensors
     * Create Location Sensor
     * Attach a sensor to a location.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the location.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param sensorCreate 
     * @return [SensorResponse]
     */
    @POST("api/v1/t/{tenant_slug}/locations/{key}/sensors")
    suspend fun createLocationSensorApiV1TTenantSlugLocationsKeySensorsPost(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body sensorCreate: SensorCreate): Response<SensorResponse>

    /**
     * DELETE api/v1/t/{tenant_slug}/locations/{key}
     * Delete Location
     * Delete a location.
     * Responses:
     *  - 204: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the location.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [Unit]
     */
    @DELETE("api/v1/t/{tenant_slug}/locations/{key}")
    suspend fun deleteLocationApiV1TTenantSlugLocationsKeyDelete(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<Unit>

    /**
     * GET api/v1/t/{tenant_slug}/locations/{key}
     * Get Location
     * Return a single location by key.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the location.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [LocationResponse]
     */
    @GET("api/v1/t/{tenant_slug}/locations/{key}")
    suspend fun getLocationApiV1TTenantSlugLocationsKeyGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<LocationResponse>

    /**
     * GET api/v1/t/{tenant_slug}/locations/{key}/frost-warning
     * Get Location Frost Warning
     * Reactive frost-warning state for a location (Home Assistant read path).  Backs &#x60;&#x60;binary_sensor.kp_{location}_frost_warning&#x60;&#x60;: the HA coordinator learns which locations to surface from &#x60;&#x60;/ha-publish/enabled-keys/location&#x60;&#x60; (opt-in) and polls this route per location. The reactive &#x60;&#x60;frost_warning&#x60;&#x60; is derived from the location&#39;s latest ambient temperature; it is &#x60;&#x60;null&#x60;&#x60; when no temperature reading is available.  The proactive forecast early-warning is served once per site — not per location — via &#x60;&#x60;GET /sites/{site_key}/weather-forecast&#x60;&#x60; (Issue #409, F1), so a site with N locations no longer triggers N identical forecast reads on this hot path.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the location.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [FrostWarningResponse]
     */
    @GET("api/v1/t/{tenant_slug}/locations/{key}/frost-warning")
    suspend fun getLocationFrostWarningApiV1TTenantSlugLocationsKeyFrostWarningGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<FrostWarningResponse>

    /**
     * GET api/v1/t/{tenant_slug}/locations/{key}/sensors
     * Get Location Sensors
     * List the sensors attached to a location.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the location.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [kotlin.collections.List<SensorResponse>]
     */
    @GET("api/v1/t/{tenant_slug}/locations/{key}/sensors")
    suspend fun getLocationSensorsApiV1TTenantSlugLocationsKeySensorsGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<kotlin.collections.List<SensorResponse>>

    /**
     * GET api/v1/t/{tenant_slug}/locations/{key}/sensors/live
     * Get Location Sensors Live
     * Return the latest live readings for a location&#39;s sensors.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the location.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [LiveStateResponse]
     */
    @GET("api/v1/t/{tenant_slug}/locations/{key}/sensors/live")
    suspend fun getLocationSensorsLiveApiV1TTenantSlugLocationsKeySensorsLiveGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<LiveStateResponse>

    /**
     * GET api/v1/t/{tenant_slug}/locations/{key}/children
     * List Location Children
     * List the child locations of a location.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the parent location.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [kotlin.collections.List<LocationResponse>]
     */
    @GET("api/v1/t/{tenant_slug}/locations/{key}/children")
    suspend fun listLocationChildrenApiV1TTenantSlugLocationsKeyChildrenGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<kotlin.collections.List<LocationResponse>>

    /**
     * GET api/v1/t/{tenant_slug}/locations
     * List Locations
     * List a site&#39;s locations, or the children of a parent location.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param siteKey Document key of the site to list locations for.
     * @param parentLocationKey If set, list the child locations of this parent instead. (optional)
     * @return [kotlin.collections.List<LocationResponse>]
     */
    @GET("api/v1/t/{tenant_slug}/locations")
    suspend fun listLocationsApiV1TTenantSlugLocationsGet(@Path("tenant_slug") tenantSlug: kotlin.String, @Query("site_key") siteKey: kotlin.String, @Query("parent_location_key") parentLocationKey: kotlin.String? = null): Response<kotlin.collections.List<LocationResponse>>

    /**
     * PUT api/v1/t/{tenant_slug}/locations/{key}
     * Update Location
     * Update a location.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the location.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param locationCreate 
     * @return [LocationResponse]
     */
    @PUT("api/v1/t/{tenant_slug}/locations/{key}")
    suspend fun updateLocationApiV1TTenantSlugLocationsKeyPut(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body locationCreate: LocationCreate): Response<LocationResponse>

}
