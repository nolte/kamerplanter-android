package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.AuthProviderResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ChangePasswordRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.MessageResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ProfileUpdateRequest
import io.github.nolte.kamerplanter.core.network.generated.models.SessionResponse
import io.github.nolte.kamerplanter.core.network.generated.models.UserProfileResponse

interface UsersApi {
    /**
     * POST api/v1/users/me/password
     * Change Password
     * Change the current user&#39;s password and revoke all sessions.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param changePasswordRequest 
     * @return [MessageResponse]
     */
    @POST("api/v1/users/me/password")
    suspend fun changePasswordApiV1UsersMePasswordPost(@Body changePasswordRequest: ChangePasswordRequest): Response<MessageResponse>

    /**
     * DELETE api/v1/users/me
     * Delete Account
     * Delete the current user&#39;s account.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [MessageResponse]
     */
    @DELETE("api/v1/users/me")
    suspend fun deleteAccountApiV1UsersMeDelete(): Response<MessageResponse>

    /**
     * GET api/v1/users/me
     * Get Profile
     * Return the current user&#39;s profile plus platform-admin flag.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [UserProfileResponse]
     */
    @GET("api/v1/users/me")
    suspend fun getProfileApiV1UsersMeGet(): Response<UserProfileResponse>

    /**
     * POST api/v1/users/me/providers/{provider_slug}/link
     * Link Provider
     * Link an OAuth provider to the current user&#39;s account.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param providerSlug Slug of the OAuth provider to link.
     * @param code OAuth authorization code returned by the provider.
     * @param state OAuth state value for CSRF validation.
     * @return [AuthProviderResponse]
     */
    @POST("api/v1/users/me/providers/{provider_slug}/link")
    suspend fun linkProviderApiV1UsersMeProvidersProviderSlugLinkPost(@Path("provider_slug") providerSlug: kotlin.String, @Query("code") code: kotlin.String, @Query("state") state: kotlin.String): Response<AuthProviderResponse>

    /**
     * GET api/v1/users/me/providers
     * List Providers
     * List the OAuth providers linked to the current user&#39;s account.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [kotlin.collections.List<AuthProviderResponse>]
     */
    @GET("api/v1/users/me/providers")
    suspend fun listProvidersApiV1UsersMeProvidersGet(): Response<kotlin.collections.List<AuthProviderResponse>>

    /**
     * GET api/v1/users/me/sessions
     * List Sessions
     * List the current user&#39;s active sessions.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [kotlin.collections.List<SessionResponse>]
     */
    @GET("api/v1/users/me/sessions")
    suspend fun listSessionsApiV1UsersMeSessionsGet(): Response<kotlin.collections.List<SessionResponse>>

    /**
     * DELETE api/v1/users/me/sessions/{session_key}
     * Revoke Session
     * Revoke one of the current user&#39;s sessions.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param sessionKey Document key of the session to revoke.
     * @return [MessageResponse]
     */
    @DELETE("api/v1/users/me/sessions/{session_key}")
    suspend fun revokeSessionApiV1UsersMeSessionsSessionKeyDelete(@Path("session_key") sessionKey: kotlin.String): Response<MessageResponse>

    /**
     * DELETE api/v1/users/me/providers/{provider_key}
     * Unlink Provider
     * Unlink an OAuth provider from the current user&#39;s account.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param providerKey Document key of the linked auth provider.
     * @return [MessageResponse]
     */
    @DELETE("api/v1/users/me/providers/{provider_key}")
    suspend fun unlinkProviderApiV1UsersMeProvidersProviderKeyDelete(@Path("provider_key") providerKey: kotlin.String): Response<MessageResponse>

    /**
     * PATCH api/v1/users/me
     * Update Profile
     * Update the current user&#39;s profile.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param profileUpdateRequest 
     * @return [UserProfileResponse]
     */
    @PATCH("api/v1/users/me")
    suspend fun updateProfileApiV1UsersMePatch(@Body profileUpdateRequest: ProfileUpdateRequest): Response<UserProfileResponse>

}
