package com.agenticai.core.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpHeaders;

/**
 * Verifies the sender-signature half of {@link WebhookSignatureMode} (V482)
 * inside {@link WorkflowWebhookController}'s shared-secret path. All schemes
 * are HMAC-SHA256 over a scheme-specific payload; the only per-mode knowledge
 * is WHERE the signature arrives and WHAT the signed payload is. Comparison
 * is always constant-time ({@link MessageDigest#isEqual}) - a non-constant
 * compare on a hex digest is a timing oracle for the MAC.
 *
 * <p>All three named schemes use the sender's own documented payload shape,
 * including Stripe/Slack's timestamp INSIDE the signed payload: it is covered
 * by the signature, so checking it against {@link #REPLAY_TOLERANCE} bounds
 * how long a captured delivery stays replayable.
 */
public final class WebhookSignatureVerifier {

    /** Stripe and Slack both publish 5-minute tolerances; the same window applies here. */
    static final long REPLAY_TOLERANCE_SECONDS = 300;

    private WebhookSignatureVerifier() {
    }

    /**
     * True when the delivery carries a signature that verifies under {@code
     * mode} with {@code secret}. {@link WebhookSignatureMode#NONE} always
     * verifies (nothing to check); every other mode fails closed on a missing
     * or malformed header/signature.
     */
    public static boolean verify(WebhookSignatureMode mode, String secret, byte[] rawBody, HttpHeaders headers) {
        if (mode == null || mode == WebhookSignatureMode.NONE) {
            return true;
        }
        if (secret == null || secret.isBlank()) {
            return false;
        }
        // An empty body is still a signed payload (HMAC over zero bytes) -
        // only a missing secret fails closed here.
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        return switch (mode) {
            case GITHUB -> verifyGithub(secret, body, headers);
            case STRIPE -> verifyStripe(secret, body, headers);
            case SLACK -> verifySlack(secret, body, headers);
            case HMAC_SHA256 -> verifyGeneric(secret, body, headers);
            case NONE -> true;
        };
    }

    /**
     * Same as {@link #verify} with a caller-supplied "now" - tests pin the
     * timestamp edge instead of sleeping.
     */
    static boolean verify(WebhookSignatureMode mode, String secret, byte[] rawBody, HttpHeaders headers, Instant now) {
        if (mode == WebhookSignatureMode.STRIPE) {
            return verifyStripe(secret, rawBody, headers, now);
        }
        if (mode == WebhookSignatureMode.SLACK) {
            return verifySlack(secret, rawBody, headers, now);
        }
        return verify(mode, secret, rawBody, headers);
    }

    /** {@code X-Hub-Signature-256: sha256=<hex(HMAC-SHA256(secret, body))>}. */
    private static boolean verifyGithub(String secret, byte[] body, HttpHeaders headers) {
        String header = headers.getFirst("X-Hub-Signature-256");
        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }
        return signatureEquals(header.substring("sha256=".length()), hmac(secret, body));
    }

    /**
     * {@code Stripe-Signature: t=<unix>,v1=<hex>} (comma-separated, more
     * entries allowed; only {@code t} and the FIRST {@code v1} are used).
     * Signed payload is {@code "<t>.<body>"}, so the timestamp is signed and
     * checking it bounds replay.
     */
    private static boolean verifyStripe(String secret, byte[] body, HttpHeaders headers) {
        return verifyStripe(secret, body, headers, Instant.now());
    }

    private static boolean verifyStripe(String secret, byte[] body, HttpHeaders headers, Instant now) {
        String header = headers.getFirst("Stripe-Signature");
        if (header == null) {
            return false;
        }
        String timestamp = null;
        String v1 = null;
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            if ("t".equals(k)) {
                timestamp = v;
            } else if ("v1".equals(k) && v1 == null) {
                v1 = v;
            }
        }
        if (timestamp == null || v1 == null || !timestampWithinTolerance(timestamp, now)) {
            return false;
        }
        byte[] payload = concat(timestamp.getBytes(StandardCharsets.UTF_8), ".".getBytes(StandardCharsets.UTF_8), body);
        return signatureEquals(v1, hmac(secret, payload));
    }

    /**
     * {@code X-Slack-Signature: v0=<hex>} over {@code "v0:<ts>:<body>"}, with
     * {@code X-Slack-Request-Timestamp} inside the signed prefix.
     */
    private static boolean verifySlack(String secret, byte[] body, HttpHeaders headers) {
        return verifySlack(secret, body, headers, Instant.now());
    }

    private static boolean verifySlack(String secret, byte[] body, HttpHeaders headers, Instant now) {
        String signature = headers.getFirst("X-Slack-Signature");
        String timestamp = headers.getFirst("X-Slack-Request-Timestamp");
        if (signature == null || !signature.startsWith("v0=") || timestamp == null
                || !timestampWithinTolerance(timestamp, now)) {
            return false;
        }
        byte[] payload = concat("v0:".getBytes(StandardCharsets.UTF_8),
            timestamp.getBytes(StandardCharsets.UTF_8),
            ":".getBytes(StandardCharsets.UTF_8), body);
        return signatureEquals(signature.substring(3), hmac(secret, payload));
    }

    /**
     * Generic: {@code X-Signature-256} carrying a raw hex digest or
     * {@code sha256=<hex>} (both spellings appear in the wild).
     */
    private static boolean verifyGeneric(String secret, byte[] body, HttpHeaders headers) {
        String header = headers.getFirst("X-Signature-256");
        if (header == null) {
            return false;
        }
        String hex = header.startsWith("sha256=") ? header.substring("sha256=".length()) : header.strip();
        return signatureEquals(hex, hmac(secret, body));
    }

    private static boolean timestampWithinTolerance(String rawTimestamp, Instant now) {
        long ts;
        try {
            ts = Long.parseLong(rawTimestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        return Math.abs(now.getEpochSecond() - ts) <= REPLAY_TOLERANCE_SECONDS;
    }

    private static byte[] hmac(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception e) {
            // HmacSHA256 is guaranteed on every JDK.
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Constant-time hex compare; also rejects malformed (non-hex) input. */
    private static boolean signatureEquals(String presentedHex, byte[] expected) {
        if (presentedHex == null) {
            return false;
        }
        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(presentedHex.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(presented, expected);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
