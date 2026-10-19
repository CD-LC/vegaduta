package com.agenticai.core.providers.addons;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Authenticates the host-side installer on {@code /api/addons/host/**}. The installer is not a
 * signed-in person, so it presents a shared secret instead: {@code Authorization: Bearer <token>},
 * where the token is {@code app.addons.host-token} ({@code APP_ADDONS_HOST_TOKEN}), generated once
 * into the install's {@code deploy/compose/.env} (the file the installer already reads and writes)
 * and handed to core as an environment variable.
 *
 * <p>No token configured, or one shorter than {@link #MIN_LENGTH}, switches the host surface off
 * (503): there is no unauthenticated fallback and no "loopback is trusted" shortcut, because the API
 * port can be published beyond loopback ({@code BIND_ADDR}) and loopback is shared with every other
 * program on the computer. The comparison is constant time over fixed-length digests, so neither the
 * token nor its length leaks through timing; the token is never logged.
 */
@Component("addonHostAuth")
public class AddonHostAuth {

    private static final Logger log = LoggerFactory.getLogger(AddonHostAuth.class);

    /** 32 characters of a random token is at least 128 bits when generated with a CSPRNG as documented. */
    static final int MIN_LENGTH = 32;

    private final byte[] expectedDigest;

    public AddonHostAuth(@Value("${app.addons.host-token:}") String token) {
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.isEmpty()) {
            this.expectedDigest = null;
        } else if (trimmed.length() < MIN_LENGTH) {
            log.warn("app.addons.host-token is shorter than {} characters; the add-on host API stays off.", MIN_LENGTH);
            this.expectedDigest = null;
        } else {
            this.expectedDigest = sha256(trimmed);
        }
    }

    public boolean configured() {
        return expectedDigest != null;
    }

    /**
     * @throws AddonException 503 {@code host_bridge_disabled} when no usable token is configured, 401
     *     {@code unauthorized} when the header is missing or wrong
     */
    public void require(String authorizationHeader) {
        if (expectedDigest == null) {
            throw new AddonException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "host_bridge_disabled",
                    "The installer link is not set up on this install (APP_ADDONS_HOST_TOKEN).");
        }
        String presented = bearer(authorizationHeader);
        if (presented == null || !MessageDigest.isEqual(expectedDigest, sha256(presented))) {
            throw new AddonException(HttpStatus.UNAUTHORIZED, "unauthorized", "A valid installer token is required.");
        }
    }

    private static String bearer(String header) {
        if (header == null) {
            return null;
        }
        String h = header.strip();
        if (h.length() <= 7 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = h.substring(7).strip();
        return token.isEmpty() ? null : token;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Lower-case hex of SHA-256, for the wanted-set fingerprint. */
    static String sha256Hex(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : sha256(value)) {
            out.append(String.format(Locale.ROOT, "%02x", b));
        }
        return out.toString();
    }
}
