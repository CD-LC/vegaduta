package com.agenticai.core.security.localauth;

import com.agenticai.core.security.JwtClaimPaths;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.core.env.Environment;

/**
 * Every tunable of the local single-user auth mode ({@code app.auth.mode=local}), read once from the
 * {@link Environment} so the key store, the token service, the throttle and the account service
 * cannot disagree about a value. All defaults are safe for a laptop install; nothing here is read at
 * all unless the mode is {@code local} (see {@link LocalAuth#isLocal(Environment)}).
 *
 * <p>The roles and tenant claim names are NOT new settings: they are the existing
 * {@code app.security.oidc.roles-claim-path} / {@code app.security.oidc.tenant-claim} mapping, so a
 * locally issued token has exactly the claim shape the Keycloak flow produces and every consumer
 * ({@code KeycloakRoleConverter}, {@code TenantContextFilter}, ...) reads it unchanged.
 *
 * @param issuer issuer identifier stamped into {@code iss} and required on validation. URL-shaped
 *     so {@code Jwt#getIssuer()} works for every library that calls it, but under the reserved
 *     {@code .invalid} TLD: nobody ever dereferences it, and it is never a localhost address
 *     (AGENTS.md rule 4).
 * @param audience required {@code aud} value.
 * @param dataDir directory holding the signing keys and the first-run setup file; created 0700.
 * @param accessTokenTtl lifetime of an access JWT. Short on purpose: access tokens are not
 *     individually revocable, so this bounds how long a disabled or logged-out session keeps working.
 * @param refreshTokenTtl idle lifetime of one refresh token (each use rotates it).
 * @param sessionMaxTtl absolute lifetime of a login session however often it is refreshed.
 * @param keyRotationInterval age after which the signing key is replaced.
 * @param keyRetention how long a retired key still verifies tokens (floored above the access TTL).
 * @param setupTokenTtl lifetime of the one-time first-run / invite setup token.
 */
public record LocalAuthSettings(
        String issuer,
        String audience,
        Path dataDir,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration sessionMaxTtl,
        Duration keyRotationInterval,
        Duration keyRetention,
        Duration setupTokenTtl,
        String rolesClaimPath,
        String tenantClaim) {

    public static final String DEFAULT_ISSUER = "https://auth.local.vegaduta.invalid";
    public static final String DEFAULT_AUDIENCE = "vegaduta-api";

    /** File in {@link #dataDir()} holding the one-time first-run setup link (mode 0600). */
    public static final String SETUP_FILE_NAME = "first-run-setup.txt";

    public LocalAuthSettings {
        if (issuer == null || issuer.isBlank()) {
            issuer = DEFAULT_ISSUER;
        }
        if (audience == null || audience.isBlank()) {
            audience = DEFAULT_AUDIENCE;
        }
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null || refreshTokenTtl.compareTo(accessTokenTtl) <= 0) {
            refreshTokenTtl = Duration.ofDays(30);
        }
        if (sessionMaxTtl == null || sessionMaxTtl.compareTo(refreshTokenTtl) < 0) {
            sessionMaxTtl = Duration.ofDays(90);
        }
        if (keyRotationInterval == null
                || keyRotationInterval.isNegative()
                || keyRotationInterval.isZero()) {
            keyRotationInterval = Duration.ofDays(90);
        }
        // A retired key must outlive the longest-lived token it ever signed, or rotation would log
        // everyone out mid-token. Floor it at twice the access TTL, and at one day.
        Duration floor = accessTokenTtl.multipliedBy(2);
        if (floor.compareTo(Duration.ofDays(1)) < 0) {
            floor = Duration.ofDays(1);
        }
        if (keyRetention == null || keyRetention.compareTo(floor) < 0) {
            keyRetention = floor;
        }
        if (setupTokenTtl == null || setupTokenTtl.isNegative() || setupTokenTtl.isZero()) {
            setupTokenTtl = Duration.ofHours(24);
        }
        if (rolesClaimPath == null || rolesClaimPath.isBlank()) {
            rolesClaimPath = JwtClaimPaths.DEFAULT_ROLES_CLAIM_PATH;
        }
        if (tenantClaim == null || tenantClaim.isBlank()) {
            tenantClaim = JwtClaimPaths.DEFAULT_TENANT_CLAIM;
        }
    }

    /** Reads {@code app.auth.local.*} and the shared OIDC claim mapping from {@code env}. */
    public static LocalAuthSettings from(Environment env) {
        String dir = env.getProperty("app.auth.local.data-dir", "");
        Path dataDir =
                dir.isBlank()
                        ? Path.of(System.getProperty("user.home", "."), ".vegaduta", "auth")
                        : Path.of(dir.trim());
        return new LocalAuthSettings(
                env.getProperty("app.auth.local.issuer", DEFAULT_ISSUER),
                env.getProperty("app.auth.local.audience", DEFAULT_AUDIENCE),
                dataDir,
                duration(env, "app.auth.local.access-token-ttl"),
                duration(env, "app.auth.local.refresh-token-ttl"),
                duration(env, "app.auth.local.session-max-ttl"),
                duration(env, "app.auth.local.key-rotation-interval"),
                duration(env, "app.auth.local.key-retention"),
                duration(env, "app.auth.local.setup-token-ttl"),
                env.getProperty(
                        "app.security.oidc.roles-claim-path", JwtClaimPaths.DEFAULT_ROLES_CLAIM_PATH),
                env.getProperty("app.security.oidc.tenant-claim", JwtClaimPaths.DEFAULT_TENANT_CLAIM));
    }

    private static Duration duration(Environment env, String key) {
        String raw = env.getProperty(key, "");
        if (raw.isBlank()) {
            return null;
        }
        try {
            return env.getProperty(key, Duration.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    key + " is not a valid duration (use ISO-8601 like PT15M or 15m): '" + raw + "'", e);
        }
    }
}
