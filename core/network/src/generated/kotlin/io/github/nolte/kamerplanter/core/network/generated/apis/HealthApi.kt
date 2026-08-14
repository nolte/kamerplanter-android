package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.LivenessResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ReadinessResponse

interface HealthApi {
    /**
     * GET api/v1/health/live
     * Liveness
     * Liveness probe: reports that the process is up.
     * Responses:
     *  - 200: Successful Response
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [LivenessResponse]
     */
    @GET("api/v1/health/live")
    suspend fun livenessApiV1HealthLiveGet(): Response<LivenessResponse>

    /**
     * GET api/v1/health/ready
     * Readiness
     * Readiness probe (NFR-013 AC-08).  Reports &#x60;&#x60;ready&#x60;&#x60; only when both the primary database and the configured object-storage backend are reachable. A storage outage flips readiness to HTTP 503 so the pod is taken out of rotation until storage recovers.
     * Responses:
     *  - 200: Successful Response
     *  - 422: The input data is invalid (field-level details in `details`).
     *  - 503: Database or object storage is unreachable.
     *
     * @return [ReadinessResponse]
     */
    @GET("api/v1/health/ready")
    suspend fun readinessApiV1HealthReadyGet(): Response<ReadinessResponse>

    /**
     * GET api/health
     * Root Health
     * Root-level health endpoint for M2M consumers (HA integration).
     * Responses:
     *  - 200: Successful Response
     *
     * @return [kotlin.collections.Map<kotlin.String, kotlinx.serialization.json.JsonElement>]
     */
    @GET("api/health")
    suspend fun rootHealthApiHealthGet(): Response<kotlin.collections.Map<kotlin.String, kotlinx.serialization.json.JsonElement>>

}
