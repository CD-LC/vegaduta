package com.agenticai.core.security;

import java.util.Set;

/**
 * SPI bridging the security module to the granular-permission store (which
 * lives in {@code core/admin} - this module cannot depend on it, so the
 * dependency points this way). Exactly one implementation exists:
 * {@code AdminPermissionAuthoritySource}, backed by the
 * {@code control_plane.user_permission_grant} rows a tenant-admin manages
 * through {@code /api/admin/users/**}.
 *
 * <p>The returned names are catalog permission names ({@code "MANAGE_USERS"}
 * etc.) as plain strings - this module deliberately does not know the
 * {@code Permission} enum. The JWT converter wraps each in a
 * {@code PERM_<name>} authority (see {@link GranularPermissionConverter})
 * which {@code SecurityConfig}'s tenant-scoped admin matchers then require.
 */
public interface PermissionAuthoritySource {

    /**
     * The caller's effective permission names for this request. Called once
     * per authenticated request during JWT→authorities conversion, so
     * implementations are expected to cache briefly and to be safe under
     * concurrent calls.
     *
     * @param tenantId   the JWT's {@code tenant_id} claim; {@code null} for
     *                   tenant-less callers (e.g. {@code platform-admin}),
     *                   for which an empty set is the correct answer
     * @param subject    the JWT {@code sub} (the Keycloak user id)
     * @param roleNames  the caller's realm role names WITHOUT the {@code
     *                   ROLE_} authority prefix, so the implementation can
     *                   apply its own role-bundle/lazy-seed rules
     */
    Set<String> effectivePermissions(String tenantId, String subject, Set<String> roleNames);
}
