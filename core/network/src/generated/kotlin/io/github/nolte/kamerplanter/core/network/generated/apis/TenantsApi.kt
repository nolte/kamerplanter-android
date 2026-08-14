package io.github.nolte.kamerplanter.core.network.generated.apis

import io.github.nolte.kamerplanter.core.network.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import io.github.nolte.kamerplanter.core.network.generated.models.AcceptInvitationRequest
import io.github.nolte.kamerplanter.core.network.generated.models.AssignmentCreateRequest
import io.github.nolte.kamerplanter.core.network.generated.models.AssignmentResponse
import io.github.nolte.kamerplanter.core.network.generated.models.AssignmentUpdateRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ChangeRoleRequest
import io.github.nolte.kamerplanter.core.network.generated.models.EmailInvitationRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.InvitationLinkResponse
import io.github.nolte.kamerplanter.core.network.generated.models.InvitationResponse
import io.github.nolte.kamerplanter.core.network.generated.models.LinkInvitationRequest
import io.github.nolte.kamerplanter.core.network.generated.models.MemberInfoResponse
import io.github.nolte.kamerplanter.core.network.generated.models.MessageResponse
import io.github.nolte.kamerplanter.core.network.generated.models.TenantCreateRequest
import io.github.nolte.kamerplanter.core.network.generated.models.TenantResponse
import io.github.nolte.kamerplanter.core.network.generated.models.TenantUpdateRequest
import io.github.nolte.kamerplanter.core.network.generated.models.TenantWithRoleResponse

interface TenantsApi {
    /**
     * POST api/v1/tenants/invitations/accept
     * Accept Invitation
     * Accept an invitation using its token.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param acceptInvitationRequest 
     * @return [MessageResponse]
     */
    @POST("api/v1/tenants/invitations/accept")
    suspend fun acceptInvitationApiV1TenantsInvitationsAcceptPost(@Body acceptInvitationRequest: AcceptInvitationRequest): Response<MessageResponse>

    /**
     * PATCH api/v1/tenants/{tenant_slug}/members/{membership_key}/role
     * Change Member Role
     * Change a member&#39;s role. Admin only.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param membershipKey Document key of the membership.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param changeRoleRequest 
     * @return [MessageResponse]
     */
    @PATCH("api/v1/tenants/{tenant_slug}/members/{membership_key}/role")
    suspend fun changeMemberRoleApiV1TenantsTenantSlugMembersMembershipKeyRolePatch(@Path("membership_key") membershipKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body changeRoleRequest: ChangeRoleRequest): Response<MessageResponse>

    /**
     * POST api/v1/tenants/{tenant_slug}/assignments
     * Create Assignment
     * Create a location assignment. Admin only.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param assignmentCreateRequest 
     * @return [AssignmentResponse]
     */
    @POST("api/v1/tenants/{tenant_slug}/assignments")
    suspend fun createAssignmentApiV1TenantsTenantSlugAssignmentsPost(@Path("tenant_slug") tenantSlug: kotlin.String, @Body assignmentCreateRequest: AssignmentCreateRequest): Response<AssignmentResponse>

    /**
     * POST api/v1/tenants/{tenant_slug}/invitations/email
     * Create Email Invitation
     * Create an email invitation. Admin only.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param emailInvitationRequest 
     * @return [InvitationLinkResponse]
     */
    @POST("api/v1/tenants/{tenant_slug}/invitations/email")
    suspend fun createEmailInvitationApiV1TenantsTenantSlugInvitationsEmailPost(@Path("tenant_slug") tenantSlug: kotlin.String, @Body emailInvitationRequest: EmailInvitationRequest): Response<InvitationLinkResponse>

    /**
     * POST api/v1/tenants/{tenant_slug}/invitations/link
     * Create Link Invitation
     * Create a shareable invitation link. Admin only.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param linkInvitationRequest 
     * @return [InvitationLinkResponse]
     */
    @POST("api/v1/tenants/{tenant_slug}/invitations/link")
    suspend fun createLinkInvitationApiV1TenantsTenantSlugInvitationsLinkPost(@Path("tenant_slug") tenantSlug: kotlin.String, @Body linkInvitationRequest: LinkInvitationRequest): Response<InvitationLinkResponse>

    /**
     * POST api/v1/tenants
     * Create Organization
     * Create a new organization tenant.
     * Responses:
     *  - 201: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantCreateRequest 
     * @return [TenantResponse]
     */
    @POST("api/v1/tenants")
    suspend fun createOrganizationApiV1TenantsPost(@Body tenantCreateRequest: TenantCreateRequest): Response<TenantResponse>

    /**
     * DELETE api/v1/tenants/{tenant_slug}/assignments/{assignment_key}
     * Delete Assignment
     * Delete a location assignment. Admin only.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param assignmentKey Document key of the location assignment.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [MessageResponse]
     */
    @DELETE("api/v1/tenants/{tenant_slug}/assignments/{assignment_key}")
    suspend fun deleteAssignmentApiV1TenantsTenantSlugAssignmentsAssignmentKeyDelete(@Path("assignment_key") assignmentKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<MessageResponse>

    /**
     * DELETE api/v1/tenants/{tenant_slug}
     * Delete Tenant
     * Delete tenant and all associated data. Admin only.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [MessageResponse]
     */
    @DELETE("api/v1/tenants/{tenant_slug}")
    suspend fun deleteTenantApiV1TenantsTenantSlugDelete(@Path("tenant_slug") tenantSlug: kotlin.String): Response<MessageResponse>

    /**
     * GET api/v1/tenants/{tenant_slug}
     * Get Tenant
     * Get tenant details.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [TenantResponse]
     */
    @GET("api/v1/tenants/{tenant_slug}")
    suspend fun getTenantApiV1TenantsTenantSlugGet(@Path("tenant_slug") tenantSlug: kotlin.String): Response<TenantResponse>

    /**
     * POST api/v1/tenants/{tenant_slug}/leave
     * Leave Tenant
     * Leave a tenant.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [MessageResponse]
     */
    @POST("api/v1/tenants/{tenant_slug}/leave")
    suspend fun leaveTenantApiV1TenantsTenantSlugLeavePost(@Path("tenant_slug") tenantSlug: kotlin.String): Response<MessageResponse>

    /**
     * GET api/v1/tenants/{tenant_slug}/assignments
     * List Assignments
     * List all location assignments in a tenant.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [kotlin.collections.List<AssignmentResponse>]
     */
    @GET("api/v1/tenants/{tenant_slug}/assignments")
    suspend fun listAssignmentsApiV1TenantsTenantSlugAssignmentsGet(@Path("tenant_slug") tenantSlug: kotlin.String): Response<kotlin.collections.List<AssignmentResponse>>

    /**
     * GET api/v1/tenants/{tenant_slug}/invitations
     * List Invitations
     * List all invitations for a tenant. Admin only.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [kotlin.collections.List<InvitationResponse>]
     */
    @GET("api/v1/tenants/{tenant_slug}/invitations")
    suspend fun listInvitationsApiV1TenantsTenantSlugInvitationsGet(@Path("tenant_slug") tenantSlug: kotlin.String): Response<kotlin.collections.List<InvitationResponse>>

    /**
     * GET api/v1/tenants/{tenant_slug}/members
     * List Members
     * List all members of a tenant.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [kotlin.collections.List<MemberInfoResponse>]
     */
    @GET("api/v1/tenants/{tenant_slug}/members")
    suspend fun listMembersApiV1TenantsTenantSlugMembersGet(@Path("tenant_slug") tenantSlug: kotlin.String): Response<kotlin.collections.List<MemberInfoResponse>>

    /**
     * GET api/v1/tenants
     * List My Tenants
     * List all tenants the current user is a member of.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @return [kotlin.collections.List<TenantWithRoleResponse>]
     */
    @GET("api/v1/tenants")
    suspend fun listMyTenantsApiV1TenantsGet(): Response<kotlin.collections.List<TenantWithRoleResponse>>

    /**
     * DELETE api/v1/tenants/{tenant_slug}/members/{membership_key}
     * Remove Member
     * Remove a member from tenant. Admin only.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param membershipKey Document key of the membership.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [MessageResponse]
     */
    @DELETE("api/v1/tenants/{tenant_slug}/members/{membership_key}")
    suspend fun removeMemberApiV1TenantsTenantSlugMembersMembershipKeyDelete(@Path("membership_key") membershipKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<MessageResponse>

    /**
     * DELETE api/v1/tenants/{tenant_slug}/invitations/{invitation_key}
     * Revoke Invitation
     * Revoke an invitation. Admin only.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param invitationKey Document key of the invitation.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @return [MessageResponse]
     */
    @DELETE("api/v1/tenants/{tenant_slug}/invitations/{invitation_key}")
    suspend fun revokeInvitationApiV1TenantsTenantSlugInvitationsInvitationKeyDelete(@Path("invitation_key") invitationKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String): Response<MessageResponse>

    /**
     * PATCH api/v1/tenants/{tenant_slug}/assignments/{assignment_key}
     * Update Assignment
     * Update a location assignment. Admin only.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param assignmentKey Document key of the location assignment.
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param assignmentUpdateRequest 
     * @return [AssignmentResponse]
     */
    @PATCH("api/v1/tenants/{tenant_slug}/assignments/{assignment_key}")
    suspend fun updateAssignmentApiV1TenantsTenantSlugAssignmentsAssignmentKeyPatch(@Path("assignment_key") assignmentKey: kotlin.String, @Path("tenant_slug") tenantSlug: kotlin.String, @Body assignmentUpdateRequest: AssignmentUpdateRequest): Response<AssignmentResponse>

    /**
     * PATCH api/v1/tenants/{tenant_slug}
     * Update Tenant
     * Update tenant. Admin only.
     * Responses:
     *  - 200: Successful Response
     *  - 401: Missing, invalid, or expired credentials.
     *  - 403: Authenticated, but not allowed to access this resource.
     *  - 404: The requested resource does not exist.
     *  - 409: A conflicting resource already exists (duplicate key or unique constraint).
     *  - 422: The input data is invalid (field-level details in `details`).
     *
     * @param tenantSlug URL slug of the tenant the request is scoped to (REQ-024).
     * @param tenantUpdateRequest 
     * @return [TenantResponse]
     */
    @PATCH("api/v1/tenants/{tenant_slug}")
    suspend fun updateTenantApiV1TenantsTenantSlugPatch(@Path("tenant_slug") tenantSlug: kotlin.String, @Body tenantUpdateRequest: TenantUpdateRequest): Response<TenantResponse>

}
