package com.agenticai.core.admin.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Set;

/**
 * Body for {@code PUT /api/admin/users/{userId}/permissions}. Same shape and
 * rationale as {@link SetUserRolesRequest}: {@code @NotNull} is a shape
 * check only ("a set was sent"), not "these permissions may be granted" -
 * the real bound is {@code TenantAssignablePermissions}, enforced in {@code
 * UserAdminController}, not expressed as a bean-validation annotation here
 * since the same catalog also has to hold on the non-DTO
 * {@code CreateUserRequest#initialPermissions} path.
 *
 * <p>An empty set is valid and means "remove all permissions" - a
 * de-escalation, never an escalation.
 */
public record SetUserPermissionsRequest(@NotNull Set<String> permissions) {
}
