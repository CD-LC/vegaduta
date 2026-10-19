package com.agenticai.core.security.localauth;

import com.agenticai.core.security.JwtClaimPaths;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Issues and validates the access JWTs of local auth mode.
 *
 * <p><b>The token has the same shape a Keycloak access token has for this API</b>, which is the
 * whole point: roles are written under the configured roles claim path (default {@code
 * realm_access.roles}) and the tenant under the configured tenant claim (default {@code
 * tenant_id}) through {@link JwtClaimPaths#putNested}, the same writer the view-as filter uses, so
 * {@code KeycloakRoleConverter}, {@code GranularPermissionConverter} and {@code
 * TenantContextFilter} read it without knowing it is not from Keycloak. Identity claims ({@code
 * email}, {@code preferred_username}, {@code given_name}, {@code family_name}, {@code name}),
 * {@code sid} (the login session) and {@code auth_time} (when the password was last typed, which
 * the recent-authentication gate reads and a refresh must not move) are carried the same way.
 *
 * <p><b>Validation</b> pins RS256 and the issuer and audience, checks {@code exp}/{@code nbf} with
 * the Spring default clock skew, and selects the verification key by {@code kid} from the live key
 * set, so a rotation takes effect without a restart. A token signed with any other algorithm
 * (including {@code none} and HMAC with the public key as secret) never reaches a key.
 */
public final class LocalJwtService {

    public static final String TOKEN_USE_CLAIM = "token_use";
    public static final String TOKEN_USE_ACCESS = "access";

    /** Everything that goes into one access token. */
    public record AccessTokenRequest(
            String subject,
            String email,
            String firstName,
            String lastName,
            String tenantId,
            Collection<String> roles,
            String sessionId,
            Instant authTime,
            String authMethod) {}

    /** A signed access token and when it stops being valid. */
    public record IssuedAccessToken(String token, Instant expiresAt) {}

    private final LocalAuthSettings settings;
    private final LocalSigningKeyStore keys;
    private final Clock clock;

    public LocalJwtService(LocalAuthSettings settings, LocalSigningKeyStore keys, Clock clock) {
        this.settings = settings;
        this.keys = keys;
        this.clock = clock;
    }

    public IssuedAccessToken issue(AccessTokenRequest request) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(settings.accessTokenTtl());

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("token_use", TOKEN_USE_ACCESS);
        claims.put("azp", settings.audience());
        if (request.email() != null) {
            claims.put("email", request.email());
            claims.put("preferred_username", request.email());
            claims.put("email_verified", Boolean.TRUE);
        }
        if (request.firstName() != null) {
            claims.put("given_name", request.firstName());
        }
        if (request.lastName() != null) {
            claims.put("family_name", request.lastName());
        }
        String fullName = join(request.firstName(), request.lastName());
        if (!fullName.isEmpty()) {
            claims.put("name", fullName);
        }
        if (request.sessionId() != null) {
            claims.put("sid", request.sessionId());
        }
        if (request.authTime() != null) {
            claims.put("auth_time", request.authTime().getEpochSecond());
        }
        if (request.authMethod() != null) {
            claims.put("amr", List.of(request.authMethod()));
        }
        if (request.tenantId() != null && !request.tenantId().isBlank()) {
            JwtClaimPaths.putNested(claims, settings.tenantClaim(), request.tenantId());
        }
        JwtClaimPaths.putNested(
                claims,
                settings.rolesClaimPath(),
                request.roles() == null ? List.of() : List.copyOf(request.roles()));

        JWTClaimsSet.Builder builder =
                new JWTClaimsSet.Builder()
                        .issuer(settings.issuer())
                        .audience(settings.audience())
                        .subject(request.subject())
                        .issueTime(Date.from(now))
                        .notBeforeTime(Date.from(now))
                        .expirationTime(Date.from(expiresAt))
                        .jwtID(UUID.randomUUID().toString());
        claims.forEach(builder::claim);

        LocalSigningKeyStore.SigningKey key = keys.signingKey();
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256)
                                .keyID(key.kid())
                                .type(JOSEObjectType.JWT)
                                .build(),
                        builder.build());
        try {
            jwt.sign(new RSASSASigner(key.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the local access token", e);
        }
        return new IssuedAccessToken(jwt.serialize(), expiresAt);
    }

    /**
     * A {@link JwtDecoder} that verifies this service's tokens. Built once; it consults the live key
     * set on every token.
     */
    public JwtDecoder decoder() {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(
                        JWSAlgorithm.RS256, (selector, context) -> selector.select(keys.publicJwkSet())));
        // Spring's decoder does the time/issuer checks below; the Nimbus claims verifier would
        // duplicate them with different error shapes.
        processor.setJWTClaimsSetVerifier((claims, context) -> {});
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(settings.issuer()),
                        audienceAndUseValidator(settings.audience())));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> audienceAndUseValidator(String audience) {
        return jwt -> {
            List<String> aud = jwt.getAudience();
            if (aud == null || !aud.contains(audience)) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "The token audience is not accepted", null));
            }
            if (!TOKEN_USE_ACCESS.equals(jwt.getClaimAsString(TOKEN_USE_CLAIM))) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "The token is not an access token", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    private static String join(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        return (f + " " + l).trim();
    }
}
