package com.agenticai.core.eventbus.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stripe-style HMAC-SHA256 signing for outbound webhook deliveries. The
 * header value sent is {@code t=<unixSeconds>,v1=<hex(hmac_sha256(secret,
 * "<t>.<body>"))>} - the timestamp inside the signed content lets the
 * receiver bound replay windows instead of only proving integrity.
 */
public final class WebhookSignature {

    public static final String HEADER = "X-VegaDuta-Signature";

    private WebhookSignature() {
    }

    /** @return the full header value, e.g. {@code t=1727000000,v1=9f86d0...}. */
    public static String sign(long epochSeconds, String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(
                (epochSeconds + "." + (body == null ? "" : body)).getBytes(StandardCharsets.UTF_8));
            return "t=" + epochSeconds + ",v1=" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is a JDK-mandatory algorithm and a random hex secret
            // can never be an invalid key - reaching this is a JVM defect.
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
