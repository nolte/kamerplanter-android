package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.SpeciesCreate
import io.github.nolte.kamerplanter.core.network.generated.models.SpeciesListResponse
import io.github.nolte.kamerplanter.core.network.generated.models.SpeciesReferenceImagesResponse
import io.github.nolte.kamerplanter.core.network.generated.models.SpeciesResponse

interface SpeciesApi {
    /**
     * POST api/v1/species
     * Create Species
     * Create a tenant-owned species master record.  Role-gated in the service since SEC-005 (#1113), mirroring the update/delete wiring: a viewer of the active tenant is refused with 403, a grower or lead may create, and a platform admin may curate regardless of domain rank.  Two tenant-bearing dependencies on purpose, and they answer different questions. &#x60;&#x60;tenant_key&#x60;&#x60; (:func:&#x60;~app.common.auth.get_creating_tenant_key&#x60;) is the **ownership stamp**; &#x60;&#x60;ctx&#x60;&#x60; supplies only the caller&#39;s **role** in that same tenant. They cannot disagree — both come from the one &#x60;&#x60;_resolve_active_tenant&#x60;&#x60; — and keeping the stamp on the alias is what stops the F-3 back-compat dependency (and the tests overriding it) from quietly becoming inert.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param speciesCreate 
     * @param xActiveTenant Slug of the tenant the caller is acting in, for global (path-less) tenant-aware routes such as the species and botanical-family catalogues (ADR-009, REQ-049 §2.11). Omit it to act in your personal tenant. A slug you hold no active membership in is refused with 403. (optional)
     * @return [SpeciesResponse]
     */
    @POST("api/v1/species")
    suspend fun createSpeciesApiV1SpeciesPost(@Body speciesCreate: SpeciesCreate, @Header("X-Active-Tenant") xActiveTenant: kotlin.String? = null): Response<SpeciesResponse>

    /**
     * DELETE api/v1/species/{key}
     * Delete Species
     * Delete a species master record.  Same three-way gate as update (SEC-002, #808), with the stricter delete role boundary: a *foreign* species answers 404, a *global* seed row is deletable only by a platform admin, and deleting an *own* species requires a lead (the irreversibility boundary, REQ-049 §2.3).
     * Responses:
     *  - 204: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the species.
     * @param xActiveTenant Slug of the tenant the caller is acting in, for global (path-less) tenant-aware routes such as the species and botanical-family catalogues (ADR-009, REQ-049 §2.11). Omit it to act in your personal tenant. A slug you hold no active membership in is refused with 403. (optional)
     * @return [Unit]
     */
    @DELETE("api/v1/species/{key}")
    suspend fun deleteSpeciesApiV1SpeciesKeyDelete(@Path("key") key: kotlin.String, @Header("X-Active-Tenant") xActiveTenant: kotlin.String? = null): Response<Unit>

    /**
     * GET api/v1/species/{key}
     * Get Species
     * Return a single species by key.  Tenant-aware (SEC-001, #808): the caller&#39;s active tenant is threaded into :meth:&#x60;SpeciesService.get_species&#x60;, so the global seed catalogue and the caller&#39;s own species resolve, but a *foreign* tenant&#39;s species answers 404 — the by-key endpoint is no longer an enumerable cross-tenant oracle.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the species.
     * @param xActiveTenant Slug of the tenant the caller is acting in, for global (path-less) tenant-aware routes such as the species and botanical-family catalogues (ADR-009, REQ-049 §2.11). Omit it to act in your personal tenant. A slug you hold no active membership in is refused with 403. (optional)
     * @return [SpeciesResponse]
     */
    @GET("api/v1/species/{key}")
    suspend fun getSpeciesApiV1SpeciesKeyGet(@Path("key") key: kotlin.String, @Header("X-Active-Tenant") xActiveTenant: kotlin.String? = null): Response<SpeciesResponse>

    /**
     * GET api/v1/species/{key}/reference-images
     * Get Species Reference Images
     * Reference-image gallery for a species (REQ-029-A §4).  Proxies the inference-service; returns an empty gallery when the service is disabled/unreachable or the index has no images for this species yet.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the species.
     * @param xActiveTenant Slug of the tenant the caller is acting in, for global (path-less) tenant-aware routes such as the species and botanical-family catalogues (ADR-009, REQ-049 §2.11). Omit it to act in your personal tenant. A slug you hold no active membership in is refused with 403. (optional)
     * @return [SpeciesReferenceImagesResponse]
     */
    @GET("api/v1/species/{key}/reference-images")
    suspend fun getSpeciesReferenceImagesApiV1SpeciesKeyReferenceImagesGet(@Path("key") key: kotlin.String, @Header("X-Active-Tenant") xActiveTenant: kotlin.String? = null): Response<SpeciesReferenceImagesResponse>

    /**
     * GET api/v1/species
     * List Species
     * List the species catalogue (paginated).  Tenant-aware on this global route (F-5, #808): returns the global seed catalogue (&#x60;&#x60;tenant_key &#x3D;&#x3D; \&quot;\&quot;&#x60;&#x60;) plus the caller&#39;s own-tenant species, and never a foreign tenant&#39;s. The active tenant is resolved by :func:&#x60;~app.common.auth.get_active_tenant_key&#x60;; an anonymous/light-mode caller resolves to &#x60;&#x60;\&quot;\&quot;&#x60;&#x60; and sees only the global catalogue.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param offset Number of species to skip (pagination offset). (optional, default to 0)
     * @param limit Maximum number of species to return. (optional, default to 50)
     * @param xActiveTenant Slug of the tenant the caller is acting in, for global (path-less) tenant-aware routes such as the species and botanical-family catalogues (ADR-009, REQ-049 §2.11). Omit it to act in your personal tenant. A slug you hold no active membership in is refused with 403. (optional)
     * @return [SpeciesListResponse]
     */
    @GET("api/v1/species")
    suspend fun listSpeciesApiV1SpeciesGet(@Query("offset") offset: kotlin.Int? = 0, @Query("limit") limit: kotlin.Int? = 50, @Header("X-Active-Tenant") xActiveTenant: kotlin.String? = null): Response<SpeciesListResponse>

    /**
     * PUT api/v1/species/{key}
     * Update Species
     * Update an existing species master record.  Ownership and role are enforced in the service (SEC-002, #808): a *foreign* tenant&#39;s species answers 404, the *global* seed catalogue is editable only by a platform admin, and the caller&#39;s *own* species requires a writing domain role (a viewer is refused).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param key Document key of the species.
     * @param speciesCreate 
     * @param xActiveTenant Slug of the tenant the caller is acting in, for global (path-less) tenant-aware routes such as the species and botanical-family catalogues (ADR-009, REQ-049 §2.11). Omit it to act in your personal tenant. A slug you hold no active membership in is refused with 403. (optional)
     * @return [SpeciesResponse]
     */
    @PUT("api/v1/species/{key}")
    suspend fun updateSpeciesApiV1SpeciesKeyPut(@Path("key") key: kotlin.String, @Body speciesCreate: SpeciesCreate, @Header("X-Active-Tenant") xActiveTenant: kotlin.String? = null): Response<SpeciesResponse>

}
