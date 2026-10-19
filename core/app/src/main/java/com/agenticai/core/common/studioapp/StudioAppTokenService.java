package com.agenticai.core.common.studioapp;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Signs and verifies the end-user session tokens for published apps -
 * HS256 JWTs, the same shape {@code LiveKitTokenService} already uses
 * (nimbus-jose-jwt is on the classpath via spring-security-oauth2-jose).
 *
 * <p>The signing key is the APP's own {@code token_secret} (generated at
 * publish), not any platform-wide secret: a token therefore proves "this
 * person signed in to THIS app" and literally cannot verify against any
 * other app or any other tenant. Unpublish deletes the row (and the secret
 * with it), killing every issued token; {@code authMode}/{@code DISABLED}
 * status and per-user {@code ACTIVE} checks happen on the row, not the JWT,
 * so revocation is immediate even though the token itself is stateless.
 */
@Service
public class StudioAppTokenService {

    static final String ISSUER = "vegaduta-studio-app";
    static final long TTL_SECONDS = 30L * 24 * 3600; // 30 days - a phone app session, not a console session

    public record AppUserPrincipal(UUID appId, UUID userId, String email, String displayName) {
    }

    public String issue(StudioApp app, StudioAppUser user) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(user.getId().toString())
                .claim("app", app.getId().toString())
                .claim("email", user.getEmail())
                .claim("name", user.getDisplayName())
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(TTL_SECONDS)))
                .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(app.getTokenSecret().getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Could not issue app user token", e);
        }
    }

    /** Signature + expiry + app binding all verified here; the caller then re-checks the USER row is still ACTIVE. */
    public Optional<AppUserPrincipal> verify(StudioApp app, String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token.trim());
            if (!jwt.verify(new MACVerifier(app.getTokenSecret().getBytes(StandardCharsets.UTF_8)))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!ISSUER.equals(claims.getIssuer())) {
                return Optional.empty();
            }
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.before(new Date())) {
                return Optional.empty();
            }
            String appClaim = claims.getStringClaim("app");
            if (appClaim == null || !app.getId().toString().equals(appClaim)) {
                return Optional.empty();
            }
            return Optional.of(new AppUserPrincipal(
                app.getId(),
                UUID.fromString(claims.getSubject()),
                claims.getStringClaim("email"),
                claims.getStringClaim("name")
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
