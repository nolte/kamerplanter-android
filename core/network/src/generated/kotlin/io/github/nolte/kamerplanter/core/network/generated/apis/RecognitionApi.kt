package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.IdentificationStatusResponse

interface RecognitionApi {
    /**
     * GET api/v1/recognition/status
     * Identification Status
     * Return identification feature availability and per-adapter status.  No authentication required so the camera UI can be toggled before login (REQ-029 §3.7). Graceful degradation: when nothing is configured, &#x60;&#x60;available&#x60;&#x60; is False and the frontend hides all camera entry points.
     * Responses:
     *  - 200: Successful Response
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [IdentificationStatusResponse]
     */
    @GET("api/v1/recognition/status")
    suspend fun identificationStatusApiV1RecognitionStatusGet(): Response<IdentificationStatusResponse>

}
