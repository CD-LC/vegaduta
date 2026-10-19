package com.agenticai.core.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * {@code initialRoles} is optional - if set, it's applied via the same
 * replace-roles call {@code PUT /api/admin/users/{id}/roles} uses, right
 * after creation, so "create a user with roles" is one API call from the
 * caller's perspective even though it's two Keycloak Admin API calls
 * underneath. Was a single {@code initialRole: String} until this field was
 * widened to a set - the post-creation edit endpoint already accepted
 * multiple roles via a multi-checkbox UI, so creation being stuck at
 * single-select was an inconsistency, not a deliberate restriction.
 *
 * <p>{@code initialPermissions} is also optional and, unlike roles, has no
 * post-creation-only precedent to match - it's new. Applied on top of the
 * permission defaults {@code initialRoles} seeds (see {@code
 * DefaultPermissionBundles}), not in place of them.
 *
 * <p>{@code firstName}/{@code lastName} required, same reason as {@link
 * com.agenticai.core.tenancy.dto.SignupRequest}: this realm's Keycloak User
 * Profile config requires both, and without them an admin-created user
 * could never actually log in.
 *
 * <p>{@code inviteByEmail} (granular-RBAC pass, 2026-09-23): when true, the
 * user is created with NO password and receives Keycloak's
 * execute-actions email ({@code UPDATE_PASSWORD} + {@code VERIFY_EMAIL})
 * to set their own - the admin never sees or relays a credential. When
 * false, {@code temporaryPassword} is required (the controller enforces the
 * conditional - a record-level {@code @NotBlank} can't express "required
 * unless invited").
 */
public record CreateUserRequest(
    @NotBlank @Size(min = 3, max = 255, message = "Username must be between 3 and 255 characters") String username,
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(max = 255) String firstName,
    @NotBlank @Size(max = 255) String lastName,
    // max bounds the Keycloak password hash cost (same DoS as the 2026-10 signup report).
    @Size(min = 12, max = 128, message = "Temporary password must be 12-128 characters") String temporaryPassword,
    Boolean inviteByEmail,
    Set<String> initialRoles,
    Set<String> initialPermissions
) {
    public boolean invite() {
        return Boolean.TRUE.equals(inviteByEmail);
    }
}
