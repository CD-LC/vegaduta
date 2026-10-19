package com.agenticai.core.tenancy;

/**
 * Mirrors the four Keycloak realm roles defined in
 * {@code deploy/keycloak/agentic-ai-realm.json} (Phase 0 Stage 1). Keycloak
 * remains the source of truth for a user's actual role at request time (read
 * off the JWT's {@code realm_access.roles} claim once Stage 3's
 * OAuth2 Resource Server config lands) - the {@code role} column on
 * {@link AppUser} using this enum is a denormalized read cache for fast
 * local lookups (e.g., listing a tenant's users without a Keycloak Admin
 * API round-trip), not the authorization decision point itself.
 */
public enum UserRole {
    TENANT_ADMIN,
    AGENT_BUILDER,
    DELEGATED_USER,
    END_CUSTOMER
}
