package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.ApiKeyCreateRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ApiKeyCreatedResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ApiKeySummaryResponse
import io.github.nolte.kamerplanter.core.network.generated.models.DevicePairingCreateResponse
import io.github.nolte.kamerplanter.core.network.generated.models.DevicePairingRedeemRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.LoginRequest
import io.github.nolte.kamerplanter.core.network.generated.models.MessageResponse
import io.github.nolte.kamerplanter.core.network.generated.models.OAuthProviderListItem
import io.github.nolte.kamerplanter.core.network.generated.models.PasswordResetConfirm
import io.github.nolte.kamerplanter.core.network.generated.models.PasswordResetRequest
import io.github.nolte.kamerplanter.core.network.generated.models.RefreshRequest
import io.github.nolte.kamerplanter.core.network.generated.models.RegisterRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ResponseRefreshApiV1AuthRefreshPost
import io.github.nolte.kamerplanter.core.network.generated.models.ServiceAccountValidateRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ServiceAccountValidateResponse
import io.github.nolte.kamerplanter.core.network.generated.models.TokenPairResponse
import io.github.nolte.kamerplanter.core.network.generated.models.TokenResponse
import io.github.nolte.kamerplanter.core.network.generated.models.UserProfileResponse
import io.github.nolte.kamerplanter.core.network.generated.models.VerifyEmailRequest

interface AuthApi {
    /**
     * POST api/v1/auth/password-reset/confirm
     * Confirm Password Reset
     * Set a new password from a valid password-reset token.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param passwordResetConfirm 
     * @return [MessageResponse]
     */
    @POST("api/v1/auth/password-reset/confirm")
    suspend fun confirmPasswordResetApiV1AuthPasswordResetConfirmPost(@Body passwordResetConfirm: PasswordResetConfirm): Response<MessageResponse>

    /**
     * POST api/v1/auth/api-keys
     * Create Api Key
     * Create a new M2M API key for the current user.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param apiKeyCreateRequest 
     * @return [ApiKeyCreatedResponse]
     */
    @POST("api/v1/auth/api-keys")
    suspend fun createApiKeyApiV1AuthApiKeysPost(@Body apiKeyCreateRequest: ApiKeyCreateRequest): Response<ApiKeyCreatedResponse>

    /**
     * POST api/v1/auth/device-pairing
     * Create Device Pairing
     * Mint a one-time QR pairing code for the authenticated user (#1118).  Bearer-authenticated, like &#x60;&#x60;create_api_key&#x60;&#x60; and for the same reason: the credential is presented in the &#x60;&#x60;Authorization&#x60;&#x60; header, not ambiently by the browser, so there is nothing for a cross-site request to ride on and no CSRF double-submit to verify. (&#x60;&#x60;verify_csrf&#x60;&#x60; guards the routes that spend the &#x60;&#x60;kp_refresh&#x60;&#x60; cookie — refresh, logout, logout-all — which is the ambient credential this one does not use.)  &#x60;&#x60;server_url&#x60;&#x60; comes from &#x60;&#x60;settings.app_base_url&#x60;&#x60;, the base URL REQ-032 already established as the SSOT for QR codes, and never from &#x60;&#x60;request.base_url&#x60;&#x60;: behind the Traefik ingress the latter resolves to a cluster-internal address, so the QR would encode a URL the scanning phone cannot reach — and it would do so only in production, where nobody is running the test that would have caught it.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [DevicePairingCreateResponse]
     */
    @POST("api/v1/auth/device-pairing")
    suspend fun createDevicePairingApiV1AuthDevicePairingPost(): Response<DevicePairingCreateResponse>

    /**
     * GET api/v1/auth/oauth/{slug}
     * Initiate Oauth
     * 302 Redirect to the OAuth provider&#39;s authorization URL.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param slug Slug of the configured OAuth/OIDC provider.
     * @return [kotlinx.serialization.json.JsonElement]
     */
    @GET("api/v1/auth/oauth/{slug}")
    suspend fun initiateOauthApiV1AuthOauthSlugGet(@Path("slug") slug: kotlin.String): Response<kotlinx.serialization.json.JsonElement>

    /**
     * GET api/v1/auth/api-keys
     * List Api Keys
     * List the current user&#39;s M2M API keys (metadata only).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [kotlin.collections.List<ApiKeySummaryResponse>]
     */
    @GET("api/v1/auth/api-keys")
    suspend fun listApiKeysApiV1AuthApiKeysGet(): Response<kotlin.collections.List<ApiKeySummaryResponse>>

    /**
     * GET api/v1/auth/oauth/providers
     * List Oauth Providers
     * List the enabled OAuth/OIDC providers offered for login.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [kotlin.collections.List<OAuthProviderListItem>]
     */
    @GET("api/v1/auth/oauth/providers")
    suspend fun listOauthProvidersApiV1AuthOauthProvidersGet(): Response<kotlin.collections.List<OAuthProviderListItem>>

    /**
     * POST api/v1/auth/login
     * Login
     * Authenticate with email and password, issuing access and refresh tokens.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param loginRequest 
     * @return [TokenResponse]
     */
    @POST("api/v1/auth/login")
    suspend fun loginApiV1AuthLoginPost(@Body loginRequest: LoginRequest): Response<TokenResponse>

    /**
     * POST api/v1/auth/logout-all
     * Logout All
     * Revoke all of the current user&#39;s active sessions.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [MessageResponse]
     */
    @POST("api/v1/auth/logout-all")
    suspend fun logoutAllApiV1AuthLogoutAllPost(): Response<MessageResponse>

    /**
     * POST api/v1/auth/logout
     * Logout
     * Revoke the current refresh token and clear the refresh cookie.  Cookie-only by design, and deliberately **not** extended with the body-borne transport &#x60;&#x60;/refresh&#x60;&#x60; gained in #1118: this route is CSRF-verified first, so a caller without the &#x60;&#x60;csrf_token&#x60;&#x60; cookie is refused with 403 before the body would be read. A paired device therefore does not log out here — it revokes its own session through &#x60;&#x60;DELETE /users/me/sessions/{key}&#x60;&#x60; (authenticated with its access token, no ambient credential involved) or simply discards the token, which becomes worthless at its expiry. Documented for the API reference rather than papered over here: adding a second, unauthenticated revocation path would widen the surface for a capability the session API already covers.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [MessageResponse]
     */
    @POST("api/v1/auth/logout")
    suspend fun logoutApiV1AuthLogoutPost(): Response<MessageResponse>

    /**
     * GET api/v1/auth/oauth/{slug}/callback
     * Oauth Callback
     * OAuth callback: exchange code, set the refresh cookie, redirect to frontend.  AP-7 (FE-S1): the access token is delivered exclusively through the HttpOnly refresh cookie. It is NEVER placed in the redirect URL (neither query nor fragment), so it cannot leak via browser history, Referer headers or proxy logs. The frontend completes the login through the cookie-based refresh flow.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param slug Slug of the configured OAuth/OIDC provider.
     * @param code Authorization code returned by the provider. (optional)
     * @param state Opaque anti-CSRF state echoed by the provider. (optional)
     * @param error Error code returned when the provider denied the request. (optional)
     * @return [kotlinx.serialization.json.JsonElement]
     */
    @GET("api/v1/auth/oauth/{slug}/callback")
    suspend fun oauthCallbackApiV1AuthOauthSlugCallbackGet(@Path("slug") slug: kotlin.String, @Query("code") code: kotlin.String? = null, @Query("state") state: kotlin.String? = null, @Query("error") error: kotlin.String? = null): Response<kotlinx.serialization.json.JsonElement>

    /**
     * POST api/v1/auth/device-pairing/redeem
     * Redeem Device Pairing
     * Exchange a scanned pairing code for the standard REQ-023 token pair (#1118).  **Public by design.** The caller is a freshly installed app that holds no credential yet; the code it just scanned is the credential. The generated OpenAPI therefore carries &#x60;&#x60;security: []&#x60;&#x60; for this operation (stamped by &#x60;&#x60;main.py::_openapi_postprocessed&#x60;&#x60; for every operation with no security dependency), which is what keeps &#x60;&#x60;scripts/security/zap_auth_bypass.py&#x60;&#x60; from reporting it as an auth bypass — and what makes the property visible to a reviewer instead of implicit in the absence of a &#x60;&#x60;Depends&#x60;&#x60;.  **No cookie is set, deliberately.** The refresh token leaves in the JSON body (see &#x60;&#x60;TokenPairResponse&#x60;&#x60;) because the client has no cookie jar. Adding the &#x60;&#x60;kp_refresh&#x60;&#x60; cookie *as well* would put one credential on two transports for no gain. No CSRF header is required either: there is no ambient credential here to protect — the request carries its own.  The error surface is the service&#39;s, unchanged and answered by the global &#x60;&#x60;KamerplanterError&#x60;&#x60; handler: 423 while the source address is locked out, 401 for a code that is unknown, used, expired or unreadable — one answer for all four, so nothing here works as an oracle — 401 for a deactivated account, 422 for input the boundary let through but the service rejects.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param devicePairingRedeemRequest 
     * @return [TokenPairResponse]
     */
    @POST("api/v1/auth/device-pairing/redeem")
    suspend fun redeemDevicePairingApiV1AuthDevicePairingRedeemPost(@Body devicePairingRedeemRequest: DevicePairingRedeemRequest): Response<TokenPairResponse>

    /**
     * POST api/v1/auth/refresh
     * Refresh
     * Rotate a refresh token and issue a fresh access token — two transports (#1118).  **Cookie transport (browsers, unchanged).** No body, or a body without a token: the refresh token comes from the HttpOnly &#x60;&#x60;kp_refresh&#x60;&#x60; cookie, the CSRF double-submit is verified, the rotated token leaves as a cookie again and never appears in the JSON (AP-7 / FE-S1). The refusal order is the one this endpoint always had — a missing cookie answers 401 *before* CSRF is looked at, so nothing about the change is observable from the browser side.  **Body transport (paired devices).** A device paired by QR code holds the raw refresh token in its platform keystore and has neither a cookie jar nor any way to obtain the &#x60;&#x60;csrf_token&#x60;&#x60; cookie. It presents the token as &#x60;&#x60;{\&quot;refresh_token\&quot;: \&quot;…\&quot;}&#x60;&#x60;; the response carries the rotated token in the body (&#x60;&#x60;TokenPairResponse&#x60;&#x60;) and sets **no** cookie — one credential on two transports would double its exposure and leave no single place that revokes it. Without this transport the pair minted by &#x60;&#x60;/device-pairing/redeem&#x60;&#x60; would die 15 minutes later, unrotatable.  **Why the body path needs no CSRF.** The double-submit defends *ambient* credentials: the browser attaches &#x60;&#x60;kp_refresh&#x60;&#x60; to a cross-site request on its own, so possession of the cookie proves nothing about who composed the request. A token in the body is not ambient — it must be read and typed in by the caller, and an attacker who can read this user&#39;s refresh token has the credential itself and needs no request forgery. (A cross-site page cannot even reach here: a JSON content type is not a CORS-simple request, so it is preflighted.)  **Precedence, when both are present: the body wins and the cookie is ignored entirely.** Not \&quot;try the body, fall back to the cookie\&quot; — that fallback is the actual vulnerability, because a forged cross-site request carrying a junk body token would then spend the victim&#39;s ambient cookie with no CSRF header in sight. Here a request that names a body token is answered purely from that token: if it is invalid the answer is 401 and the cookie is left untouched and still valid. Nothing spends the ambient credential except the CSRF-verified branch below.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param refreshRequest  (optional)
     * @return [ResponseRefreshApiV1AuthRefreshPost]
     */
    @POST("api/v1/auth/refresh")
    suspend fun refreshApiV1AuthRefreshPost(@Body refreshRequest: RefreshRequest? = null): Response<ResponseRefreshApiV1AuthRefreshPost>

    /**
     * POST api/v1/auth/register
     * Register
     * Register a new local account with email and password.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param registerRequest 
     * @return [UserProfileResponse]
     */
    @POST("api/v1/auth/register")
    suspend fun registerApiV1AuthRegisterPost(@Body registerRequest: RegisterRequest): Response<UserProfileResponse>

    /**
     * POST api/v1/auth/password-reset/request
     * Request Password Reset
     * Send a password-reset link if an account exists for the email.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param passwordResetRequest 
     * @return [MessageResponse]
     */
    @POST("api/v1/auth/password-reset/request")
    suspend fun requestPasswordResetApiV1AuthPasswordResetRequestPost(@Body passwordResetRequest: PasswordResetRequest): Response<MessageResponse>

    /**
     * DELETE api/v1/auth/api-keys/{key_id}
     * Revoke Api Key
     * Revoke one of the current user&#39;s M2M API keys.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param keyId Identifier of the API key to revoke.
     * @return [MessageResponse]
     */
    @DELETE("api/v1/auth/api-keys/{key_id}")
    suspend fun revokeApiKeyApiV1AuthApiKeysKeyIdDelete(@Path("key_id") keyId: kotlin.String): Response<MessageResponse>

    /**
     * POST api/v1/auth/service-accounts/validate
     * Validate Service Account
     * Validate a service-account API key and return its tenant + MCP grants (§5).  The M2M validation entrypoint an external MCP-server process (future follow-up) calls to resolve a key into a bound service account. The raw key is hashed inside the authenticator and never persisted or logged (AC-S2).  Hardened per the REQ-033 security review (SEC-003): gated behind the MCP enable flag (&#x60;&#x60;require_mcp_enabled&#x60;&#x60; → 404 when MCP is off), per-IP rate limited (reusing the auth limiter) and the valid-non-service case is collapsed into the SAME generic 401 as an invalid/revoked key so the endpoint can never be used as an oracle to prove a valid non-service key exists (&#x60;&#x60;mask_non_service&#x3D;True&#x60;&#x60;). The client IP is resolved so the key&#39;s &#x60;&#x60;ip_allowlist&#x60;&#x60; (SEC-004) is enforced here too.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param serviceAccountValidateRequest 
     * @return [ServiceAccountValidateResponse]
     */
    @POST("api/v1/auth/service-accounts/validate")
    suspend fun validateServiceAccountApiV1AuthServiceAccountsValidatePost(@Body serviceAccountValidateRequest: ServiceAccountValidateRequest): Response<ServiceAccountValidateResponse>

    /**
     * POST api/v1/auth/verify-email
     * Verify Email
     * Verify a user&#39;s email address from a signed token.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param verifyEmailRequest 
     * @return [UserProfileResponse]
     */
    @POST("api/v1/auth/verify-email")
    suspend fun verifyEmailApiV1AuthVerifyEmailPost(@Body verifyEmailRequest: VerifyEmailRequest): Response<UserProfileResponse>

}
