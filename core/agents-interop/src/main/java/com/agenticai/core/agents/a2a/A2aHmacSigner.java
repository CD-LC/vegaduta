package com.agenticai.core.agents.a2a;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Detached HMAC-SHA256 over {@code method, path, timestamp, nonce, hop, sha256(body)}. The header
 * value is {@code v1=<base64url>}. The hop is inside the MAC so a relay cannot lower it. The path
 * is the raw request path as the receiver sees it; a proxy that rewrites paths breaks verification
 * (documented operator constraint).
 */
final class A2aHmacSigner {

    static final String PREFIX = "v1=";

    private A2aHmacSigner() {}

    static String canonical(
            String method, String path, String timestamp, String nonce, int hop, byte[] body) {
        return "A2A-HMAC-SHA256\n"
                + method.toUpperCase(Locale.ROOT)
                + "\n"
                + path
                + "\n"
                + timestamp
                + "\n"
                + nonce
                + "\n"
                + hop
                + "\n"
                + sha256Hex(body);
    }

    static String sign(
            String secret,
            String method,
            String path,
            String timestamp,
            String nonce,
            int hop,
            byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] tag =
                    mac.doFinal(
                            canonical(method, path, timestamp, nonce, hop, body)
                                    .getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(tag);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    static boolean verify(
            String secret,
            String header,
            String method,
            String path,
            String timestamp,
            String nonce,
            int hop,
            byte[] body) {
        if (header == null) {
            return false;
        }
        String expected = sign(secret, method, path, timestamp, nonce, hop, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                header.strip().getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
