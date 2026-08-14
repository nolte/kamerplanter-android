package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.CurrentPhaseResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.PhaseHistoryDateUpdate
import io.github.nolte.kamerplanter.core.network.generated.models.PhaseHistoryResponse
import io.github.nolte.kamerplanter.core.network.generated.models.PlantResponse
import io.github.nolte.kamerplanter.core.network.generated.models.TransitionRequest

interface PhasesApi {
    /**
     * DELETE api/v1/plant-instances/{plant_key}/phases/history/{history_key}
     * Delete Phase History
     * Delete a phase-history entry of a plant instance.
     * Responses:
     *  - 204: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param plantKey Document key of the plant instance.
     * @param historyKey Document key of the phase-history entry.
     * @return [Unit]
     */
    @DELETE("api/v1/plant-instances/{plant_key}/phases/history/{history_key}")
    suspend fun deletePhaseHistoryApiV1PlantInstancesPlantKeyPhasesHistoryHistoryKeyDelete(@Path("plant_key") plantKey: kotlin.String, @Path("history_key") historyKey: kotlin.String): Response<Unit>

    /**
     * GET api/v1/plant-instances/{plant_key}/phases/current
     * Get Current Phase
     * Return the plant instance&#39;s current phase snapshot.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param plantKey Document key of the plant instance.
     * @return [CurrentPhaseResponse]
     */
    @GET("api/v1/plant-instances/{plant_key}/phases/current")
    suspend fun getCurrentPhaseApiV1PlantInstancesPlantKeyPhasesCurrentGet(@Path("plant_key") plantKey: kotlin.String): Response<CurrentPhaseResponse>

    /**
     * GET api/v1/plant-instances/{plant_key}/phases/history
     * Get Phase History
     * List the plant instance&#39;s phase-transition history.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param plantKey Document key of the plant instance.
     * @return [kotlin.collections.List<PhaseHistoryResponse>]
     */
    @GET("api/v1/plant-instances/{plant_key}/phases/history")
    suspend fun getPhaseHistoryApiV1PlantInstancesPlantKeyPhasesHistoryGet(@Path("plant_key") plantKey: kotlin.String): Response<kotlin.collections.List<PhaseHistoryResponse>>

    /**
     * POST api/v1/plant-instances/{plant_key}/phases/transition
     * Transition Phase
     * Transition a plant instance to a target phase.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param plantKey Document key of the plant instance.
     * @param transitionRequest 
     * @return [PlantResponse]
     */
    @POST("api/v1/plant-instances/{plant_key}/phases/transition")
    suspend fun transitionPhaseApiV1PlantInstancesPlantKeyPhasesTransitionPost(@Path("plant_key") plantKey: kotlin.String, @Body transitionRequest: TransitionRequest): Response<PlantResponse>

    /**
     * PATCH api/v1/plant-instances/{plant_key}/phases/history/{history_key}
     * Update Phase History Dates
     * Adjust the entered/exited dates of a phase-history entry.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param plantKey Document key of the plant instance.
     * @param historyKey Document key of the phase-history entry.
     * @param phaseHistoryDateUpdate 
     * @return [PhaseHistoryResponse]
     */
    @PATCH("api/v1/plant-instances/{plant_key}/phases/history/{history_key}")
    suspend fun updatePhaseHistoryDatesApiV1PlantInstancesPlantKeyPhasesHistoryHistoryKeyPatch(@Path("plant_key") plantKey: kotlin.String, @Path("history_key") historyKey: kotlin.String, @Body phaseHistoryDateUpdate: PhaseHistoryDateUpdate): Response<PhaseHistoryResponse>

}
