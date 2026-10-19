package com.agenticai.core.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The {@code jwtGrantedAuthoritiesConverter} behind {@code SecurityConfig}'s
 * resource-server setup: delegates realm-role mapping to {@link
 * KeycloakRoleConverter} (unchanged - {@code ROLE_*} authorities exactly as
 * before), optionally emits {@code SCOPE_*} authorities from a configured
 * scope/permissions claim, then layers the caller's stored granular permissions
 * on top as {@code PERM_<name>} authorities via {@link PermissionAuthoritySource}.
 *
 * <p>Granular enforcement (2026-09-23): the permission catalog used to be
 * assignment-only - {@code SecurityConfig} required {@code
 * ROLE_tenant-admin} for every admin surface, so a stored grant could never
 * admit a non-admin. The tenant-scoped admin matchers now require {@code
 * PERM_*} authorities, which this converter emits from the caller's stored
 * grants. Because grants bind tenant-admins too (a {@code tenant-admin}
 * role seeds the full bundle but does not bypass it), this is where the
 * "admin creates a scoped user" story becomes real: an {@code
 * agent-builder} granted {@code MANAGE_BILLING} arrives with {@code
 * PERM_MANAGE_BILLING} and can call the billing surface - and nothing else.
 *
 * <p>Claim layout is {@link OidcClaimMapping}-configurable so the resource
 * server works against generic OIDC providers, not only Keycloak: the roles
 * claim path and the tenant claim follow {@code
 * app.security.oidc.*} instead of hardcoded Keycloak names. {@code SCOPE_*}
 * authorities are emitted only when {@code app.security.oidc.scopes-claim}
 * names a claim the provider sends ({@code scope}/{@code scp} as a
 * space-delimited string, or a list-valued claim like Auth0's {@code
 * permissions}); the empty default emits none, exactly as before. Token-asserted
 * scopes deliberately do NOT feed the {@code PERM_*} namespace - {@code PERM_*}
 * stays strictly stored-grant-sourced, so a token can never grant itself a
 * permission the control plane didn't record.
 *
 * <p>A missing source bean (narrow test contexts with no {@code core/admin}
 * on the classpath, or the {@code insecure} profile this converter never
 * sees) degrades to roles(-and-scopes)-only, exactly like before.
 */
public class GranularPermissionConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final Logger log = LoggerFactory.getLogger(GranularPermissionConverter.class);

    /** Authority prefix {@code SecurityConfig}'s matchers check, e.g. {@code PERM_MANAGE_USERS}. */
    public static final String PERMISSION_AUTHORITY_PREFIX = "PERM_";

    private static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

    /**
     * Prefix for token-asserted scopes ({@code app.security.oidc.scopes-claim}).
     * Matches {@link DelegationAuthenticationToken}'s convention so {@code
     * hasAuthority("SCOPE_...")} reads the same for both token kinds.
     */
    public static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

    private final KeycloakRoleConverter roleConverter;
    private final Optional<PermissionAuthoritySource> permissionSource;
    private final OidcClaimMapping claimMapping;

    public GranularPermissionConverter(Optional<PermissionAuthoritySource> permissionSource) {
        this(permissionSource, OidcClaimMapping.keycloakDefaults());
    }

    /**
     * @param claimMapping which claims roles/tenant/scopes live in (see {@link
     *     OidcClaimMapping}); {@code null} falls back to the Keycloak defaults.
     */
    public GranularPermissionConverter(
            Optional<PermissionAuthoritySource> permissionSource, OidcClaimMapping claimMapping) {
        this.permissionSource = permissionSource;
        this.claimMapping = claimMapping == null ? OidcClaimMapping.keycloakDefaults() : claimMapping;
        this.roleConverter = new KeycloakRoleConverter(this.claimMapping.rolesClaimPath());
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> roleAuthorities = roleConverter.convert(jwt);
        Collection<GrantedAuthority> baseAuthorities = withScopeAuthorities(jwt, roleAuthorities);
        if (permissionSource.isEmpty()) {
            return baseAuthorities;
        }
        String tenantId = JwtClaimPaths.valueAsString(jwt, claimMapping.tenantClaim());
        String subject = jwt.getSubject();
        if (tenantId == null || subject == null) {
            // Tenant-less caller (platform-admin) or malformed token - roles
            // only, never a permission lookup.
            return baseAuthorities;
        }
        Set<String> roleNames = roleAuthorities.stream()
            .map(GrantedAuthority::getAuthority)
            .filter(authority -> authority.startsWith(ROLE_AUTHORITY_PREFIX))
            .map(authority -> authority.substring(ROLE_AUTHORITY_PREFIX.length()))
            .collect(Collectors.toUnmodifiableSet());
        try {
            Set<String> permissions = permissionSource.get().effectivePermissions(tenantId, subject, roleNames);
            if (permissions.isEmpty()) {
                return baseAuthorities;
            }
            Set<GrantedAuthority> combined = new LinkedHashSet<>(baseAuthorities);
            for (String permission : permissions) {
                if (permission != null && !permission.isBlank()) {
                    combined.add(new SimpleGrantedAuthority(PERMISSION_AUTHORITY_PREFIX + permission));
                }
            }
            return combined;
        } catch (RuntimeException e) {
            // A permission-store failure must not take down authentication for
            // every request - the caller degrades to role authorities only
            // (i.e. they keep whatever ROLE_* already grants them and get 403s
            // on PERM_*-gated surfaces, which is the fail-closed direction).
            log.error("Permission lookup failed for subject '{}' in tenant '{}' - roles-only authorities",
                subject, tenantId, e);
            return baseAuthorities;
        }
    }

    /**
     * Adds {@code SCOPE_*} authorities from the configured scopes claim (off by
     * default). Accepts both wire shapes providers actually send: a
     * space-delimited string ({@code scope}/{@code scp}) or a JSON array
     * (Auth0's {@code permissions}). Any other claim type is ignored - a
     * malformed scope claim must not fail the request nor grant anything.
     */
    private Collection<GrantedAuthority> withScopeAuthorities(
            Jwt jwt, Collection<GrantedAuthority> base) {
        String scopesClaim = claimMapping.scopesClaim();
        if (scopesClaim == null || scopesClaim.isBlank()) {
            return base;
        }
        Object raw = JwtClaimPaths.value(jwt, scopesClaim);
        Collection<String> scopes;
        if (raw instanceof String scopeString) {
            scopes =
                    Arrays.stream(scopeString.split("\\s+"))
                            .filter(scope -> !scope.isBlank())
                            .toList();
        } else if (raw instanceof Collection<?> scopeList) {
            scopes = scopeList.stream().map(String::valueOf).toList();
        } else {
            return base;
        }
        if (scopes.isEmpty()) {
            return base;
        }
        Set<GrantedAuthority> combined = new LinkedHashSet<>(base);
        for (String scope : scopes) {
            combined.add(new SimpleGrantedAuthority(SCOPE_AUTHORITY_PREFIX + scope));
        }
        return combined;
    }
}
