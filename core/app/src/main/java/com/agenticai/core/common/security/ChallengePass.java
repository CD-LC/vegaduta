package com.agenticai.core.common.security;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The challenge-pass token a client earns by solving the fraud challenge: a stateless HMAC-SHA256
 * token bound to the tenant, the client IP the challenge was solved from and the challenge nonce,
 * with a short expiry, and single-use ({@link #redeem} consumes the nonce). Same construction as
 * {@code WorkflowWaitResumeTokens}: the key is derived from the platform encryption key (identical
 * on every core replica by deployment rule) with a domain separator so a token for another purpose
 * never verifies here. Unconfigured (no key) means no pass can be issued and none verifies.
 *
 * <p>Single use is tracked in memory per replica until the token expires; a replay against a
 * different replica inside the lifetime is the residual risk, bounded by the short lifetime and the
 * IP binding.
 */
final class ChallengePass {
    static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final String VERSION = "v1";
    private static final int MAX_TOKEN_LENGTH = 600;

    private final byte[] key;
    private final Clock clock;
    private final Duration ttl;

    /** Redeemed nonce -> expiry epoch seconds. */
    private final Map<String, Long> redeemed = new ConcurrentHashMap<>();

    ChallengePass(String encryptionKey, Clock clock, Duration ttl) {
        this.key =
                encryptionKey == null || encryptionKey.isBlank()
                        ? null
                        : ("fraud-challenge-pass:" + encryptionKey)
                                .getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        this.ttl = ttl;
    }

    Duration ttl() {
        return ttl;
    }

    /** A signed pass, or null when unconfigured or an input is missing. */
    String issue(UUID tenantId, InetAddress ip, String nonceId) {
        if (key == null
                || tenantId == null
                || ip == null
                || nonceId == null
                || nonceId.isBlank()
                || nonceId.indexOf('\n') >= 0) {
            return null;
        }
        long exp = clock.instant().plus(ttl).getEpochSecond();
        String payload =
                b64(
                        VERSION
                                + "\n"
                                + tenantId
                                + "\n"
                                + ip.getHostAddress()
                                + "\n"
                                + nonceId
                                + "\n"
                                + exp);
        return payload + "." + b64(hmac(payload));
    }

    /** True exactly once for a genuine, unexpired pass for this tenant and client IP. */
    boolean redeem(String token, UUID tenantId, InetAddress ip) {
        if (key == null
                || token == null
                || tenantId == null
                || ip == null
                || token.length() > MAX_TOKEN_LENGTH) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return false;
        }
        String payload = token.substring(0, dot);
        String[] parts;
        try {
            byte[] presented = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(presented, hmac(payload))) {
                return false;
            }
            parts =
                    new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
                            .split("\n", -1);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (parts.length != 5 || !VERSION.equals(parts[0])) {
            return false;
        }
        long exp;
        try {
            exp = Long.parseLong(parts[4]);
        } catch (NumberFormatException e) {
            return false;
        }
        long now = clock.instant().getEpochSecond();
        if (now >= exp) {
            return false;
        }
        // Constant-time comparison of the bound claims (non-short-circuit &).
        boolean bound =
                MessageDigest.isEqual(bytes(parts[1]), bytes(tenantId.toString()))
                        & MessageDigest.isEqual(bytes(parts[2]), bytes(ip.getHostAddress()));
        if (!bound || parts[3].isBlank()) {
            return false;
        }
        redeemed.values().removeIf(e -> e <= now);
        return redeemed.putIfAbsent(parts[3], exp) == null;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String b64(String s) {
        return b64(bytes(s));
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(bytes(payload));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
