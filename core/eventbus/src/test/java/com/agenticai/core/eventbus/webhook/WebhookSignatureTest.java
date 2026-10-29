package com.agenticai.core.eventbus.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * {@link WebhookSignature#sign} is the ONLY thing a receiver checks before trusting a delivery -
 * the timestamp inside the signed content is what lets them bound the replay window, so the {@code
 * t=} value must be the same one that went into the HMAC, and the digest must be over {@code
 * "<t>.<body>"} exactly (UTF-8, literal dot, no padding).
 */
class WebhookSignatureTest {

    private static String expectedV1(long epochSeconds, String secret, String body)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of()
                .formatHex(
                        mac.doFinal((epochSeconds + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theHeaderConstantIsTheDocumentedName() {
        // Receivers integrate against this literal; a rename is a silent breaking change.
        assertEquals("X-VegaDuta-Signature", WebhookSignature.HEADER);
    }

    @Test
    void signEmitsTheStripeStyleTCommaV1Format() throws Exception {
        String header = WebhookSignature.sign(1_727_000_000L, "whsec_abc", "{\"a\":1}");

        assertTrue(header.startsWith("t=1727000000,v1="));
        String v1 = header.substring("t=1727000000,v1=".length());
        assertEquals(64, v1.length(), "HMAC-SHA256 hex is 64 chars");
        assertEquals(expectedV1(1_727_000_000L, "whsec_abc", "{\"a\":1}"), v1);
    }

    @Test
    void aDifferentTimestampProducesADifferentV1() {
        // If the timestamp were not inside the signed content these would be equal and a receiver
        // could replay an old delivery with a fresh t=.
        assertNotEquals(
                WebhookSignature.sign(1L, "s", "body"), WebhookSignature.sign(2L, "s", "body"));
    }

    @Test
    void aNullBodyIsSignedAsTheEmptyString() throws Exception {
        String header = WebhookSignature.sign(42L, "s", null);

        assertEquals("t=42,v1=" + expectedV1(42L, "s", ""), header);
    }
}
