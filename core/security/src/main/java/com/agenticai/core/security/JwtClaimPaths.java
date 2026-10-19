package com.agenticai.core.security;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resolves a configured claim path against a {@link Jwt}'s claims, and the inverse
 * (writing a value under a path so a later read with the same path finds it). This
 * is the single lookup every configurable-claim call site goes through - {@link
 * OidcClaimMapping} only carries WHICH paths are configured; how a path maps onto
 * a token's claims lives here so the rules can never drift between the role
 * converter, the tenant resolution points, and {@code ViewAsFilter}'s synthetic
 * target token.
 *
 * <p><b>Read rule ({@link #value}) - literal first, then dot-path.</b> The whole
 * path is tried as a literal top-level claim key before any splitting happens:
 * that is what makes both flat names ({@code roles} - Microsoft Entra, {@code
 * groups} - Okta) and Auth0-style namespaced claims ({@code
 * https://example.com/roles}, a single claim key that itself contains dots)
 * work. Only when no literal key matches is the path walked segment-by-segment
 * on {@code '.'}, which is what turns {@code realm_access.roles} into Keycloak's
 * nested {@code {"realm_access": {"roles": [...]}} shape. Any missing segment,
 * non-map intermediate value, or blank path resolves to {@code null} - callers
 * treat {@code null} as "claim absent" and fail closed.
 *
 * <p><b>Write rule ({@link #putNested}) - only used for the synthetic view-as
 * token.</b> A path containing {@code '/'} or {@code ':'} (a URI-style
 * namespaced claim) is written as one literal top-level claim, matching how the
 * IdP emits it and how {@link #value} finds it. Any other path containing {@code
 * '.'} is expanded into nested maps, so the default {@code realm_access.roles}
 * rebuilds the exact Keycloak shape downstream readers expect. A plain name is
 * written literally.
 */
public final class JwtClaimPaths {

    /** Keycloak's {@code realm_access.roles} - the shipped default role mapping. */
    public static final String DEFAULT_ROLES_CLAIM_PATH = "realm_access.roles";

    /** The {@code tenant_id} user-attribute claim Keycloak's mapper emits - shipped default. */
    public static final String DEFAULT_TENANT_CLAIM = "tenant_id";

    private JwtClaimPaths() {}

    /**
     * The claim value at {@code claimPath}, or {@code null} when the token does not
     * carry it (or carries a non-map intermediate segment). Never throws on a
     * malformed token - a {@code null} return is the fail-closed signal callers
     * key their "no authorities / no tenant" behaviour on.
     */
    public static Object value(Jwt jwt, String claimPath) {
        if (jwt == null || claimPath == null || claimPath.isBlank()) {
            return null;
        }
        Map<String, Object> claims = jwt.getClaims();
        if (claims.containsKey(claimPath)) {
            return claims.get(claimPath);
        }
        Object node = claims;
        for (String segment : claimPath.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = map.get(segment);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /**
     * {@link #value} narrowed to a String. A claim that resolves to a non-string
     * (a list, a map, a number) is treated as absent rather than stringified -
     * for the tenant claim in particular, a malformed value must behave exactly
     * like a missing one (the caller's missing-tenant rejection), never become a
     * schema name.
     */
    public static String valueAsString(Jwt jwt, String claimPath) {
        Object resolved = value(jwt, claimPath);
        return resolved instanceof String string ? string : null;
    }

    /**
     * Writes {@code value} under {@code claimPath} into {@code claims} in whatever
     * shape {@link #value} would read it back: literal for URI-style namespaced
     * paths and plain names, nested maps for a dot-path. When a segment on the
     * way down already exists as a non-map value the value is stored under the
     * literal path instead - it stays reachable for {@link #value}'s literal
     * first pass rather than being silently dropped. A blank path writes nothing.
     */
    @SuppressWarnings("unchecked")
    public static void putNested(Map<String, Object> claims, String claimPath, Object value) {
        if (claims == null || claimPath == null || claimPath.isBlank()) {
            return;
        }
        if (!claimPath.contains(".") || claimPath.contains("/") || claimPath.contains(":")) {
            claims.put(claimPath, value);
            return;
        }
        String[] segments = claimPath.split("\\.");
        Map<String, Object> node = claims;
        for (int i = 0; i < segments.length - 1; i++) {
            Object next =
                    node.computeIfAbsent(segments[i], key -> new LinkedHashMap<String, Object>());
            if (!(next instanceof Map)) {
                claims.put(claimPath, value);
                return;
            }
            node = (Map<String, Object>) next;
        }
        node.put(segments[segments.length - 1], value);
    }
}
