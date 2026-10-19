package com.agenticai.core.agents.share;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SEC-005: server-issued, HMAC-signed visitor sessions for the PUBLIC agent share
 * surface ({@code /api/share/agents/{shareId}/**}).
 *
 * <p><b>What this replaces.</b> A share visitor's conversation used to be keyed by
 * {@code "share:" + shareId + ":" + sha256(ip)[:12]} (or by a caller-supplied {@code
 * sessionKey} of that shape), and every visitor of a link shared ONE long-term-memory
 * subject, {@code "agent-share:" + shareId}. So everyone behind one NAT/corporate
 * egress IP shared a transcript, anyone who knew (or guessed) a visitor's IP could
 * resume and read theirs back, and memory extraction carried one visitor's PII into
 * every other visitor's answers.
 *
 * <p><b>Now.</b> A visitor is a random 128-bit id from {@link SecureRandom}, handed
 * to the client as an opaque token {@code sv1.<visitorId>.<mac>} where {@code mac =
 * HMAC-SHA256(key, "agent-share-visitor|" + shareId + "|" + visitorId)}. The client
 * echoes the token back (request body field or {@code X-Share-Session} header - the
 * web app calls the API cross-origin without credentials, so a cookie would never be
 * sent back; see the report). A token that is missing, malformed, minted for another
 * share, or fails the MAC is IGNORED and a brand-new visitor is issued - never an
 * error, never an IP fallback. The token is a bearer capability for exactly one
 * anonymous visitor's conversation on exactly one share, nothing more.
 *
 * <p><b>Key.</b> {@code app.agent.share.visitor-token-secret} when set; otherwise
 * derived (HMAC-SHA256, fixed domain-separation label) from {@code app.encryption.key}
 * - the deployment secret that already must be identical on every core replica - so
 * tokens verify across replicas and restarts with no new configuration. With neither
 * set, a random per-boot key is used and a WARN is logged: tokens then stop verifying
 * on restart or on another replica (visitors silently get a fresh conversation), which
 * degrades continuity but never isolation.
 */
@Component
public class ShareVisitorSessions {

    private static final Logger log = LoggerFactory.getLogger(ShareVisitorSessions.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TOKEN_VERSION = "sv1";
    private static final int VISITOR_ID_BYTES = 16; // 128 bits
    private static final Pattern VISITOR_ID_SHAPE = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final String KEY_DERIVATION_LABEL = "vegaduta/agent-share-visitor-token/v1";

    /** Header a client may use instead of the body field to present its visitor token. */
    public static final String HEADER = "X-Share-Session";

    private final byte[] key;

    /** A resolved visitor: the stable id, the token to hand back, and whether it was freshly issued. */
    public record Visitor(String visitorId, String token, boolean issued) {
    }

    public ShareVisitorSessions(
        @Value("${app.agent.share.visitor-token-secret:}") String dedicatedSecret,
        @Value("${app.encryption.key:}") String encryptionKey
    ) {
        if (dedicatedSecret != null && !dedicatedSecret.isBlank()) {
            this.key = hmac(dedicatedSecret.getBytes(StandardCharsets.UTF_8), KEY_DERIVATION_LABEL);
        } else if (encryptionKey != null && !encryptionKey.isBlank()) {
            this.key = hmac(encryptionKey.getBytes(StandardCharsets.UTF_8), KEY_DERIVATION_LABEL);
        } else {
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            this.key = random;
            log.warn("Neither app.agent.share.visitor-token-secret nor app.encryption.key is set - agent-share visitor "
                + "tokens use a random per-boot key and will not survive a restart or verify on another replica");
        }
    }

    /**
     * The visitor behind {@code presentedToken} for {@code shareId} when it verifies,
     * otherwise a newly issued visitor. Never throws, never derives from the caller's IP.
     */
    public Visitor resolveOrIssue(String shareId, String presentedToken) {
        return verify(shareId, presentedToken)
            .map(visitorId -> new Visitor(visitorId, presentedToken.trim(), false))
            .orElseGet(() -> issue(shareId));
    }

    /** Issues a new random visitor for {@code shareId}. */
    public Visitor issue(String shareId) {
        byte[] bytes = new byte[VISITOR_ID_BYTES];
        RANDOM.nextBytes(bytes);
        String visitorId = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Visitor(visitorId, TOKEN_VERSION + "." + visitorId + "." + mac(shareId, visitorId), true);
    }

    /** The visitor id inside {@code token} when it is well-formed and signed by this server FOR {@code shareId}. */
    public Optional<String> verify(String shareId, String token) {
        if (shareId == null || token == null || token.isBlank() || token.length() > 128) {
            return Optional.empty();
        }
        String[] parts = token.trim().split("\\.", -1);
        if (parts.length != 3 || !TOKEN_VERSION.equals(parts[0]) || !VISITOR_ID_SHAPE.matcher(parts[1]).matches()) {
            return Optional.empty();
        }
        byte[] expected = mac(shareId, parts[1]).getBytes(StandardCharsets.US_ASCII);
        byte[] presented = parts[2].getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, presented) ? Optional.of(parts[1]) : Optional.empty();
    }

    /** The per-visitor chat session id - non-secret (it is never the token), safe for logs/events. */
    public static String chatSessionId(String shareId, String visitorId) {
        return "share:" + shareId + ":v:" + visitorId;
    }

    /**
     * The per-visitor long-term-memory subject. Replaces the old per-SHARE
     * {@code "agent-share:" + shareRowId}, which pooled every visitor's extracted
     * facts into one memory every other visitor could query.
     */
    public static String memorySubject(Object shareRowId, String visitorId) {
        return "agent-share:" + shareRowId + ":" + visitorId;
    }

    private String mac(String shareId, String visitorId) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(hmac(key, "agent-share-visitor|" + shareId + "|" + visitorId));
    }

    private static byte[] hmac(byte[] keyBytes, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
