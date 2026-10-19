package com.agenticai.core.tenancy.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation and hashing for the opaque demo-session bearer token.
 *
 * <p><b>Not a UUID.</b> A random UUID is only 122 bits and, more to the
 * point, several UUID generators in the wild are time-seeded - for a token
 * that is the ONLY thing standing between an anonymous internet caller and
 * somebody else's sandbox, 256 bits straight out of {@link SecureRandom} is
 * the right answer and costs nothing. URL-safe Base64 without padding so it
 * drops cleanly into a header, a query string, or {@code localStorage}.
 *
 * <p>{@link #hash} is plain SHA-256 hex, deliberately NOT a slow password
 * hash (bcrypt/argon2). This value is a high-entropy random secret, not a
 * human-chosen password: there is no dictionary to attack, so key
 * stretching buys nothing, while a slow hash on a per-request lookup path
 * would be a real latency cost. Same reasoning and same construction the
 * workflow webhook token ({@code WebhookTokenHasher}) already uses.
 */
final class DemoTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256 bits

    private DemoTokens() {
    }

    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec - unreachable on any real JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
