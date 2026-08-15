package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.ConsentGrantRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ConsentResponse
import io.github.nolte.kamerplanter.core.network.generated.models.DataExportResponse
import io.github.nolte.kamerplanter.core.network.generated.models.EmailChangeConfirmRequest
import io.github.nolte.kamerplanter.core.network.generated.models.EmailChangeCreateRequest
import io.github.nolte.kamerplanter.core.network.generated.models.EmailChangeResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ErasureCreateRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ErasureResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.McpAuditLogEntry
import io.github.nolte.kamerplanter.core.network.generated.models.MessageResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ObjectionRequest
import io.github.nolte.kamerplanter.core.network.generated.models.PrivacyPolicyResponse
import io.github.nolte.kamerplanter.core.network.generated.models.RestrictionCreateRequest
import io.github.nolte.kamerplanter.core.network.generated.models.RestrictionResponse

interface PrivacyApi {
    /**
     * POST api/v1/privacy/email-change/confirm
     * Confirm Email Change
     * Confirm an email change via the verification token (no auth required).  Rate-limited per client IP (&#x60;&#x60;settings.rate_limit_email_change_confirm&#x60;&#x60;, #990). The endpoint is unauthenticated and state-changing; the reason its token being 32 random bytes is not accepted as sufficient — and why the budget is deliberately *not* the sibling&#39;s &#x60;&#x60;rate_limit_email_change&#x60;&#x60; — is recorded on the setting.  &#x60;&#x60;request&#x60;&#x60; is unused by the body and required by the decorator: slowapi inspects the signature and raises &#x60;&#x60;No \&quot;request\&quot; or \&quot;websocket\&quot; argument&#x60;&#x60; at decoration time. Deleting the parameter as dead therefore breaks the import, which is the loud failure — it cannot quietly disarm the limit.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param emailChangeConfirmRequest 
     * @return [MessageResponse]
     */
    @POST("api/v1/privacy/email-change/confirm")
    suspend fun confirmEmailChangeApiV1PrivacyEmailChangeConfirmPost(@Body emailChangeConfirmRequest: EmailChangeConfirmRequest): Response<MessageResponse>

    /**
     * GET api/v1/privacy/export/{export_key}/download
     * Download Export
     * Mark an export as downloaded and return its metadata.  Streaming the actual file is delegated to a future Celery/FileResponse integration. Returning the metadata is enough to validate the contract and record the download.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param exportKey Document key of the data-export job.
     * @return [DataExportResponse]
     */
    @GET("api/v1/privacy/export/{export_key}/download")
    suspend fun downloadExportApiV1PrivacyExportExportKeyDownloadGet(@Path("export_key") exportKey: kotlin.String): Response<DataExportResponse>

    /**
     * GET api/v1/privacy/erasure/{erasure_key}
     * Get Erasure Status
     * Return status of an erasure request.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param erasureKey Document key of the erasure request.
     * @return [ErasureResponse]
     */
    @GET("api/v1/privacy/erasure/{erasure_key}")
    suspend fun getErasureStatusApiV1PrivacyErasureErasureKeyGet(@Path("erasure_key") erasureKey: kotlin.String): Response<ErasureResponse>

    /**
     * GET api/v1/privacy/export/{export_key}
     * Get Export Status
     * Return status of a single export job (ownership-checked).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param exportKey Document key of the data-export job.
     * @return [DataExportResponse]
     */
    @GET("api/v1/privacy/export/{export_key}")
    suspend fun getExportStatusApiV1PrivacyExportExportKeyGet(@Path("export_key") exportKey: kotlin.String): Response<DataExportResponse>

    /**
     * GET api/v1/privacy/mcp-activity
     * Get Mcp Activity
     * Return the MCP tool-call audit trail attributed to the calling account.  DSGVO transparency for MCP usage (§4.6): entries are hash-only (no PII) and scoped to the caller&#39;s own &#x60;&#x60;service_account_key&#x60;&#x60; (AC-S1). Retention is 90 days (AC-S4); aggregating across *all* service accounts a human owns is a documented follow-up (needs a user→service-account ownership edge).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [kotlin.collections.List<McpAuditLogEntry>]
     */
    @GET("api/v1/privacy/mcp-activity")
    suspend fun getMcpActivityApiV1PrivacyMcpActivityGet(): Response<kotlin.collections.List<McpAuditLogEntry>>

    /**
     * GET api/v1/privacy/policy
     * Get Privacy Policy
     * Return the current privacy policy (no auth required).
     * Responses:
     *  - 200: Successful Response
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [PrivacyPolicyResponse]
     */
    @GET("api/v1/privacy/policy")
    suspend fun getPrivacyPolicyApiV1PrivacyPolicyGet(): Response<PrivacyPolicyResponse>

    /**
     * POST api/v1/privacy/consents
     * Grant Consent
     * Grant consent for a processing purpose.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param consentGrantRequest 
     * @return [ConsentResponse]
     */
    @POST("api/v1/privacy/consents")
    suspend fun grantConsentApiV1PrivacyConsentsPost(@Body consentGrantRequest: ConsentGrantRequest): Response<ConsentResponse>

    /**
     * DELETE api/v1/privacy/restrict/{restriction_key}
     * Lift Restriction
     * Lift an existing processing-restriction.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param restrictionKey Document key of the processing restriction.
     * @return [RestrictionResponse]
     */
    @DELETE("api/v1/privacy/restrict/{restriction_key}")
    suspend fun liftRestrictionApiV1PrivacyRestrictRestrictionKeyDelete(@Path("restriction_key") restrictionKey: kotlin.String): Response<RestrictionResponse>

    /**
     * GET api/v1/privacy/consents
     * List Consents
     * List all known purposes annotated with current consent state.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [kotlin.collections.List<ConsentResponse>]
     */
    @GET("api/v1/privacy/consents")
    suspend fun listConsentsApiV1PrivacyConsentsGet(): Response<kotlin.collections.List<ConsentResponse>>

    /**
     * POST api/v1/privacy/object
     * Object To Processing
     * File an objection (Art. 21) — stored as restriction with objection_pending.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param objectionRequest 
     * @return [RestrictionResponse]
     */
    @POST("api/v1/privacy/object")
    suspend fun objectToProcessingApiV1PrivacyObjectPost(@Body objectionRequest: ObjectionRequest): Response<RestrictionResponse>

    /**
     * POST api/v1/privacy/export
     * Request Data Export
     * Initiate a new data-export job (Art. 15 / 20).
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [DataExportResponse]
     */
    @POST("api/v1/privacy/export")
    suspend fun requestDataExportApiV1PrivacyExportPost(): Response<DataExportResponse>

    /**
     * POST api/v1/privacy/email-change
     * Request Email Change
     * Initiate an email-change request (Art. 16).  Rate-limited per client IP (&#x60;&#x60;settings.rate_limit_email_change&#x60;&#x60;): every call mails an address the caller names and does not have to own — the verification link when the address is free, the \&quot;someone tried to use your address\&quot; notice when it is taken (#957). Authentication bounds *who* can trigger that but not *how often*, and this router carried no limit at all.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param emailChangeCreateRequest 
     * @return [EmailChangeResponse]
     */
    @POST("api/v1/privacy/email-change")
    suspend fun requestEmailChangeApiV1PrivacyEmailChangePost(@Body emailChangeCreateRequest: EmailChangeCreateRequest): Response<EmailChangeResponse>

    /**
     * POST api/v1/privacy/erasure
     * Request Erasure
     * Request account erasure (Art. 17).
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param erasureCreateRequest 
     * @return [ErasureResponse]
     */
    @POST("api/v1/privacy/erasure")
    suspend fun requestErasureApiV1PrivacyErasurePost(@Body erasureCreateRequest: ErasureCreateRequest): Response<ErasureResponse>

    /**
     * POST api/v1/privacy/restrict
     * Restrict Processing
     * Create a processing-restriction (Art. 18).
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param restrictionCreateRequest 
     * @return [RestrictionResponse]
     */
    @POST("api/v1/privacy/restrict")
    suspend fun restrictProcessingApiV1PrivacyRestrictPost(@Body restrictionCreateRequest: RestrictionCreateRequest): Response<RestrictionResponse>

    /**
     * DELETE api/v1/privacy/consents/{purpose}
     * Revoke Consent
     * Revoke consent for an optional processing purpose.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 404: The requested resource does not exist.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param purpose Processing purpose whose consent to revoke.
     * @return [ConsentResponse]
     */
    @DELETE("api/v1/privacy/consents/{purpose}")
    suspend fun revokeConsentApiV1PrivacyConsentsPurposeDelete(@Path("purpose") purpose: kotlin.String): Response<ConsentResponse>

}
