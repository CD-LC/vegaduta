package com.agenticai.core.mcp.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** RFC 7636 PKCE (S256) pair generation, shared by the generic flow and {@code AtlassianOAuthClient}. */
public final class McpOAuthPkce {

    private static final SecureRandom RANDOM = new SecureRandom();

    private McpOAuthPkce() {
    }

    public record Pair(String verifier, String challenge) {
    }

    /** @throws IllegalStateException if SHA-256 is unavailable (never on a standard JRE) */
    public static Pair generate() {
        byte[] bytes = new byte[64];
        RANDOM.nextBytes(bytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Pair(verifier, challengeFor(verifier));
    }

    public static String challengeFor(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
