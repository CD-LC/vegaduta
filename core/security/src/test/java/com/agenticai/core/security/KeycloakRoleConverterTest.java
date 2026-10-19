package com.agenticai.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * {@link KeycloakRoleConverter} is where a Keycloak JWT's {@code
 * realm_access.roles} claim becomes the Spring Security authorities every
 * {@code hasAuthority("ROLE_...")} check in {@link SecurityConfig} (and
 * every {@code TenantContext.hasRole(...)} call downstream) actually relies
 * on - a bug here is a direct authorization bypass or lockout, not a cosmetic
 * issue. Had zero test coverage before this suite despite being pure,
 * dependency-free logic.
 */
class KeycloakRoleConverterTest {

    private final KeycloakRoleConverter converter = new KeycloakRoleConverter();

    private static Jwt jwtWithRealmAccess(Object realmAccessValue) {
        Jwt.Builder builder = Jwt.withTokenValue("token-value")
            .header("alg", "none")
            .subject("user-1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300));
        if (realmAccessValue != null) {
            builder.claim("realm_access", realmAccessValue);
        }
        return builder.build();
    }

    @Test
    void mapsRealmRolesToRolePrefixedAuthorities() {
        Jwt jwt = jwtWithRealmAccess(Map.of("roles", List.of("tenant-admin", "platform-admin")));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        Set<String> names = authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        assertEquals(Set.of("ROLE_tenant-admin", "ROLE_platform-admin"), names);
    }

    @Test
    void roleNamesAreNotUppercasedOrTranslated() {
        // Deliberately preserves exact casing/hyphenation so it matches
        // deploy/keycloak/agentic-ai-realm.json's role names verbatim -
        // see this class's own javadoc. A "helpful" uppercase/underscore
        // normalization here would silently break every hasAuthority(...)
        // check in SecurityConfig.
        Jwt jwt = jwtWithRealmAccess(Map.of("roles", List.of("tenant-admin")));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertEquals(1, authorities.size());
        assertEquals("ROLE_tenant-admin", authorities.iterator().next().getAuthority());
        assertTrue(authorities.stream().noneMatch(a -> a.getAuthority().equals("ROLE_TENANT-ADMIN")));
        assertTrue(authorities.stream().noneMatch(a -> a.getAuthority().equals("ROLE_TENANT_ADMIN")));
    }

    @Test
    void missingRealmAccessClaimReturnsEmptyAuthorities() {
        // A valid JWT with no realm_access at all (e.g. issued by a
        // differently-configured client) - must fail closed to "no roles",
        // never throw and never grant anything.
        Jwt jwt = jwtWithRealmAccess(null);

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertTrue(authorities.isEmpty());
    }

    @Test
    void realmAccessPresentButRolesKeyMissingReturnsEmptyAuthorities() {
        Jwt jwt = jwtWithRealmAccess(Map.of("some_other_key", "value"));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertTrue(authorities.isEmpty());
    }

    @Test
    void realmAccessWithNonListRolesReturnsEmptyAuthoritiesInsteadOfThrowing() {
        // "roles" present but not actually a List (malformed/unexpected
        // token shape) - the instanceof pattern-match guard must fail
        // closed rather than throw a ClassCastException that would turn
        // into an unhandled 500 for every request bearing this token.
        Jwt jwt = jwtWithRealmAccess(Map.of("roles", "tenant-admin"));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertTrue(authorities.isEmpty());
    }

    @Test
    void emptyRolesListReturnsEmptyAuthorities() {
        Jwt jwt = jwtWithRealmAccess(Map.of("roles", List.of()));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertTrue(authorities.isEmpty());
    }

    @Test
    void singleRoleMapsToSingleAuthority() {
        Jwt jwt = jwtWithRealmAccess(Map.of("roles", List.of("platform-admin")));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertEquals(List.of("ROLE_platform-admin"),
            authorities.stream().map(GrantedAuthority::getAuthority).toList());
    }
}
