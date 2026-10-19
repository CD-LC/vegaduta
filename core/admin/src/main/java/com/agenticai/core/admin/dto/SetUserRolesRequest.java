package com.agenticai.core.admin.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Set;

/**
 * Body for {@code PUT /api/admin/users/{userId}/roles}.
 *
 * <p>{@code @NotNull} is a shape check only - it says "a set was sent", not
 * "these roles may be granted". The actual authorization bound lives in
 * {@code TenantAssignableRoles}, enforced by {@code UserAdminController} and
 * again inside {@code KeycloakAdminClient#replaceUserRealmRoles}. It is
 * deliberately not expressed as a bean-validation annotation here: the same
 * allowlist has to hold on the non-DTO paths too (the client-level
 * defence-in-depth check, and {@code CreateUserRequest#initialRole}), and a
 * constraint that only fires on this one record would give a false sense
 * that the rule is centrally enforced.
 *
 * <p>An empty set is valid and means "remove all realm roles" - a
 * de-escalation, never an escalation.
 */
public record SetUserRolesRequest(@NotNull Set<String> roles) {
}
