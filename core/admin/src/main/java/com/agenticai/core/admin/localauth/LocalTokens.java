package com.agenticai.core.admin.localauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque bearer secrets of local mode: refresh tokens and one-time setup tokens. Both are 256 bits
 * of {@link SecureRandom} with a type prefix, so a leaked value is recognisable in a log scan and a
 * refresh token can never be mistaken for a setup token. Only the SHA-256 of a token is persisted;
 * with 256 bits of entropy a plain digest (no salt, no work factor) is the right tool, and it keeps
 * the lookup an indexed equality.
 */
public final class LocalTokens {

    public static final String REFRESH_PREFIX = "vdlr_";
    public static final String SETUP_PREFIX = "vdls_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private LocalTokens() {}

    public static String newRefreshToken() {
        return REFRESH_PREFIX + randomPart();
    }

    public static String newSetupToken() {
        return SETUP_PREFIX + randomPart();
    }

    /** Lower-case hex SHA-256 of {@code token}. */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String randomPart() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
