package com.agenticai.core.admin.auth0;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Minimal subset of Auth0's role object ({@code GET /api/v2/roles}, {@code GET
 * /api/v2/users/{id}/roles}). Role assignment endpoints take role <b>ids</b>, not names - same
 * resolve-by-name-then-post-ids shape as Keycloak's role-mappings flow, just flattened.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record Auth0RoleRepresentation(String id, String name, String description) {}
