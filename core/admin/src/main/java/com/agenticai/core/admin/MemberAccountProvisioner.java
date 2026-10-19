package com.agenticai.core.admin;

/**
 * Seam for the control-plane {@code app_user} row of a member created through the admin
 * "add member" path ({@code POST /api/admin/users}). The row lives in the tenancy module,
 * which depends on this one, so the admin controller reaches it through this interface
 * (implemented in tenancy, injected optionally).
 *
 * <p>Idempotent and race-safe by contract: calling it for a user that already has a row, or
 * from two threads at once, must leave exactly one row and not throw.
 */
public interface MemberAccountProvisioner {

    /**
     * Ensures a {@code control_plane.app_user} row exists for this Keycloak user.
     *
     * @param tenantSchema the tenant's schema name (what {@code TenantContext#getTenantId} carries)
     * @param keycloakUserId the Keycloak subject
     * @param email the user's email
     * @param tenantAdmin whether the member is a tenant administrator
     */
    void ensureMemberAccount(String tenantSchema, String keycloakUserId, String email, boolean tenantAdmin);
}
