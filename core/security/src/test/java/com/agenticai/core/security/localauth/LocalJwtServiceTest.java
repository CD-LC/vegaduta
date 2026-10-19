package com.agenticai.core.security.localauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.JwtClaimPaths;
import com.agenticai.core.security.KeycloakRoleConverter;
import com.agenticai.core.security.localauth.LocalJwtService.AccessTokenRequest;
import com.agenticai.core.security.localauth.LocalSigningKeyStoreTest.MutableClock;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class LocalJwtServiceTest {

    @TempDir Path dir;

    private LocalAuthSettings settings;
    private LocalSigningKeyStore keys;
    private LocalJwtService service;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        settings = new LocalAuthSettings(null, null, dir, null, null, null, null, null, null, null, null);
        keys = new LocalSigningKeyStore(dir, settings.keyRotationInterval(), settings.keyRetention(), Clock.systemUTC());
        service = new LocalJwtService(settings, keys, Clock.systemUTC());
        decoder = service.decoder();
    }

    private static AccessTokenRequest request() {
        return new AccessTokenRequest(
                "11111111-1111-1111-1111-111111111111",
                "owner@example.test",
                "Ada",
                "Lovelace",
                "t_abc123",
                Set.of("tenant-admin"),
                "session-1",
                Instant.parse("2026-01-01T10:00:00Z"),
                "pwd");
    }

    @Test
    void anIssuedTokenValidatesAndCarriesTheClaimShapeTheRestOfTheAppReads() {
        Jwt jwt = decoder.decode(service.issue(request()).token());

        assertThat(jwt.getSubject()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(jwt.getIssuer()).hasToString("https://auth.local.vegaduta.invalid");
        assertThat(jwt.getAudience()).containsExactly("vegaduta-api");
        // Same readers the Keycloak flow uses, unchanged:
        assertThat(JwtClaimPaths.valueAsString(jwt, "tenant_id")).isEqualTo("t_abc123");
        assertThat(new KeycloakRoleConverter().convert(jwt)).extracting(Object::toString).containsExactly("ROLE_tenant-admin");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("owner@example.test");
        assertThat(jwt.getClaimAsString("given_name")).isEqualTo("Ada");
        assertThat(jwt.getClaimAsString("name")).isEqualTo("Ada Lovelace");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo("session-1");
        assertThat(jwt.getClaim("auth_time").toString()).isEqualTo(String.valueOf(Instant.parse("2026-01-01T10:00:00Z").getEpochSecond()));
        assertThat(jwt.getClaimAsStringList("amr")).containsExactly("pwd");
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256");
    }

    @Test
    void theConfiguredClaimNamesAreHonouredSoOtherMappingsKeepWorking() {
        LocalAuthSettings custom =
                new LocalAuthSettings(null, null, dir, null, null, null, null, null, null, "roles", "org");
        LocalJwtService svc = new LocalJwtService(custom, keys, Clock.systemUTC());
        Jwt jwt = svc.decoder().decode(svc.issue(request()).token());

        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("tenant-admin");
        assertThat(jwt.getClaimAsString("org")).isEqualTo("t_abc123");
        assertThat(jwt.getClaims()).doesNotContainKey("realm_access");
    }

    @Test
    void expiryIsEnforced() {
        MutableClock past = new MutableClock(Instant.now().minus(Duration.ofHours(2)));
        LocalJwtService old = new LocalJwtService(settings, keys, past);

        assertThatThrownBy(() -> decoder.decode(old.issue(request()).token()))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void aTokenIsNotValidBeforeItsNotBeforeTime() {
        MutableClock future = new MutableClock(Instant.now().plus(Duration.ofHours(2)));
        LocalJwtService early = new LocalJwtService(settings, keys, future);

        assertThatThrownBy(() -> decoder.decode(early.issue(request()).token())).isInstanceOf(JwtException.class);
    }

    @Test
    void aTamperedPayloadIsRejected() {
        String token = service.issue(request()).token();
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        String forged = payload.replace("tenant-admin", "platform-admin");
        String tampered =
                parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(forged.getBytes()) + "." + parts[2];

        assertThat(forged).isNotEqualTo(payload);
        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTamperedSignatureIsRejected() {
        String token = service.issue(request()).token();
        String flipped = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");

        assertThatThrownBy(() -> decoder.decode(flipped)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenSignedByAForeignKeyIsRejectedEvenWithOurKeyId() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair foreign = gen.generateKeyPair();
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keys.currentKid()).build(), claims());
        jwt.sign(new RSASSASigner((RSAPrivateKey) foreign.getPrivate()));

        assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void algorithmNoneAndHmacConfusionAreRejected() throws Exception {
        String none =
                b64("{\"alg\":\"none\",\"kid\":\"" + keys.currentKid() + "\"}")
                        + "."
                        + b64(claims().toString())
                        + ".";
        assertThatThrownBy(() -> decoder.decode(none)).isInstanceOf(JwtException.class);

        SignedJWT hmac =
                new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keys.currentKid()).build(), claims());
        // The classic attack: sign with HMAC using the public key bytes as the secret.
        byte[] publicKeyBytes = keys.publicJwkSet().getKeys().get(0).toRSAKey().toRSAPublicKey().getEncoded();
        hmac.sign(new MACSigner(java.util.Arrays.copyOf(publicKeyBytes, 64)));
        assertThatThrownBy(() -> decoder.decode(hmac.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void aWrongIssuerOrAudienceIsRejected() throws Exception {
        assertThatThrownBy(() -> decoder.decode(signedWith("someone-else", "vegaduta-api", "access")))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(signedWith("https://auth.local.vegaduta.invalid", "another-api", "access")))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(signedWith("https://auth.local.vegaduta.invalid", "vegaduta-api", "refresh")))
                .as("only access tokens are accepted")
                .isInstanceOf(JwtException.class);
        assertThat(decoder.decode(signedWith("https://auth.local.vegaduta.invalid", "vegaduta-api", "access"))).isNotNull();
    }

    @Test
    void rotationKeepsEarlierTokensValidUntilTheRetiredKeyIsDropped() {
        MutableClock clock = new MutableClock(Instant.now());
        LocalSigningKeyStore rotating =
                new LocalSigningKeyStore(dir.resolve("rot"), Duration.ofDays(90), Duration.ofDays(1), clock);
        LocalJwtService svc = new LocalJwtService(settings, rotating, clock);
        JwtDecoder dec = svc.decoder();
        String before = svc.issue(request()).token();

        rotating.rotate();
        String after = svc.issue(request()).token();

        assertThat(dec.decode(before)).isNotNull();
        assertThat(dec.decode(after)).isNotNull();

        clock.advance(Duration.ofDays(2));
        assertThatThrownBy(() -> dec.decode(before)).as("retired key is gone").isInstanceOf(JwtException.class);
        assertThat(dec.decode(after)).isNotNull();
    }

    @Test
    void settingsFloorTheRetentionAboveTheAccessLifetime() {
        LocalAuthSettings s =
                new LocalAuthSettings(null, null, dir, Duration.ofHours(6), null, null, null, Duration.ofMinutes(1), null, null, null);
        assertThat(s.keyRetention()).isGreaterThanOrEqualTo(Duration.ofHours(12));
        assertThat(s.refreshTokenTtl()).isGreaterThan(s.accessTokenTtl());
        assertThat(s.sessionMaxTtl()).isGreaterThanOrEqualTo(s.refreshTokenTtl());
    }

    // ------------------------------------------------------------------ helpers

    private JWTClaimsSet claims() {
        return claims("https://auth.local.vegaduta.invalid", "vegaduta-api", "access");
    }

    private JWTClaimsSet claims(String issuer, String audience, String use) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject("u")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .claim("token_use", use)
                .claim("realm_access", java.util.Map.of("roles", List.of("platform-admin")))
                .build();
    }

    private String signedWith(String issuer, String audience, String use) throws Exception {
        LocalSigningKeyStore.SigningKey key = keys.signingKey();
        SignedJWT jwt =
                new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.kid()).build(), claims(issuer, audience, use));
        jwt.sign(new RSASSASigner(key.privateKey()));
        return jwt.serialize();
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes());
    }
}
