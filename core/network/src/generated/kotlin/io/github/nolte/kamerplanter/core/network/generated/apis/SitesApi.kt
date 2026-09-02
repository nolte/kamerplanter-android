package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.LiveStateResponse
import io.github.nolte.kamerplanter.core.network.generated.models.LocationTreeNode
import io.github.nolte.kamerplanter.core.network.generated.models.SensorCreate
import io.github.nolte.kamerplanter.core.network.generated.models.SensorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.SiteCreate
import io.github.nolte.kamerplanter.core.network.generated.models.SiteHardinessResponse
import io.github.nolte.kamerplanter.core.network.generated.models.SiteResponse

interface SitesApi {
    /**
     * POST api/v1/t/{tenant_slug}/sites
     * Create Site
     * Create a site for the tenant.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param siteCreate 
     * @return [SiteResponse]
     */
    @POST("api/v1/t/{tenant_slug}/sites")
    suspend fun createSiteApiV1TTenantSlugSitesPost(@Path("tenant_slug") tenantSlug: kotlin.String, @Body siteCreate: SiteCreate): Response<SiteResponse>

    /**
     * POST api/v1/t/{tenant_slug}/sites/{key}/sensors
     * Create Site Sensor
     * Attach a sensor to a site.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the site.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param sensorCreate 
     * @return [SensorResponse]
     */
    @POST("api/v1/t/{tenant_slug}/sites/{key}/sensors")
    suspend fun createSiteSensorApiV1TTenantSlugSitesKeySensorsPost(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body sensorCreate: SensorCreate): Response<SensorResponse>

    /**
     * DELETE api/v1/t/{tenant_slug}/sites/{key}
     * Delete Site
     * Delete a site.
     * Responses:
     *  - 204: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the site.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [Unit]
     */
    @DELETE("api/v1/t/{tenant_slug}/sites/{key}")
    suspend fun deleteSiteApiV1TTenantSlugSitesKeyDelete(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<Unit>

    /**
     * GET api/v1/t/{tenant_slug}/sites/{key}/location-tree
     * Get Location Tree
     * Return the site&#39;s location hierarchy with slot, plant and tank counts.  The service verifies the site against &#x60;&#x60;tenant_key&#x60;&#x60; itself and scopes the traversal with it (#927), so the redundant pre-check is gone.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the site.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [kotlin.collections.List<LocationTreeNode>]
     */
    @GET("api/v1/t/{tenant_slug}/sites/{key}/location-tree")
    suspend fun getLocationTreeApiV1TTenantSlugSitesKeyLocationTreeGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<kotlin.collections.List<LocationTreeNode>>

    /**
     * GET api/v1/t/{tenant_slug}/sites/{key}
     * Get Site
     * Return a single site by key.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the site.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [SiteResponse]
     */
    @GET("api/v1/t/{tenant_slug}/sites/{key}")
    suspend fun getSiteApiV1TTenantSlugSitesKeyGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<SiteResponse>

    /**
     * GET api/v1/t/{tenant_slug}/sites/{key}/hardiness
     * Get Site Hardiness
     * Return the site&#39;s resolved hardiness zone and the matching catalog entry.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the site.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [SiteHardinessResponse]
     */
    @GET("api/v1/t/{tenant_slug}/sites/{key}/hardiness")
    suspend fun getSiteHardinessApiV1TTenantSlugSitesKeyHardinessGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<SiteHardinessResponse>

    /**
     * GET api/v1/t/{tenant_slug}/sites/{key}/sensors
     * Get Site Sensors
     * List the sensors attached to a site.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the site.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [kotlin.collections.List<SensorResponse>]
     */
    @GET("api/v1/t/{tenant_slug}/sites/{key}/sensors")
    suspend fun getSiteSensorsApiV1TTenantSlugSitesKeySensorsGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<kotlin.collections.List<SensorResponse>>

    /**
     * GET api/v1/t/{tenant_slug}/sites/{key}/sensors/live
     * Get Site Sensors Live
     * Return the live sensor readings for a site.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the site.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [LiveStateResponse]
     */
    @GET("api/v1/t/{tenant_slug}/sites/{key}/sensors/live")
    suspend fun getSiteSensorsLiveApiV1TTenantSlugSitesKeySensorsLiveGet(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<LiveStateResponse>

    /**
     * GET api/v1/t/{tenant_slug}/sites
     * List Sites
     * List the tenant&#39;s sites (paginated).
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
     * @return [kotlin.collections.List<SiteResponse>]
     */
    @GET("api/v1/t/{tenant_slug}/sites")
    suspend fun listSitesApiV1TTenantSlugSitesGet(@Path("tenant_slug") tenantSlug: kotlin.String, @Query("offset") offset: kotlin.Int? = 0, @Query("limit") limit: kotlin.Int? = 50): Response<kotlin.collections.List<SiteResponse>>

    /**
     * POST api/v1/t/{tenant_slug}/sites/{key}/resolve-hardiness-zone
     * Resolve Site Hardiness Zone
     * Derive the site&#39;s hardiness zone from its REQ-041 climate normals.  Climate normals are fetched on demand from the site&#39;s GPS coordinates when not already cached, so this works immediately for a site that just got GPS (no waiting for the monthly climate-normals beat). A manually set zone is preserved unless &#x60;&#x60;force&#x3D;true&#x60;&#x60;. Returns 422 when no climate normals with a usable minimum temperature can be obtained (e.g. the site has no GPS).  State-changing (mutates &#x60;&#x60;Site.hardiness_zone&#x60;&#x60;), so it requires at least the &#x60;&#x60;grower&#x60;&#x60; role — a &#x60;&#x60;viewer&#x60;&#x60; cannot trigger a zone derivation (SEC-001).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the site.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param force Re-derive even when a manual zone is already set. (optional, default to false)
     * @return [SiteHardinessResponse]
     */
    @POST("api/v1/t/{tenant_slug}/sites/{key}/resolve-hardiness-zone")
    suspend fun resolveSiteHardinessZoneApiV1TTenantSlugSitesKeyResolveHardinessZonePost(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Query("force") force: kotlin.Boolean? = false): Response<SiteHardinessResponse>

    /**
     * PUT api/v1/t/{tenant_slug}/sites/{key}
     * Update Site
     * Update a site, preserving its resolved hardiness zone unless overridden.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the site.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param siteCreate 
     * @return [SiteResponse]
     */
    @PUT("api/v1/t/{tenant_slug}/sites/{key}")
    suspend fun updateSiteApiV1TTenantSlugSitesKeyPut(@Path("key") key: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body siteCreate: SiteCreate): Response<SiteResponse>

}
