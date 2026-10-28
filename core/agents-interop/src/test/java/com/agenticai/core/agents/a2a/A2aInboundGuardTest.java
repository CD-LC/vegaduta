package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Message-level checks on inbound A2A requests, against a REAL signer (the same Ed25519 key the
 * Agent Card is signed with) and the real nonce cache: valid, tampered, replayed, stale, legacy and
 * over-the-hop-limit requests.
 */
class A2aInboundGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final URI ENDPOINT = URI.create("https://agents.example.com/api/agents/a1/a2a");
    private static final String PATH = "/api/agents/a1/a2a";
    private static final String BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\",\"params\":{\"message\":"
                    + "{\"messageId\":\"m\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"hi\"}]}}}";

    private MutableClock clock;
    private A2aCardSigningService signing;
    private A2aMessageSigner signer;
    private A2aMessageSecurityProperties security;
    private A2aNonceCache nonces;
    private A2aInboundGuard guard;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-28T12:00:00Z"));
        A2aProperties properties = new A2aProperties();
        properties.setPublicBaseUrl("https://caller.example.com");
        signing = new A2aCardSigningService(properties);
        signer = new A2aMessageSigner(signing, properties, clock);
        security = new A2aMessageSecurityProperties();
        nonces = new A2aNonceCache(Duration.ofMinutes(10), 100, clock, null);
        guard = guardWith(signing);
    }

    private A2aInboundGuard guardWith(A2aCardSigningService trusted) {
        A2aPeerKeyResolver keys =
                (cardUrl, kid) ->
                        cardUrl.equals("https://caller.example.com/.well-known/agent-card.json")
                                        && kid.equals(trusted.keyId())
                                ? Optional.of(trusted.publicKey())
                                : Optional.empty();
        return new A2aInboundGuard(security, nonces, keys, clock);
    }

    private Map<String, String> signed(String body, int hop, String trace) {
        return signer.headers(ENDPOINT, body, hop, trace, true);
    }

    private static A2aInboundMeta meta(String path, Map<String, String> headers) {
        return new A2aInboundMeta("POST", path, headers::get);
    }

    private static JsonNode params(String body) throws Exception {
        return JSON.readTree(body).path("params");
    }

    private A2aError rejected(A2aInboundMeta meta, String body) {
        return assertThrows(A2aError.class, () -> guard.check(meta, body, params(body)));
    }

    // ------------------------------------------------------------ signatures

    @Test
    void validSignedRequestIsAcceptedAndCarriesHopAndTrace() throws Exception {
        Map<String, String> headers = signed(BODY, 2, "trace-1");
        assertTrue(headers.containsKey(A2aMessageSecurity.HEADER_SIGNATURE));

        A2aCallContext call = guard.check(meta(PATH, headers), BODY, params(BODY));

        assertTrue(call.signed());
        assertEquals(2, call.hop());
        assertEquals("trace-1", call.traceId());
        assertEquals(3, call.nextHop());
    }

    @Test
    void signatureIsDetached() {
        String sig = signed(BODY, 1, "t").get(A2aMessageSecurity.HEADER_SIGNATURE);
        String[] parts = sig.split("\\.", -1);
        assertEquals(3, parts.length);
        assertEquals("", parts[1], "the payload is rebuilt by the verifier, never sent");
    }

    @Test
    void tamperedBodyIsRejected() throws Exception {
        Map<String, String> headers = signed(BODY, 1, "t");
        String tampered = BODY.replace("\"hi\"", "\"transfer all funds\"");

        A2aError e = rejected(meta(PATH, headers), tampered);

        assertEquals(A2aError.MESSAGE_SIGNATURE_INVALID, e.getCode());
    }

    @Test
    void tamperedPathIsRejected() {
        Map<String, String> headers = signed(BODY, 1, "t");

        A2aError e = rejected(meta("/api/agents/other/a2a", headers), BODY);

        assertEquals(A2aError.MESSAGE_SIGNATURE_INVALID, e.getCode());
    }

    @Test
    void loweringTheHopHeaderBreaksTheSignature() {
        Map<String, String> headers = new HashMap<>(signed(BODY, 3, "t"));
        headers.put(A2aMessageSecurity.HEADER_HOP, "0");

        A2aError e = rejected(meta(PATH, headers), BODY);

        assertEquals(A2aError.MESSAGE_SIGNATURE_INVALID, e.getCode());
    }

    @Test
    void signatureFromAnotherKeyIsRejected() throws Exception {
        A2aProperties other = new A2aProperties();
        other.setPublicBaseUrl("https://caller.example.com");
        A2aCardSigningService stranger = new A2aCardSigningService(other);
        Map<String, String> headers =
                new A2aMessageSigner(stranger, other, clock).headers(ENDPOINT, BODY, 1, "t", true);
        // The resolver answers for the stranger's kid with OUR caller's key: the signature cannot verify.
        A2aPeerKeyResolver keys =
                (url, kid) -> Optional.of(signing.publicKey());
        A2aInboundGuard g = new A2aInboundGuard(security, nonces, keys, clock);

        A2aError e =
                assertThrows(
                        A2aError.class, () -> g.check(meta(PATH, headers), BODY, params(BODY)));

        assertEquals(A2aError.MESSAGE_SIGNATURE_INVALID, e.getCode());
    }

    @Test
    void unresolvableKeyIsRejected() {
        Map<String, String> headers = new HashMap<>(signed(BODY, 1, "t"));
        headers.put(A2aMessageSecurity.HEADER_ORIGIN, "https://evil.example.com/card.json");

        A2aError e = rejected(meta(PATH, headers), BODY);

        assertEquals(A2aError.MESSAGE_SIGNATURE_INVALID, e.getCode());
    }

    @Test
    void aCardSignatureCannotBePassedOffAsAMessageSignature() throws Exception {
        // Same key, but a protected header of the wrong type: refused before any key lookup.
        Map<String, String> headers = new HashMap<>(signed(BODY, 1, "t"));
        String[] parts = headers.get(A2aMessageSecurity.HEADER_SIGNATURE).split("\\.", -1);
        String wrongHeader =
                java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                ("{\"alg\":\"EdDSA\",\"kid\":\"" + signing.keyId() + "\",\"typ\":\"JWS\"}")
                                        .getBytes());
        headers.put(A2aMessageSecurity.HEADER_SIGNATURE, wrongHeader + ".." + parts[2]);

        A2aError e = rejected(meta(PATH, headers), BODY);

        assertEquals(A2aError.MESSAGE_SIGNATURE_INVALID, e.getCode());
    }

    // ------------------------------------------------------------ replay window

    @Test
    void replayedNonceIsRejected() throws Exception {
        Map<String, String> headers = signed(BODY, 1, "t");
        guard.check(meta(PATH, headers), BODY, params(BODY));

        A2aError e = rejected(meta(PATH, headers), BODY);

        assertEquals(A2aError.MESSAGE_REPLAYED, e.getCode());
    }

    @Test
    void aFreshNonceForTheSameBodyIsAccepted() throws Exception {
        guard.check(meta(PATH, signed(BODY, 1, "t")), BODY, params(BODY));
        A2aCallContext second = guard.check(meta(PATH, signed(BODY, 1, "t")), BODY, params(BODY));
        assertTrue(second.signed());
    }

    @Test
    void staleTimestampIsRejected() {
        Map<String, String> headers = signed(BODY, 1, "t");
        clock.advance(Duration.ofMinutes(6));

        A2aError e = rejected(meta(PATH, headers), BODY);

        assertEquals(A2aError.MESSAGE_EXPIRED, e.getCode());
    }

    @Test
    void timestampFromTheFutureIsRejected() {
        Map<String, String> headers = signed(BODY, 1, "t");
        clock.advance(Duration.ofMinutes(-6));

        A2aError e = rejected(meta(PATH, headers), BODY);

        assertEquals(A2aError.MESSAGE_EXPIRED, e.getCode());
    }

    @Test
    void timestampInsideTheWindowIsAccepted() throws Exception {
        Map<String, String> headers = signed(BODY, 1, "t");
        clock.advance(Duration.ofMinutes(4));
        assertTrue(guard.check(meta(PATH, headers), BODY, params(BODY)).signed());
    }

    @Test
    void aFailedSignatureDoesNotBurnTheNonce() throws Exception {
        Map<String, String> headers = signed(BODY, 1, "t");
        rejected(meta(PATH, headers), BODY.replace("hi", "ho"));
        // The untampered request is still fresh: garbage cannot pre-claim a victim's nonce.
        assertTrue(guard.check(meta(PATH, headers), BODY, params(BODY)).signed());
    }

    // ------------------------------------------------------------ legacy peers

    @Test
    void legacyUnsignedRequestIsAcceptedWhenAllowed() throws Exception {
        A2aCallContext call = guard.check(meta(PATH, Map.of()), BODY, params(BODY));

        assertFalse(call.signed());
        assertEquals(0, call.hop());
        assertFalse(call.traceId().isBlank());
    }

    @Test
    void legacyUnsignedRequestIsRejectedWhenSignaturesAreRequired() {
        security.getServer().setRequireSignedMessages(true);

        A2aError e = rejected(meta(PATH, Map.of()), BODY);

        assertEquals(A2aError.MESSAGE_SIGNATURE_REQUIRED, e.getCode());
    }

    @Test
    void requiredSignaturesStillAcceptASignedRequest() throws Exception {
        security.getServer().setRequireSignedMessages(true);
        assertTrue(guard.check(meta(PATH, signed(BODY, 1, "t")), BODY, params(BODY)).signed());
    }

    @Test
    void verificationCanBeSwitchedOffAndStillEnforcesTheHopLimit() throws Exception {
        security.getServer().setVerifySignatures(false);
        Map<String, String> headers = new HashMap<>(signed(BODY, 2, "t"));
        headers.put(A2aMessageSecurity.HEADER_SIGNATURE, "garbage..garbage");

        A2aCallContext call = guard.check(meta(PATH, headers), BODY, params(BODY));

        assertFalse(call.signed());
        assertEquals(2, call.hop());
        headers.put(A2aMessageSecurity.HEADER_HOP, "9");
        assertEquals(A2aError.HOP_LIMIT_EXCEEDED, rejected(meta(PATH, headers), BODY).getCode());
    }

    // ------------------------------------------------------------ hop limit

    @Test
    void hopAtTheLimitIsAcceptedAndAboveItIsRejected() throws Exception {
        assertEquals(4, security.getMaxHops());
        assertEquals(
                4,
                guard.check(meta(PATH, signed(BODY, 4, "t")), BODY, params(BODY)).hop());

        A2aError e = rejected(meta(PATH, signed(BODY, 5, "t")), BODY);

        assertEquals(A2aError.HOP_LIMIT_EXCEEDED, e.getCode());
    }

    @Test
    void unsignedRequestsAreHopLimitedToo() {
        A2aError e =
                rejected(meta(PATH, Map.of(A2aMessageSecurity.HEADER_HOP, "5")), BODY);
        assertEquals(A2aError.HOP_LIMIT_EXCEEDED, e.getCode());
    }

    @Test
    void hopInTheMessageMetadataCountsToo() {
        String body = BODY.replace("\"messageId\":\"m\"",
                "\"messageId\":\"m\",\"metadata\":{\"vegaduta.a2a.hop\":7}");
        A2aError e = rejected(meta(PATH, Map.of()), body);
        assertEquals(A2aError.HOP_LIMIT_EXCEEDED, e.getCode());
    }

    @Test
    void theLargerOfHeaderAndMetadataHopWins() throws Exception {
        String body = BODY.replace("\"messageId\":\"m\"",
                "\"messageId\":\"m\",\"metadata\":{\"vegaduta.a2a.hop\":3}");
        A2aCallContext call =
                guard.check(
                        meta(PATH, Map.of(A2aMessageSecurity.HEADER_HOP, "1")), body, params(body));
        assertEquals(3, call.hop());
    }

    @Test
    void hopLimitCanBeSwitchedOff() throws Exception {
        security.setMaxHops(0);
        A2aCallContext call =
                guard.check(
                        meta(PATH, Map.of(A2aMessageSecurity.HEADER_HOP, "99")),
                        BODY,
                        params(BODY));
        assertEquals(99, call.hop());
    }

    @Test
    void malformedHopIsAnInvalidRequest() {
        for (String bad : new String[] {"abc", "-1", "1.5", ""}) {
            if (bad.isEmpty()) {
                continue; // blank = absent
            }
            A2aError e = rejected(meta(PATH, Map.of(A2aMessageSecurity.HEADER_HOP, bad)), BODY);
            assertEquals(A2aError.INVALID_REQUEST, e.getCode(), bad);
        }
    }

    @Test
    void traceIdFromAnUnsignedHeaderIsKeptOnlyWhenSafe() throws Exception {
        A2aCallContext ok =
                guard.check(
                        meta(PATH, Map.of(A2aMessageSecurity.HEADER_TRACE, "req-123")),
                        BODY,
                        params(BODY));
        assertEquals("req-123", ok.traceId());
        A2aCallContext injected =
                guard.check(
                        meta(PATH, Map.of(A2aMessageSecurity.HEADER_TRACE, "a b\nc")),
                        BODY,
                        params(BODY));
        assertFalse(injected.traceId().contains("\n"));
    }

    // ------------------------------------------------------------ signer

    @Test
    void signerSendsOnlyHopAndTraceWithoutAPublicBaseUrl() {
        A2aProperties noBase = new A2aProperties();
        A2aMessageSigner unsigned =
                new A2aMessageSigner(new A2aCardSigningService(noBase), noBase, clock);

        Map<String, String> headers = unsigned.headers(ENDPOINT, BODY, 1, "t", true);

        assertEquals("1", headers.get(A2aMessageSecurity.HEADER_HOP));
        assertEquals("t", headers.get(A2aMessageSecurity.HEADER_TRACE));
        assertFalse(headers.containsKey(A2aMessageSecurity.HEADER_SIGNATURE));
    }

    @Test
    void signerHonoursTheSignSwitch() {
        Map<String, String> headers = signer.headers(ENDPOINT, BODY, 1, "t", false);
        assertFalse(headers.containsKey(A2aMessageSecurity.HEADER_SIGNATURE));
        assertEquals("1", headers.get(A2aMessageSecurity.HEADER_HOP));
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
