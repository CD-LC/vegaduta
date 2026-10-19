package com.agenticai.core.mcpserver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates and hashes this module's inbound MCP API keys. Deliberately
 * hash-only, never reversible - closer to {@code core.workflow}'s
 * {@code WebhookTokenHasher} (a same-package-private SHA-256 utility, itself
 * used for {@code WorkflowDefinition.webhookTokenHash}) than to
 * {@code McpServerRegistration}'s AES-256-GCM {@code encryptedEnv}.
 *
 * <p><b>Deliberate deviation, called out explicitly:</b> the task framing for
 * this build pointed at {@code McpServerRegistration}'s AES-256-GCM-at-rest
 * pattern (via {@code core.crypto.EncryptionService}) as the BYOK-credential
 * precedent to mirror. That pattern is correct for a secret this platform
 * must later DECRYPT to use (an outbound MCP server's env vars) or
 * re-DISPLAY to the tenant on demand (a workflow's webhook token, per
 * {@code WorkflowDefinition}'s own javadoc explaining exactly why it went
 * from hash-only to hash+encrypt). An inbound API key is neither: this
 * platform never needs the plaintext back, only "does the presented key
 * match," so a one-way digest is both simpler and strictly harder to turn
 * into a plaintext-key leak than a reversible ciphertext would be - and it
 * is already an established pattern in this exact schema (the V102
 * {@code workflow_webhook_token_hash} migration, immediately before this
 * module's own V104). {@code core.crypto} is intentionally NOT a dependency
 * of this module for that reason.
 */
final class McpApiKeyHasher {

    private static final String KEY_PREFIX = "vmcp_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private McpApiKeyHasher() {
        // utility class
    }

    /** A fresh, high-entropy (32 random bytes, base64url) raw key - shown to the caller exactly once. */
    static String generateRawKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** First 8 chars of the raw key (after the prefix) - safe to store/display for "which key is this" identification, never enough to reconstruct or brute-force the real key. */
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
            // SHA-256 is a JDK-mandatory algorithm - unreachable in practice,
            // same reasoning WebhookTokenHasher's identical catch relies on.
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
        }
    }
}
