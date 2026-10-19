package com.agenticai.core.mcpserver;

import com.agenticai.core.security.JwtClaimPaths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Resource-server half of the MCP authorization spec for {@code /mcp}: validates an OAuth 2.1
 * access token (a JWT) presented as {@code Authorization: Bearer}.
 *
 * <p>Checks, in order: signature against the configured authorization server's keys; issuer is
 * exactly the configured one; token not expired; <b>audience contains this server's canonical
 * resource URI</b> (RFC 8707 / MCP "token audience binding" - a token minted for any other
 * resource is rejected, which is what makes token passthrough from another server useless here);
 * the token carries the tenant claim and the grant claim linking it to an existing
 * {@link McpServerAccessGrant}. The grant keeps the per-caller confinement (which agents,
 * workflows and collections) identical to a {@code vmcp_} key; the token's scopes only narrow what
 * the caller may do, see {@link McpOAuthScopes}.
 *
 * <p>This class never forwards or stores the token: the filter strips {@code Authorization}
 * before the request reaches any downstream handler, and tools make their upstream calls with
 * their own credentials.
 */
class McpOAuthAuthenticator {

    /** A token that passed every check. */
    record Principal(String tenantId, UUID grantId, Set<String> scopes) {}

    /** Why a token was refused; {@code status} is 401 for a bad token, 403 for a bad link. */
    record Rejection(int status, String error, String description) {}

    /** Exactly one of the two is non-null. */
    record Result(Principal principal, Rejection rejection) {
        static Result ok(Principal p) {
            return new Result(p, null);
        }

        static Result reject(int status, String error, String description) {
            return new Result(null, new Rejection(status, error, description));
        }
    }

    private final JwtDecoder decoder;
    private final String resource;
    private final String tenantClaim;
    private final String grantClaim;

    McpOAuthAuthenticator(JwtDecoder decoder, String resource, String tenantClaim, String grantClaim) {
        this.decoder = decoder;
        this.resource = canonical(resource);
        this.tenantClaim = tenantClaim;
        this.grantClaim = grantClaim;
    }

    /** Builds the production decoder: JWKS from the authorization server, issuer and audience enforced. */
    static McpOAuthAuthenticator forJwks(
        String jwkSetUri, String issuer, String resource, String tenantClaim, String grantClaim
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), audienceValidator(resource)));
        return new McpOAuthAuthenticator(decoder, resource, tenantClaim, grantClaim);
    }

    static OAuth2TokenValidator<Jwt> audienceValidator(String resource) {
        String expected = canonical(resource);
        return jwt -> {
            List<String> audiences = jwt.getAudience();
            if (audiences != null && audiences.stream().anyMatch(a -> expected.equals(canonical(a)))) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token", "The token was not issued for this MCP server (audience mismatch).", null));
        };
    }

    Result authenticate(String bearer) {
        Jwt jwt;
        try {
            jwt = decoder.decode(bearer);
        } catch (JwtException e) {
            // Deliberately generic: do not tell a prober whether signature, expiry, issuer or audience failed.
            return Result.reject(401, "invalid_token", "The access token is invalid, expired or not issued for this server.");
        }
        // Defence in depth for decoders built without the audience validator (tests, custom wiring).
        if (jwt.getAudience() == null || jwt.getAudience().stream().noneMatch(a -> resource.equals(canonical(a)))) {
            return Result.reject(401, "invalid_token", "The access token is invalid, expired or not issued for this server.");
        }
        String tenant = JwtClaimPaths.valueAsString(jwt, tenantClaim);
        String grant = JwtClaimPaths.valueAsString(jwt, grantClaim);
        if (tenant == null || tenant.isBlank() || grant == null || grant.isBlank()) {
            return Result.reject(403, "insufficient_scope", "The token is not linked to an MCP access grant.");
        }
        UUID grantId;
        try {
            grantId = UUID.fromString(grant);
        } catch (IllegalArgumentException e) {
            return Result.reject(403, "insufficient_scope", "The token is not linked to an MCP access grant.");
        }
        return Result.ok(new Principal(tenant, grantId, scopesOf(jwt)));
    }

    private static Set<String> scopesOf(Jwt jwt) {
        Set<String> scopes = new HashSet<>();
        Object scope = jwt.getClaims().get("scope");
        if (scope instanceof String s) {
            Arrays.stream(s.trim().split("\\s+")).filter(x -> !x.isEmpty()).forEach(scopes::add);
        }
        Object scp = jwt.getClaims().get("scp");
        if (scp instanceof List<?> list) {
            list.forEach(x -> scopes.add(String.valueOf(x)));
        }
        return scopes;
    }

    /** RFC 8707 comparison form: scheme and host lower-cased, no trailing slash, no fragment. */
    static String canonical(String uri) {
        if (uri == null) {
            return "";
        }
        String s = uri.trim();
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        int schemeEnd = s.indexOf("://");
        if (schemeEnd < 0) {
            return s;
        }
        int pathStart = s.indexOf('/', schemeEnd + 3);
        String head = pathStart < 0 ? s : s.substring(0, pathStart);
        String tail = pathStart < 0 ? "" : s.substring(pathStart);
        return head.toLowerCase(java.util.Locale.ROOT) + tail;
    }
}
