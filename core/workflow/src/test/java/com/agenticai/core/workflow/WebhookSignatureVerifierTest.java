package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "whsec_test_secret_value";
    private static final byte[] BODY = "{\"type\":\"push\",\"id\":42}".getBytes(StandardCharsets.UTF_8);

    private static String hmacHex(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpHeaders headers(String name, String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(name, value);
        return headers;
    }

    @Test
    void noneModeAlwaysVerifies() {
        assertTrue(WebhookSignatureVerifier.verify(WebhookSignatureMode.NONE, null, null, new HttpHeaders()));
    }

    @Test
    void missingSecretFailsClosed() {
        HttpHeaders headers = headers("X-Hub-Signature-256", "sha256=" + hmacHex(SECRET, BODY));
        assertFalse(WebhookSignatureVerifier.verify(WebhookSignatureMode.GITHUB, null, BODY, headers));
        assertFalse(WebhookSignatureVerifier.verify(WebhookSignatureMode.GITHUB, "  ", BODY, headers));
    }

    @Test
    void githubAcceptsValidSignature() {
        HttpHeaders headers = headers("X-Hub-Signature-256", "sha256=" + hmacHex(SECRET, BODY));
        assertTrue(WebhookSignatureVerifier.verify(WebhookSignatureMode.GITHUB, SECRET, BODY, headers));
    }

    @Test
    void githubRejectsWrongSecretAndMissingHeader() {
        HttpHeaders wrong = headers("X-Hub-Signature-256", "sha256=" + hmacHex("other-secret", BODY));
        assertFalse(WebhookSignatureVerifier.verify(WebhookSignatureMode.GITHUB, SECRET, BODY, wrong));
        assertFalse(WebhookSignatureVerifier.verify(WebhookSignatureMode.GITHUB, SECRET, BODY, new HttpHeaders()));
        HttpHeaders badScheme = headers("X-Hub-Signature-256", hmacHex(SECRET, BODY));
        assertFalse(WebhookSignatureVerifier.verify(WebhookSignatureMode.GITHUB, SECRET, BODY, badScheme));
    }

    @Test
    void stripeAcceptsValidSignatureWithinTolerance() {
        long t = Instant.now().getEpochSecond();
        byte[] payload = (t + "." + new String(BODY, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = headers("Stripe-Signature", "t=" + t + ",v1=" + hmacHex(SECRET, payload));
        assertTrue(WebhookSignatureVerifier.verify(WebhookSignatureMode.STRIPE, SECRET, BODY, headers));
    }

    @Test
    void stripeRejectsStaleTimestampAndForgedTimestamp() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long stale = now.getEpochSecond() - WebhookSignatureVerifier.REPLAY_TOLERANCE_SECONDS - 60;
        byte[] stalePayload = (stale + "." + new String(BODY, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        HttpHeaders staleHeaders =
            headers("Stripe-Signature", "t=" + stale + ",v1=" + hmacHex(SECRET, stalePayload));
        assertFalse(WebhookSignatureVerifier.verify(WebhookSignatureMode.STRIPE, SECRET, BODY, staleHeaders, now));

        // Forged "fresh" timestamp that was never signed: the v1 covers the
        // ORIGINAL t, so moving t invalidates the signature even inside the window.
        long orig = now.getEpochSecond() - 400;
        byte[] origPayload = (orig + "." + new String(BODY, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        HttpHeaders forged =
            headers("Stripe-Signature", "t=" + now.getEpochSecond() + ",v1=" + hmacHex(SECRET, origPayload));
        assertFalse(WebhookSignatureVerifier.verify(WebhookSignatureMode.STRIPE, SECRET, BODY, forged, now));
    }

    @Test
    void slackAcceptsValidSignature() {
        Instant now = Instant.now();
        long ts = now.getEpochSecond();
        byte[] payload = ("v0:" + ts + ":" + new String(BODY, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = headers("X-Slack-Signature", "v0=" + hmacHex(SECRET, payload));
        headers.add("X-Slack-Request-Timestamp", String.valueOf(ts));
        assertTrue(WebhookSignatureVerifier.verify(WebhookSignatureMode.SLACK, SECRET, BODY, headers, now));
    }

    @Test
    void slackRejectsMissingTimestampAndOldTimestamp() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        byte[] payload = ("v0:" + now.getEpochSecond() + ":" + new String(BODY, StandardCharsets.UTF_8))
            .getBytes(StandardCharsets.UTF_8);
        String sig = "v0=" + hmacHex(SECRET, payload);
        HttpHeaders noTs = headers("X-Slack-Signature", sig);
        assertFalse(WebhookSignatureVerifier.verify(WebhookSignatureMode.SLACK, SECRET, BODY, noTs, now));

        long stale = now.getEpochSecond() - WebhookSignatureVerifier.REPLAY_TOLERANCE_SECONDS - 1;
        byte[] stalePayload = ("v0:" + stale + ":" + new String(BODY, StandardCharsets.UTF_8))
            .getBytes(StandardCharsets.UTF_8);
        HttpHeaders staleHeaders = headers("X-Slack-Signature", "v0=" + hmacHex(SECRET, stalePayload));
        staleHeaders.add("X-Slack-Request-Timestamp", String.valueOf(stale));
        assertFalse(WebhookSignatureVerifier.verify(WebhookSignatureMode.SLACK, SECRET, BODY, staleHeaders, now));
    }

    @Test
    void genericAcceptsBareAndPrefixedHex() {
        String hex = hmacHex(SECRET, BODY);
        assertTrue(WebhookSignatureVerifier.verify(
            WebhookSignatureMode.HMAC_SHA256, SECRET, BODY, headers("X-Signature-256", hex)));
        assertTrue(WebhookSignatureVerifier.verify(
            WebhookSignatureMode.HMAC_SHA256, SECRET, BODY, headers("X-Signature-256", "sha256=" + hex)));
    }

    @Test
    void malformedSignatureNeverVerifies() {
        HttpHeaders garbage = headers("X-Signature-256", "not-hex!");
        assertFalse(WebhookSignatureVerifier.verify(
            WebhookSignatureMode.HMAC_SHA256, SECRET, BODY, garbage));
    }

    @Test
    void emptyBodyStillVerifiesWhenSigned() {
        byte[] empty = new byte[0];
        HttpHeaders headers = headers("X-Signature-256", hmacHex(SECRET, empty));
        assertTrue(WebhookSignatureVerifier.verify(WebhookSignatureMode.HMAC_SHA256, SECRET, empty, headers));
        assertTrue(WebhookSignatureVerifier.verify(WebhookSignatureMode.HMAC_SHA256, SECRET, null, headers));
    }
}
