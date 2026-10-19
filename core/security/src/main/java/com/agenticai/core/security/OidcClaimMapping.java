package com.agenticai.core.security;

/**
 * The configured mapping between an OIDC provider's token claims and the claim
 * shapes core's security layer understands - what makes the resource server work
 * against generic providers (Auth0, Microsoft Entra, Okta, ...) instead of only
 * Keycloak. Bound in {@code SecurityConfig} from {@code app.security.oidc.*}
 * (see {@code application-security.yml}); every component that reads a mapped
 * claim takes the relevant value from here rather than hardcoding a Keycloak
 * claim name.
 *
 * <p>Defaults reproduce the historical Keycloak layout EXACTLY, so an
 * unconfigured deployment is byte-for-byte the old behaviour:
 *
 * <ul>
 *   <li>{@code rolesClaimPath} = {@value JwtClaimPaths#DEFAULT_ROLES_CLAIM_PATH}
 *       - a dot-path walked through nested claims (see {@link JwtClaimPaths} for
 *       the literal-first resolution rule that also covers flat names like
 *       Entra's {@code roles} / Okta's {@code groups} and Auth0 namespaced
 *       claims like {@code https://example.com/roles}). Values found there must
 *       be a JSON array of role names; anything else maps to no roles.
 *   <li>{@code tenantClaim} = {@value JwtClaimPaths#DEFAULT_TENANT_CLAIM} - the
 *       claim carrying the tenant/schema id. Read by {@code
 *       TenantContextFilter}, {@link GranularPermissionConverter}, {@code
 *       AuditingAccessDeniedHandler}, {@code ViewAsFilter}'s synthetic target
 *       token, and (in core/tenancy) {@code TenantSchemaResolver} and {@code
 *       OnboardingController} - all from the same property, so a deployment
 *       remaps it in exactly one place.
 *   <li>{@code scopesClaim} = empty (OFF) - when set to an OAuth2
 *       scope/permission claim ({@code scope}, {@code scp}, or a list-valued
 *       claim like Auth0's {@code permissions}), each value is emitted as a
 *       {@code SCOPE_<value>} authority alongside the roles and stored-grant
 *       {@code PERM_*} ones. Off by default because the pre-generic-OIDC
 *       resource server emitted no scope authorities at all.
 * </ul>
 *
 * <p>A null or blank {@code rolesClaimPath}/{@code tenantClaim} falls back to
 * the Keycloak default rather than disabling the mapping - "no roles" and "no
 * tenant" are the fail-closed outcomes a misconfigured PATH already produces,
 * so an empty property degrading to the default can never silently widen
 * access, only keep a deployment that typo'd it working exactly as before.
 */
public record OidcClaimMapping(String rolesClaimPath, String tenantClaim, String scopesClaim) {

    public OidcClaimMapping {
        if (rolesClaimPath == null || rolesClaimPath.isBlank()) {
            rolesClaimPath = JwtClaimPaths.DEFAULT_ROLES_CLAIM_PATH;
        }
        if (tenantClaim == null || tenantClaim.isBlank()) {
            tenantClaim = JwtClaimPaths.DEFAULT_TENANT_CLAIM;
        }
        if (scopesClaim == null) {
            scopesClaim = "";
        }
    }

    /** The shipped mapping - byte-for-byte the pre-generic-OIDC Keycloak behaviour. */
    public static OidcClaimMapping keycloakDefaults() {
        return new OidcClaimMapping(
                JwtClaimPaths.DEFAULT_ROLES_CLAIM_PATH, JwtClaimPaths.DEFAULT_TENANT_CLAIM, "");
    }
}
