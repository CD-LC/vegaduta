package com.agenticai.core.admin.localauth;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One user of a local-mode install ({@code control_plane.local_account}).
 *
 * @param passwordHash a Spring {@code DelegatingPasswordEncoder} value, or {@code null} for an
 *     account that has no password yet (invited, or the first-run owner before setup)
 * @param tenantId the tenant SCHEMA name, the value Keycloak carries in its {@code tenant_id}
 *     attribute; {@code null} for a tenant-less account
 * @param roles realm-role names, e.g. {@code tenant-admin}
 * @param attributes identity attributes other than {@code tenant_id}, in Keycloak's multi-valued shape
 */
public record LocalAccount(
        String id,
        String email,
        String firstName,
        String lastName,
        String passwordHash,
        boolean mustChangePassword,
        boolean enabled,
        boolean setupPending,
        String tenantId,
        Set<String> roles,
        Map<String, List<String>> attributes,
        Instant createdAt,
        Instant lastLoginAt) {

    public LocalAccount {
        roles = roles == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(roles));
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public LocalAccount withPassword(String hash, boolean mustChange, boolean stillPending) {
        return new LocalAccount(id, email, firstName, lastName, hash, mustChange, enabled, stillPending, tenantId, roles, attributes, createdAt, lastLoginAt);
    }

    public LocalAccount withEnabled(boolean value) {
        return new LocalAccount(id, email, firstName, lastName, passwordHash, mustChangePassword, value, setupPending, tenantId, roles, attributes, createdAt, lastLoginAt);
    }

    public LocalAccount withTenant(String value) {
        return new LocalAccount(id, email, firstName, lastName, passwordHash, mustChangePassword, enabled, setupPending, value, roles, attributes, createdAt, lastLoginAt);
    }

    public LocalAccount withRoles(Set<String> value) {
        return new LocalAccount(id, email, firstName, lastName, passwordHash, mustChangePassword, enabled, setupPending, tenantId, value, attributes, createdAt, lastLoginAt);
    }

    public LocalAccount withAttributes(Map<String, List<String>> value) {
        return new LocalAccount(id, email, firstName, lastName, passwordHash, mustChangePassword, enabled, setupPending, tenantId, roles, value, createdAt, lastLoginAt);
    }
}
