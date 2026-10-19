package com.agenticai.core.agents.tools.browseruse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The short-lived signed token that lets the person's browser open the live view and send input for
 * ONE takeover episode. It is a second check on top of the normal bearer token: the bearer proves
 * who is calling, this proves they were handed THIS takeover by the server moments ago.
 *
 * <p>Format {@code v1.<base64url(payload)>.<base64url(HMAC-SHA256)>}, payload {@code
 * takeoverId|epoch|tenantId|userId|expiresAtEpochSecond}. Verification re-derives the HMAC
 * (constant-time compare) and then the caller checks the claims against the LIVE session: tenant,
 * user, takeover id, current epoch and state. A token minted for another tenant, user, episode or
 * an earlier epoch is therefore useless even if it is validly signed. The signing key is the
 * operator's {@code app.browser.takeover.token-secret}, or a random per-process key when none is
 * set (tokens then die with the JVM, exactly like the sessions they guard).
 *
 * <p>The token is never logged and never put in a URL by this code - it travels in a request
 * header.
 */
final class BrowserTakeoverToken {

    private static final String VERSION = "v1";

    /** What a valid token claims. */
    record Claims(UUID takeoverId, int epoch, String tenantId, String userId, Instant expiresAt) {}

    private final byte[] key;

    BrowserTakeoverToken(String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = random;
        } else {
            this.key = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    String issue(UUID takeoverId, int epoch, String tenantId, String userId, Instant expiresAt) {
        String payload =
                takeoverId
                        + "|"
                        + epoch
                        + "|"
                        + escape(tenantId)
                        + "|"
                        + escape(userId)
                        + "|"
                        + expiresAt.getEpochSecond();
        String body =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return VERSION + "." + body + "." + sign(body);
    }

    /**
     * The claims of a well-formed, correctly signed, unexpired token; null for anything else (never
     * throws).
     */
    Claims verify(String token, Instant now) {
        if (token == null || token.length() > 1_024) {
            return null;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            return null;
        }
        byte[] expected = sign(parts[1]).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, parts[2].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        try {
            String payload =
                    new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String[] f = payload.split("\\|", -1);
            if (f.length != 5) {
                return null;
            }
            Claims claims =
                    new Claims(
                            UUID.fromString(f[0]),
                            Integer.parseInt(f[1]),
                            unescape(f[2]),
                            unescape(f[3]),
                            Instant.ofEpochSecond(Long.parseLong(f[4])));
            return claims.expiresAt().isAfter(now) ? claims : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    // A tenant or user id could contain the '|' separator; escape so a crafted id cannot shift
    // fields.
    private static String escape(String value) {
        return (value == null ? "" : value).replace("%", "%25").replace("|", "%7C");
    }

    private static String unescape(String value) {
        return value.replace("%7C", "|").replace("%25", "%");
    }
}
