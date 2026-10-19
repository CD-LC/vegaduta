package com.agenticai.core.security;

import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps a JWT's configured roles claim (default: Keycloak's {@code
 * realm_access.roles}) to Spring Security authorities, prefixed {@code ROLE_}
 * and otherwise unchanged (e.g. role {@code "tenant-admin"} becomes authority
 * {@code "ROLE_tenant-admin"}) - deliberately not upper-cased/underscored, so
 * {@link SecurityConfig}'s {@code hasAuthority(...)} calls can reference the
 * exact same role names used in
 * {@code deploy/keycloak/agentic-ai-realm.json} without a naming-convention
 * translation step to get wrong.
 *
 * <p>The claim location is configurable via {@code app.security.oidc.roles-claim-path}
 * ({@link OidcClaimMapping}) so generic OIDC providers can be mapped in: a
 * dot-path for nested claims (the Keycloak default), a flat claim name
 * (Microsoft Entra's {@code roles}, Okta's {@code groups}), or an Auth0-style
 * namespaced claim ({@code https://example.com/roles}) - see {@link
 * JwtClaimPaths} for the resolution rule. The zero-arg constructor keeps the
 * historical Keycloak path and is what every pre-generic-OIDC caller and test
 * uses. As before, a missing claim or one that is not a JSON array yields NO
 * authorities - the converter never grants anything on a malformed token.
 */
public class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final String rolesClaimPath;

    /** The historical Keycloak layout - {@link JwtClaimPaths#DEFAULT_ROLES_CLAIM_PATH}. */
    public KeycloakRoleConverter() {
        this(JwtClaimPaths.DEFAULT_ROLES_CLAIM_PATH);
    }

    /**
     * @param rolesClaimPath claim path to the roles list (literal name or
     *     dot-path - see {@link JwtClaimPaths}); a blank path resolves no roles.
     */
    public KeycloakRoleConverter(String rolesClaimPath) {
        this.rolesClaimPath = rolesClaimPath;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object roles = JwtClaimPaths.value(jwt, rolesClaimPath);
        if (!(roles instanceof List<?> roleList)) {
            return List.of();
        }
        return roleList.stream()
            .map(String::valueOf)
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
            .toList();
    }
}
