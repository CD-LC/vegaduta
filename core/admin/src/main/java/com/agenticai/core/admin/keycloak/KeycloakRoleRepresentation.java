package com.agenticai.core.admin.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Keycloak's {@code RoleRepresentation}, minimal subset. Role-mapping
 * endpoints (assigning a realm role to a user) require the full
 * representation including {@code id} - Keycloak identifies roles by id
 * internally, not just by name - so {@link KeycloakAdminClient} always
 * resolves a role name to its full representation (via
 * {@code GET /admin/realms/{realm}/roles/{roleName}}) before using it in a
 * role-mapping call, rather than constructing one with a null id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakRoleRepresentation(String id, String name, String description) {
}
