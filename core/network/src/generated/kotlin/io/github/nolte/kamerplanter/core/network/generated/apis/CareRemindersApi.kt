package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.CareConfirmationResponse
import io.github.nolte.kamerplanter.core.network.generated.models.CareDashboardEntryResponse
import io.github.nolte.kamerplanter.core.network.generated.models.CareProfileResponse
import io.github.nolte.kamerplanter.core.network.generated.models.CareProfileUpdate
import io.github.nolte.kamerplanter.core.network.generated.models.ConfirmRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ReminderType
import io.github.nolte.kamerplanter.core.network.generated.models.SnoozeRequest

interface CareRemindersApi {
    /**
     * POST api/v1/care-reminders/plants/{plant_key}/confirm
     * Confirm Reminder
     * Confirm a due care reminder and record the performed care.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param plantKey Document key of the plant.
     * @param confirmRequest 
     * @return [CareConfirmationResponse]
     */
    @POST("api/v1/care-reminders/plants/{plant_key}/confirm")
    suspend fun confirmReminderApiV1CareRemindersPlantsPlantKeyConfirmPost(@Path("plant_key") plantKey: kotlin.String, @Body confirmRequest: ConfirmRequest): Response<CareConfirmationResponse>

    /**
     * GET api/v1/t/{tenant_slug}/care-reminders/dashboard
     * Get Care Dashboard
     * Build the care dashboard from the active plants of the current tenant.  Tenant isolation is enforced via &#x60;&#x60;get_current_tenant&#x60;&#x60;; only plants of the authenticated tenant are considered (removed plants are excluded).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param hemisphere Hemisphere used for seasonal care timing (north or south). (optional, default to "north")
     * @return [kotlin.collections.List<CareDashboardEntryResponse>]
     */
    @GET("api/v1/t/{tenant_slug}/care-reminders/dashboard")
    suspend fun getCareDashboardApiV1TTenantSlugCareRemindersDashboardGet(@Path("tenant_slug") tenantSlug: kotlin.String, @Query("hemisphere") hemisphere: kotlin.String? = "north"): Response<kotlin.collections.List<CareDashboardEntryResponse>>

    /**
     * GET api/v1/care-reminders/plants/{plant_key}/history
     * Get Confirmation History
     * List the plant&#39;s care-confirmation history, optionally filtered by type.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param plantKey Document key of the plant.
     * @param reminderType Filter the history by reminder type. (optional)
     * @param limit Maximum number of confirmations to return. (optional, default to 50)
     * @return [kotlin.collections.List<CareConfirmationResponse>]
     */
    @GET("api/v1/care-reminders/plants/{plant_key}/history")
    suspend fun getConfirmationHistoryApiV1CareRemindersPlantsPlantKeyHistoryGet(@Path("plant_key") plantKey: kotlin.String, @Query("reminder_type") reminderType: ReminderType? = null, @Query("limit") limit: kotlin.Int? = 50): Response<kotlin.collections.List<CareConfirmationResponse>>

    /**
     * GET api/v1/care-reminders/plants/{plant_key}/profile
     * Get Or Create Profile
     * Return the plant&#39;s care profile, creating it from presets if absent.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param plantKey Document key of the plant.
     * @param speciesName Species name used to seed a new profile&#39;s presets. (optional)
     * @param botanicalFamily Botanical family used to seed a new profile&#39;s presets. (optional)
     * @return [CareProfileResponse]
     */
    @GET("api/v1/care-reminders/plants/{plant_key}/profile")
    suspend fun getOrCreateProfileApiV1CareRemindersPlantsPlantKeyProfileGet(@Path("plant_key") plantKey: kotlin.String, @Query("species_name") speciesName: kotlin.String? = null, @Query("botanical_family") botanicalFamily: kotlin.String? = null): Response<CareProfileResponse>

    /**
     * POST api/v1/care-reminders/plants/{plant_key}/reset-profile
     * Reset Profile
     * Reset the plant&#39;s care profile back to its preset defaults.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param plantKey Document key of the plant.
     * @param speciesName Species name used to re-seed the profile&#39;s presets. (optional)
     * @param botanicalFamily Botanical family used to re-seed the profile&#39;s presets. (optional)
     * @return [CareProfileResponse]
     */
    @POST("api/v1/care-reminders/plants/{plant_key}/reset-profile")
    suspend fun resetProfileApiV1CareRemindersPlantsPlantKeyResetProfilePost(@Path("plant_key") plantKey: kotlin.String, @Query("species_name") speciesName: kotlin.String? = null, @Query("botanical_family") botanicalFamily: kotlin.String? = null): Response<CareProfileResponse>

    /**
     * POST api/v1/care-reminders/plants/{plant_key}/snooze
     * Snooze Reminder
     * Snooze a due care reminder for the requested number of days.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param plantKey Document key of the plant.
     * @param snoozeRequest 
     * @return [CareConfirmationResponse]
     */
    @POST("api/v1/care-reminders/plants/{plant_key}/snooze")
    suspend fun snoozeReminderApiV1CareRemindersPlantsPlantKeySnoozePost(@Path("plant_key") plantKey: kotlin.String, @Body snoozeRequest: SnoozeRequest): Response<CareConfirmationResponse>

    /**
     * PATCH api/v1/care-reminders/plants/{plant_key}/profile
     * Update Profile
     * Update the plant&#39;s care profile with the supplied fields.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param plantKey Document key of the plant.
     * @param careProfileUpdate 
     * @return [CareProfileResponse]
     */
    @PATCH("api/v1/care-reminders/plants/{plant_key}/profile")
    suspend fun updateProfileApiV1CareRemindersPlantsPlantKeyProfilePatch(@Path("plant_key") plantKey: kotlin.String, @Body careProfileUpdate: CareProfileUpdate): Response<CareProfileResponse>

}
