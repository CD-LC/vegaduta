package com.agenticai.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * {@link GranularPermissionConverter} is what makes stored
 * {@code user_permission_grant} rows real: it must layer {@code PERM_*}
 * authorities on top of the {@code ROLE_*} ones {@link
 * KeycloakRoleConverter} already emits, fail CLOSED when the permission
 * store is unavailable (roles-only, never invented permissions), and never
 * invoke the store for tenant-less callers.
 */
class GranularPermissionConverterTest {

    private static Jwt jwt(String subject, String tenantId, List<String> roles) {
        Jwt.Builder builder = Jwt.withTokenValue("token-value")
            .header("alg", "none")
            .subject(subject)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claim("realm_access", Map.of("roles", roles));
        if (tenantId != null) {
            builder.claim("tenant_id", tenantId);
        }
        return builder.build();
    }

    private static Set<String> names(Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    void emitsPermAuthoritiesOnTopOfRoles() {
        GranularPermissionConverter converter = new GranularPermissionConverter(Optional.of(
            (tenantId, subject, roleNames) -> Set.of("MANAGE_USERS", "VIEW_AUDIT_LOG")));

        Collection<GrantedAuthority> authorities =
            converter.convert(jwt("user-1", "tenant-a", List.of("tenant-admin")));

        assertEquals(
            Set.of("ROLE_tenant-admin", "PERM_MANAGE_USERS", "PERM_VIEW_AUDIT_LOG"),
            names(authorities));
    }

    @Test
    void passesTheJwtTenantSubjectAndRoleNamesToTheSource() {
        Set<String> seenTenant = new java.util.HashSet<>();
        Set<String> seenRoles = new java.util.HashSet<>();
        GranularPermissionConverter converter = new GranularPermissionConverter(Optional.of(
            (tenantId, subject, roleNames) -> {
                seenTenant.add(tenantId);
                seenRoles.addAll(roleNames);
                return Set.of();
            }));

        converter.convert(jwt("user-1", "tenant-a", List.of("agent-builder", "delegated-user")));

        assertEquals(Set.of("tenant-a"), seenTenant);
        assertEquals(Set.of("agent-builder", "delegated-user"), seenRoles);
    }

    @Test
    void tenantlessCallerGetsRolesOnlyAndTheSourceIsNeverConsulted() {
        boolean[] called = {false};
        GranularPermissionConverter converter = new GranularPermissionConverter(Optional.of(
            (tenantId, subject, roleNames) -> {
                called[0] = true;
                return Set.of("MANAGE_USERS");
            }));

        Collection<GrantedAuthority> authorities =
            converter.convert(jwt("operator-1", null, List.of("platform-admin")));

        assertEquals(Set.of("ROLE_platform-admin"), names(authorities));
        assertTrue(!called[0]);
    }

    @Test
    void missingSourceDegradesToRolesOnly() {
        GranularPermissionConverter converter = new GranularPermissionConverter(Optional.empty());

        Collection<GrantedAuthority> authorities =
            converter.convert(jwt("user-1", "tenant-a", List.of("tenant-admin")));

        assertEquals(Set.of("ROLE_tenant-admin"), names(authorities));
    }

    @Test
    void aFailingSourceDegradesToRolesOnly() {
        // Fail closed on the PERM_* side, not closed on authentication: a
        // control-plane outage must not take down every request - the caller
        // keeps ROLE_* and gets 403s on permission-gated surfaces.
        GranularPermissionConverter converter = new GranularPermissionConverter(Optional.of(
            (tenantId, subject, roleNames) -> {
                throw new RuntimeException("control_plane unavailable");
            }));

        Collection<GrantedAuthority> authorities =
            converter.convert(jwt("user-1", "tenant-a", List.of("tenant-admin")));

        assertEquals(Set.of("ROLE_tenant-admin"), names(authorities));
    }
}
