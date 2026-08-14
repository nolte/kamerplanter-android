package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.AttachmentListResponse
import io.github.nolte.kamerplanter.core.network.generated.models.AttachmentResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.PresignDownloadResponse
import io.github.nolte.kamerplanter.core.network.generated.models.PresignUploadRequest
import io.github.nolte.kamerplanter.core.network.generated.models.PresignUploadResponse

import okhttp3.MultipartBody

interface AttachmentsApi {
    /**
     * DELETE api/v1/t/{tenant_slug}/attachments/{attachment_id}
     * Delete Attachment
     * Delete an attachment and its thumbnails (idempotent).
     * Responses:
     *  - 204: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param attachmentId Identifier of the attachment.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [Unit]
     */
    @DELETE("api/v1/t/{tenant_slug}/attachments/{attachment_id}")
    suspend fun deleteAttachmentApiV1TTenantSlugAttachmentsAttachmentIdDelete(@Path("attachment_id") attachmentId: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<Unit>

    /**
     * GET api/v1/t/{tenant_slug}/attachments/{attachment_id}
     * Download Attachment
     * Serve the original object.  Presign-capable backends (S3) 307-redirect to a short-lived signed URL; otherwise the object is proxy-streamed with a private cache header and an ETag (&#x3D; content SHA-256).
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param attachmentId Identifier of the attachment.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [kotlinx.serialization.json.JsonElement]
     */
    @GET("api/v1/t/{tenant_slug}/attachments/{attachment_id}")
    suspend fun downloadAttachmentApiV1TTenantSlugAttachmentsAttachmentIdGet(@Path("attachment_id") attachmentId: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<kotlinx.serialization.json.JsonElement>

    /**
     * GET api/v1/t/{tenant_slug}/attachments/{attachment_id}/thumbnails/{size}
     * Download Thumbnail
     * Serve a thumbnail rendition; lazily regenerates a missing rendition.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param attachmentId Identifier of the attachment.
     * @param size Thumbnail edge size in pixels.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [kotlinx.serialization.json.JsonElement]
     */
    @GET("api/v1/t/{tenant_slug}/attachments/{attachment_id}/thumbnails/{size}")
    suspend fun downloadThumbnailApiV1TTenantSlugAttachmentsAttachmentIdThumbnailsSizeGet(@Path("attachment_id") attachmentId: kotlin.String, @Path("size") size: kotlin.Int, @Path("tenant_slug") tenantSlug: kotlin.String): Response<kotlinx.serialization.json.JsonElement>

    /**
     * GET api/v1/t/{tenant_slug}/attachments
     * List Attachments
     * List the tenant&#39;s attachments, newest first, optionally filtered by category.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param category Filter the listing by attachment category. (optional)
     * @param offset Number of items to skip from the start of the result set. (optional, default to 0)
     * @param limit Maximum number of items to return (1-200). (optional, default to 50)
     * @return [AttachmentListResponse]
     */
    @GET("api/v1/t/{tenant_slug}/attachments")
    suspend fun listAttachmentsApiV1TTenantSlugAttachmentsGet(@Path("tenant_slug") tenantSlug: kotlin.String, @Query("category") category: kotlin.String? = null, @Query("offset") offset: kotlin.Int? = 0, @Query("limit") limit: kotlin.Int? = 50): Response<AttachmentListResponse>

    /**
     * GET api/v1/t/{tenant_slug}/attachments/{attachment_id}/presign-download
     * Presign Download
     * Return an explicit signed/token download URL for the original object.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param attachmentId Identifier of the attachment.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [PresignDownloadResponse]
     */
    @GET("api/v1/t/{tenant_slug}/attachments/{attachment_id}/presign-download")
    suspend fun presignDownloadApiV1TTenantSlugAttachmentsAttachmentIdPresignDownloadGet(@Path("attachment_id") attachmentId: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<PresignDownloadResponse>

    /**
     * POST api/v1/t/{tenant_slug}/attachments/presign-upload
     * Presign Upload
     * Presigned upload is deliberately not offered in B1 (SEC-003).  A presigned PUT URL would let the client write arbitrary, unvalidated bytes straight into the bucket — bypassing the whole server-side pipeline (magic-byte, size, virus scan, EXIF strip). Until a server-side post-upload validation hook exists (S3 event → Celery re-validation; follow-up work), this endpoint returns 409 for ALL backends and the proxy upload (&#x60;&#x60;POST /attachments&#x60;&#x60;, full pipeline) is the only supported upload path — which NFR-013 explicitly defines as the safe fallback.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param presignUploadRequest 
     * @return [PresignUploadResponse]
     */
    @POST("api/v1/t/{tenant_slug}/attachments/presign-upload")
    suspend fun presignUploadApiV1TTenantSlugAttachmentsPresignUploadPost(@Path("tenant_slug") tenantSlug: kotlin.String, @Body presignUploadRequest: PresignUploadRequest): Response<PresignUploadResponse>

    /**
     * GET api/v1/attachments/token/{token}
     * Redeem Token
     * Verify a local-fs signed download token and stream the object.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param token Backend-signed download token to redeem.
     * @return [kotlinx.serialization.json.JsonElement]
     */
    @GET("api/v1/attachments/token/{token}")
    suspend fun redeemTokenApiV1AttachmentsTokenTokenGet(@Path("token") token: kotlin.String): Response<kotlinx.serialization.json.JsonElement>

    /**
     * POST api/v1/t/{tenant_slug}/attachments
     * Upload Attachment
     * Proxy-upload an attachment through the server-side validation pipeline.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param category Attachment category the file belongs to.
     * @param file 
     * @return [AttachmentResponse]
     */
    @Multipart
    @POST("api/v1/t/{tenant_slug}/attachments")
    suspend fun uploadAttachmentApiV1TTenantSlugAttachmentsPost(@Path("tenant_slug") tenantSlug: kotlin.String, @Part("category") category: kotlin.String, @Part file: MultipartBody.Part): Response<AttachmentResponse>

}
