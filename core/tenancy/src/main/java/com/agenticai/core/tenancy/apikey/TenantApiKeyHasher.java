package com.agenticai.core.tenancy.apikey;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;

/**
 * Raw-key generation and hashing for tenant API keys - same rationale as
 * {@code McpApiKeyHasher} (which this mirrors deliberately): a one-way
 * digest is all the platform needs to verify a presented key, and hash-only
 * storage cannot be turned into a plaintext-key leak the way an encrypted
 * column can. {@code core.crypto} is intentionally NOT used - there is
 * nothing to decrypt.
 */
final class TenantApiKeyHasher {

    /** Literal prefix on every tenant API key - lets {@code ApiKeyAuthFilter} tell a key from a JWT. */
    static final String KEY_PREFIX = "vdak_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private TenantApiKeyHasher() {
        // utility class
    }

    /** A fresh, high-entropy (32 random bytes, base64url) raw key - shown to the caller exactly once. */
    static String generateRawKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * First 8 chars of the raw key after the prefix - safe to store and show
     * for "which key is this" identification, never enough to reconstruct or
     * brute-force the real key.
     */
    static String displayPrefix(String rawKey) {
        int start = KEY_PREFIX.length();
        int end = Math.min(rawKey.length(), start + 8);
        return rawKey.substring(start, end);
    }

    static String sha256Hex(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
        }
    }
}
