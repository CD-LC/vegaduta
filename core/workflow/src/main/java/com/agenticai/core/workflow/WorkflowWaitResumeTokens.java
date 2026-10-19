package com.agenticai.core.workflow;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The signed resume URL of a webhook-mode Wait node: a stateless HMAC-SHA256 token that carries the tenant, the
 * run and the Wait node, so the PUBLIC resume endpoint can find the tenant without a lookup table and nobody can
 * mint a URL for a run they were not given one for. Same construction as the email-unsubscribe token (HMAC under
 * the platform encryption key, which is identical on every core replica by deployment rule, with a domain
 * separator so a token for one purpose never verifies for another). Single use is enforced by the {@code
 * workflow_wait} row (WAITING -> RESUMED), not by the token, so replaying a URL after the run moved on is a 409.
 *
 * <p>Unconfigured (no key, or no public base URL to build an absolute link from) means {@link #isConfigured()}
 * is false and a webhook-mode Wait refuses to park rather than emit a dead URL.
 */
@Component
public class WorkflowWaitResumeTokens {

    /** Public resume endpoint base path (under {@code /webhook/**}, which SecurityConfig already permits). */
    public static final String PATH = "/webhook/workflow-waits/";

    /**
     * Node-id claim of a run-scoped token: it resumes whichever webhook-mode Wait the run is parked at. This is
     * what {@code $run.waitUrl} carries, because the URL has to exist (and be handed to the party who will call
     * it) BEFORE the run reaches the Wait node. Still single use per Wait row, and still bound to one run.
     */
    public static final String ANY_NODE = "*";

    /** Verified token contents. */
    public record Claims(String tenantId, UUID runId, String nodeId) {
    }

    private final byte[] key;
    private final String baseUrl;

    public WorkflowWaitResumeTokens(
        @Value("${app.encryption.key:}") String encryptionKey,
        @Value("${app.omnichannel.public-base-url:}") String publicBaseUrl
    ) {
        this.key = encryptionKey == null || encryptionKey.isBlank()
            ? null : ("workflow-wait-resume:" + encryptionKey).getBytes(StandardCharsets.UTF_8);
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public boolean isConfigured() {
        return key != null && !baseUrl.isEmpty();
    }

    /** Absolute resume URL, or null when {@link #isConfigured()} is false. */
    public String resumeUrl(String tenantId, UUID runId, String nodeId) {
        if (!isConfigured() || tenantId == null || runId == null || nodeId == null) {
            return null;
        }
        return baseUrl + PATH + createToken(tenantId, runId, nodeId);
    }

    /** Absolute run-scoped resume URL ({@link #ANY_NODE}), or null when {@link #isConfigured()} is false. */
    public String runResumeUrl(String tenantId, UUID runId) {
        return resumeUrl(tenantId, runId, ANY_NODE);
    }

    public String createToken(String tenantId, UUID runId, String nodeId) {
        String payload = b64(tenantId + "\n" + runId + "\n" + nodeId);
        return payload + "." + b64(hmac(payload));
    }

    public Optional<Claims> verify(String token) {
        if (key == null || token == null || token.length() > 600) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        String payload = token.substring(0, dot);
        try {
            byte[] presented = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(presented, hmac(payload))) {
                return Optional.empty();
            }
            String[] parts = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8).split("\n", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[2].isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Claims(parts[0], UUID.fromString(parts[1]), parts[2]));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String b64(String s) {
        return b64(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
