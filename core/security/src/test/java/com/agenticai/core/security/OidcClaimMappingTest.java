package com.agenticai.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.agenticai.core.security.ImpersonationGrantResolver.ViewAsSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Generic-OIDC claim mapping ({@link OidcClaimMapping} / {@link JwtClaimPaths},
 * {@code app.security.oidc.*}): proves the configurable claim locations feed the
 * same converters and filters the Keycloak defaults do, that the default mapping
 * is byte-for-byte the historical Keycloak behaviour, and that every missing or
 * malformed claim fails CLOSED (no authorities, no tenant) rather than throwing
 * or granting.
 */
class OidcClaimMappingTest {

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder =
                Jwt.withTokenValue("token-value")
                        .header("alg", "none")
                        .subject("user-1")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300));
        builder.claims(c -> c.putAll(claims));
        return builder.build();
    }

    private static Set<String> names(Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------ defaults

    @Test
    void defaultMappingReproducesTheKeycloakTokenShapeExactly() {
        // realm_access.roles + tenant_id, i.e. the token every existing test
        // fixture builds - must produce identical authorities to before.
        OidcClaimMapping mapping = OidcClaimMapping.keycloakDefaults();
        GranularPermissionConverter converter =
                new GranularPermissionConverter(
                        Optional.of((tenantId, subject, roles) -> Set.of("MANAGE_USERS")), mapping);

        Collection<GrantedAuthority> authorities =
                converter.convert(
                        jwt(
                                Map.of(
                                        "realm_access", Map.of("roles", List.of("tenant-admin")),
                                        "tenant_id", "tenant-a")));

        assertEquals(
                Set.of("ROLE_tenant-admin", "PERM_MANAGE_USERS"),
                names(authorities));
    }

    @Test
    void blankComponentsFallBackToTheKeycloakDefaults() {
        OidcClaimMapping mapping = new OidcClaimMapping("", null, null);

        assertEquals("realm_access.roles", mapping.rolesClaimPath());
        assertEquals("tenant_id", mapping.tenantClaim());
        assertEquals("", mapping.scopesClaim());
    }

    // ------------------------------------------------------------------ roles claim path

    @Test
    void flatRolesClaimNameCoversEntraAndOktaShapes() {
        // Microsoft Entra app roles: top-level "roles"; Okta: top-level "groups".
        for (String claim : List.of("roles", "groups")) {
            KeycloakRoleConverter converter = new KeycloakRoleConverter(claim);

            Collection<GrantedAuthority> authorities =
                    converter.convert(jwt(Map.of(claim, List.of("tenant-admin"))));

            assertEquals(Set.of("ROLE_tenant-admin"), names(authorities), "claim " + claim);
        }
    }

    @Test
    void auth0StyleNamespacedRolesClaimIsReadLiterally() {
        // A single top-level claim key that itself contains dots - the
        // literal-first lookup must find it before any dot-path splitting
        // mangles it into unreachable segments.
        KeycloakRoleConverter converter = new KeycloakRoleConverter("https://example.com/roles");

        Collection<GrantedAuthority> authorities =
                converter.convert(jwt(Map.of("https://example.com/roles", List.of("agent-builder"))));

        assertEquals(Set.of("ROLE_agent-builder"), names(authorities));
    }

    @Test
    void arbitraryNestedDotPathWalksIntermediateMaps() {
        KeycloakRoleConverter converter = new KeycloakRoleConverter("custom.app_metadata.roles");

        Collection<GrantedAuthority> authorities =
                converter.convert(
                        jwt(
                                Map.of(
                                        "custom",
                                        Map.of(
                                                "app_metadata",
                                                Map.of("roles", List.of("tenant-admin"))))));

        assertEquals(Set.of("ROLE_tenant-admin"), names(authorities));
    }

    @Test
    void missingConfiguredRolesClaimYieldsNoAuthorities() {
        KeycloakRoleConverter converter = new KeycloakRoleConverter("roles");

        // Token carries ONLY the Keycloak-shape claim - with the path
        // remapped, that is simply "no roles", never a fallback grant.
        Collection<GrantedAuthority> authorities =
                converter.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("tenant-admin")))));

        assertTrue(authorities.isEmpty());
    }

    @Test
    void malformedClaimTypesAreIgnoredInsteadOfThrowing() {
        KeycloakRoleConverter converter = new KeycloakRoleConverter("realm_access.roles");

        // The path resolves but to a non-array value.
        assertTrue(converter.convert(jwt(Map.of("realm_access", Map.of("roles", "admin")))).isEmpty());
        // An intermediate segment exists but is not a map.
        assertTrue(converter.convert(jwt(Map.of("realm_access", "not-a-map"))).isEmpty());
        // A scalar under a custom path.
        assertTrue(new KeycloakRoleConverter("roles").convert(jwt(Map.of("roles", 42))).isEmpty());
    }

    // ------------------------------------------------------------------ tenant claim

    @Test
    void customTenantClaimDrivesThePermissionLookup() {
        OidcClaimMapping mapping = new OidcClaimMapping("roles", "org_id", "");
        Set<String> seenTenant = new java.util.HashSet<>();
        GranularPermissionConverter converter =
                new GranularPermissionConverter(
                        Optional.of(
                                (tenantId, subject, roles) -> {
                                    seenTenant.add(tenantId);
                                    return Set.of();
                                }),
                        mapping);

        converter.convert(jwt(Map.of("roles", List.of("user"), "org_id", "tenant-x")));

        assertEquals(Set.of("tenant-x"), seenTenant);
    }

    @Test
    void tokenCarryingOnlyTheUnmappedClaimIsTenantlessAndFailsClosed() {
        // The deployment expects the tenant in org_id; a token that only has
        // tenant_id must be treated as tenant-LESS (roles only, permission
        // source never consulted) - the same fail-closed shape as a platform
        // admin token, never an accidental grant under the wrong tenant.
        OidcClaimMapping mapping = new OidcClaimMapping("roles", "org_id", "");
        boolean[] sourceCalled = {false};
        GranularPermissionConverter converter =
                new GranularPermissionConverter(
                        Optional.of(
                                (tenantId, subject, roles) -> {
                                    sourceCalled[0] = true;
                                    return Set.of("MANAGE_USERS");
                                }),
                        mapping);

        Collection<GrantedAuthority> authorities =
                converter.convert(jwt(Map.of("roles", List.of("tenant-admin"), "tenant_id", "tenant-a")));

        assertEquals(Set.of("ROLE_tenant-admin"), names(authorities));
        assertTrue(!sourceCalled[0]);
    }

    @Test
    void nonStringTenantClaimIsTreatedAsMissing() {
        OidcClaimMapping mapping = new OidcClaimMapping("roles", "org_id", "");
        boolean[] sourceCalled = {false};
        GranularPermissionConverter converter =
                new GranularPermissionConverter(
                        Optional.of(
                                (tenantId, subject, roles) -> {
                                    sourceCalled[0] = true;
                                    return Set.of("MANAGE_USERS");
                                }),
                        mapping);

        Collection<GrantedAuthority> authorities =
                converter.convert(jwt(Map.of("roles", List.of("tenant-admin"), "org_id", List.of("a", "b"))));

        assertEquals(Set.of("ROLE_tenant-admin"), names(authorities));
        assertTrue(!sourceCalled[0]);
    }

    @Test
    void tenantContextFilterReadsTheConfiguredTenantClaim() throws Exception {
        Jwt token = jwt(Map.of("org_id", "tenant-z"));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                token, List.of(new SimpleGrantedAuthority("ROLE_tenant-admin"))));

        TenantContextFilter filter =
                new TenantContextFilter(Optional.empty(), Optional.empty(), "org_id");
        FilterChain chain = mock(FilterChain.class);
        AtomicReference<TenantContext> seen = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            seen.set(TenantContext.current().orElse(null));
                            return null;
                        })
                .when(chain)
                .doFilter(any(), any());

        filter.doFilterInternal(
                new MockHttpServletRequest("GET", "/api/agents"),
                mock(HttpServletResponse.class),
                chain);

        assertEquals("tenant-z", seen.get().getTenantId());
        assertTrue(TenantContext.current().isEmpty());
    }

    // ------------------------------------------------------------------ scopes claim

    @Test
    void scopesClaimIsOffByDefault() {
        // A token carrying scope claims produces no SCOPE_* authorities unless
        // the mapping opts in - the pre-generic-OIDC behaviour exactly.
        GranularPermissionConverter converter = new GranularPermissionConverter(Optional.empty());

        Collection<GrantedAuthority> authorities =
                converter.convert(jwt(Map.of("scope", "read:agents write:agents")));

        assertTrue(names(authorities).stream().noneMatch(a -> a.startsWith("SCOPE_")));
    }

    @Test
    void stringScopeClaimSplitsOnWhitespace() {
        OidcClaimMapping mapping = new OidcClaimMapping("roles", "tenant_id", "scp");
        GranularPermissionConverter converter =
                new GranularPermissionConverter(Optional.empty(), mapping);

        Collection<GrantedAuthority> authorities =
                converter.convert(jwt(Map.of("scp", "read:agents write:agents")));

        assertEquals(Set.of("SCOPE_read:agents", "SCOPE_write:agents"), names(authorities));
    }

    @Test
    void listValuedScopesClaimMapsEachEntry() {
        // Auth0's permissions claim is a JSON array, not a space-delimited string.
        OidcClaimMapping mapping = new OidcClaimMapping("roles", "tenant_id", "permissions");
        GranularPermissionConverter converter =
                new GranularPermissionConverter(Optional.empty(), mapping);

        Collection<GrantedAuthority> authorities =
                converter.convert(jwt(Map.of("permissions", List.of("read:agents"))));

        assertEquals(Set.of("SCOPE_read:agents"), names(authorities));
    }

    @Test
    void malformedScopesClaimGrantsNothing() {
        OidcClaimMapping mapping = new OidcClaimMapping("roles", "tenant_id", "scp");
        GranularPermissionConverter converter =
                new GranularPermissionConverter(Optional.empty(), mapping);

        assertTrue(converter.convert(jwt(Map.of("scp", 42))).isEmpty());
    }

    // ------------------------------------------------------------------ view-as synthetic token

    @Test
    void targetJwtWritesTheDefaultKeycloakShape() {
        Jwt admin = jwt(Map.of("iss", "https://auth.example.com/realms/agentic-ai"));
        ViewAsSession session = session();

        Jwt target = ViewAsFilter.targetJwt(admin, session, OidcClaimMapping.keycloakDefaults());

        assertEquals("tenant_target", target.getClaimAsString("tenant_id"));
        assertEquals(
                List.of("tenant-admin", "user"),
                target.getClaimAsMap("realm_access").get("roles"));
    }

    @Test
    void targetJwtHonoursAConfiguredClaimMapping() {
        OidcClaimMapping mapping =
                new OidcClaimMapping("https://example.com/roles", "org_id", "");
        Jwt admin = jwt(Map.of("iss", "https://login.example.com/"));
        ViewAsSession session = session();

        Jwt target = ViewAsFilter.targetJwt(admin, session, mapping);

        // The synthetic token must be readable by the SAME mapping the real
        // tokens use - namespaced roles written literally, tenant under org_id.
        assertEquals("tenant_target", JwtClaimPaths.valueAsString(target, "org_id"));
        assertNull(JwtClaimPaths.valueAsString(target, "tenant_id"));
        assertEquals(
                List.of("tenant-admin", "user"),
                JwtClaimPaths.value(target, "https://example.com/roles"));

        // And the configured converter agrees - platform-admin still stripped.
        GranularPermissionConverter converter =
                new GranularPermissionConverter(Optional.empty(), mapping);
        assertEquals(
                Set.of("ROLE_tenant-admin", "ROLE_user"), names(converter.convert(target)));
    }

    @Test
    void putNestedBuildsDotPathMapsAndLiteralNamespacedKeys() {
        Map<String, Object> claims = new LinkedHashMap<>();

        JwtClaimPaths.putNested(claims, "realm_access.roles", List.of("tenant-admin"));
        JwtClaimPaths.putNested(claims, "https://example.com/roles", List.of("x"));
        JwtClaimPaths.putNested(claims, "groups", List.of("g"));

        assertEquals(
                Map.of("roles", List.of("tenant-admin")), claims.get("realm_access"));
        assertEquals(List.of("x"), claims.get("https://example.com/roles"));
        assertEquals(List.of("g"), claims.get("groups"));
    }

    private static ViewAsSession session() {
        return new ViewAsSession(
                UUID.randomUUID(),
                "admin-kc-1",
                "ops@vegaduta.ai",
                UUID.randomUUID(),
                "tenant_target",
                "Target Co",
                "target-kc-9",
                "user@target.co",
                List.of("tenant-admin", "platform-admin", "user"),
                Instant.now().plusSeconds(600),
                null);
    }
}
