package com.agenticai.core.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * #815 Governance follow-up (roadmap §10.3 item 1) - shared SHA-256 hex digest used as
 * {@code WorkflowDefinition.webhookTokenHash}'s lookup key. Deliberately a small, dependency-free
 * utility (not routed through {@code EncryptionService}, which is for reversible AES-GCM
 * encryption, a different primitive) so both {@link WorkflowService} (computing the hash to store
 * when a token is minted/reused) and {@link WorkflowWebhookController} (hashing an inbound
 * caller's raw token to look it up) hash identically without duplicating the algorithm choice in
 * two places.
 */
final class WebhookTokenHasher {

    private WebhookTokenHasher() {
        // utility class
    }

    static String sha256Hex(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-mandatory algorithm (every conforming JVM implementation must
            // support it, per MessageDigest's own javadoc) - this is unreachable in practice, not
            // a real failure mode to design around, the same reasoning RazorpayGatewayClient's
            // HmacSHA256 usage elsewhere in this codebase relies on implicitly.
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
        }
    }
}
