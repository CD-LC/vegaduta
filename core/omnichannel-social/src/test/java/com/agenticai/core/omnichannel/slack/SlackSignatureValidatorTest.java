package com.agenticai.core.omnichannel.slack;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * The Slack request signature is the only authentication on {@code /webhook/slack} for everything
 * except the one-time URL handshake. Real validator, signatures computed here with the documented
 * scheme (v0=HMAC-SHA256(secret, "v0:" + timestamp + ":" + body)), so a validator that stopped
 * checking would fail these.
 */
class SlackSignatureValidatorTest {

    private static final String SECRET = "test-signing-secret";
    private static final String BODY = "{\"type\":\"event_callback\",\"team_id\":\"T1\"}";

    private final SlackSignatureValidator validator = new SlackSignatureValidator();

    static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw =
                    mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8));
            return "v0=" + HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String now() {
        return Long.toString(Instant.now().getEpochSecond());
    }

    @Test
    void aCorrectlySignedFreshRequestIsAccepted() {
        String ts = now();
        assertDoesNotThrow(
                () -> validator.validateOrThrow(BODY, ts, sign(SECRET, ts, BODY), SECRET));
    }

    @Test
    void aBadSignatureIsRejected() {
        String ts = now();
        assertThrows(
                SlackWebhookSignatureException.class,
                () ->
                        validator.validateOrThrow(
                                BODY, ts, sign("another-secret", ts, BODY), SECRET));
    }

    @Test
    void aTamperedBodyIsRejected() {
        String ts = now();
        String signature = sign(SECRET, ts, BODY);
        assertThrows(
                SlackWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY + " ", ts, signature, SECRET));
    }

    @Test
    void aStaleTimestampIsRejectedEvenWithAValidSignature() {
        String ts = Long.toString(Instant.now().getEpochSecond() - 301);
        assertThrows(
                SlackWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, ts, sign(SECRET, ts, BODY), SECRET));
    }

    @Test
    void aTimestampFarInTheFutureIsRejectedToo() {
        String ts = Long.toString(Instant.now().getEpochSecond() + 301);
        assertThrows(
                SlackWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, ts, sign(SECRET, ts, BODY), SECRET));
    }

    @Test
    void aReplayedRequestWithTheOriginalTimestampStopsWorkingOnceItIsOld() {
        // Same body, same signature, same timestamp: accepted when fresh, refused later.
        long issued = Instant.now().getEpochSecond();
        String ts = Long.toString(issued - 3600);
        assertThrows(
                SlackWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, ts, sign(SECRET, ts, BODY), SECRET));
    }

    @Test
    void missingOrMalformedHeadersAreRejected() {
        String ts = now();
        String good = sign(SECRET, ts, BODY);
        assertThrows(
                SlackWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, null, good, SECRET));
        assertThrows(
                SlackWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, "", good, SECRET));
        assertThrows(
                SlackWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, ts, null, SECRET));
        assertThrows(
                SlackWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, ts, "", SECRET));
        assertThrows(
                SlackWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, ts, good.substring(3), SECRET));
        assertThrows(
                SlackWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, "not-a-number", good, SECRET));
    }

    @Test
    void aMissingSigningSecretFailsClosed() {
        String ts = now();
        String signedWithEmptyKey = "v0=" + "0".repeat(64);
        assertThrows(
                SlackWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, ts, signedWithEmptyKey, ""));
    }
}
