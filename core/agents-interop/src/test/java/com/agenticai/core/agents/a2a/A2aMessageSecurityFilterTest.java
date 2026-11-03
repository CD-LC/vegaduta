package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class A2aMessageSecurityFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final String PATH = "/api/a2a";
    private static final String BODY = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"GetTask\"}";
    private static final String SECRET = "unit-test-shared-secret";

    private final ObjectMapper mapper = new ObjectMapper();
    private A2aMessageSecurityProperties props;
    private A2aNonceStore nonces;
    private A2aMessageSecurityFilter filter;

    @BeforeEach
    void setUp() {
        props = new A2aMessageSecurityProperties();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        nonces = new A2aNonceStore(clock, 100, null);
        filter = new A2aMessageSecurityFilter(props, nonces, mapper, clock);
        TenantContext.set(TenantContext.of("tenant-a", "alice", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockHttpServletRequest req(String path, String ts, String nonce, String hop) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.setRequestURI(path);
        r.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        r.setContentType("application/json");
        if (ts != null) r.addHeader(A2aMessageSecurityFilter.TIMESTAMP_HEADER, ts);
        if (nonce != null) r.addHeader(A2aMessageSecurityFilter.NONCE_HEADER, nonce);
        if (hop != null) r.addHeader(A2aMessageSecurityFilter.HOP_HEADER, hop);
        return r;
    }

    private static String now() {
        return Long.toString(NOW.getEpochSecond());
    }

    /** Runs the filter; returns the response. {@code reached[0]} says whether the chain ran. */
    private MockHttpServletResponse run(MockHttpServletRequest r, boolean[] reached)
            throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(
                r,
                resp,
                new MockFilterChain() {
                    @Override
                    public void doFilter(
                            jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) {
                        reached[0] = true;
                    }
                });
        return resp;
    }

    private int errorCode(MockHttpServletResponse resp) throws Exception {
        JsonNode n = mapper.readTree(resp.getContentAsString());
        return n.path("error").path("code").asInt();
    }

    @Test
    void validHeadersPassAndReplayIsRefused() throws Exception {
        boolean[] ok = {false};
        run(req(PATH, now(), "nonce-aaaaaaaa", "0"), ok);
        assertTrue(ok[0]);

        boolean[] replay = {false};
        MockHttpServletResponse resp = run(req(PATH, now(), "nonce-aaaaaaaa", "0"), replay);
        assertFalse(replay[0]);
        assertEquals(200, resp.getStatus());
        assertEquals(A2aError.REPLAY_DETECTED, errorCode(resp));
        assertEquals(7, mapper.readTree(resp.getContentAsString()).path("id").asInt());
    }

    @Test
    void clockSkewBeyondWindowIsRefusedBothDirections() throws Exception {
        String stale = Long.toString(NOW.getEpochSecond() - 301);
        String future = Long.toString(NOW.getEpochSecond() + 301);
        for (String ts : new String[] {stale, future}) {
            boolean[] reached = {false};
            MockHttpServletResponse resp = run(req(PATH, ts, "nonce-skew-" + ts, "0"), reached);
            assertFalse(reached[0]);
            assertEquals(A2aError.TIMESTAMP_OUT_OF_WINDOW, errorCode(resp));
        }
        boolean[] edge = {false};
        run(req(PATH, Long.toString(NOW.getEpochSecond() - 300), "nonce-edge-1", "0"), edge);
        assertTrue(edge[0]);
    }

    @Test
    void staleRequestDoesNotBurnItsNonce() throws Exception {
        String stale = Long.toString(NOW.getEpochSecond() - 1000);
        run(req(PATH, stale, "nonce-burn-001", "0"), new boolean[1]);
        boolean[] ok = {false};
        run(req(PATH, now(), "nonce-burn-001", "0"), ok);
        assertTrue(ok[0]);
    }

    @Test
    void hopLimitStopsLoops() throws Exception {
        boolean[] atLimit = {false};
        run(req(PATH, now(), "nonce-hop-0004", "4"), atLimit);
        assertTrue(atLimit[0]);

        boolean[] over = {false};
        MockHttpServletResponse resp = run(req(PATH, now(), "nonce-hop-0005", "5"), over);
        assertFalse(over[0]);
        assertEquals(A2aError.HOP_LIMIT_EXCEEDED, errorCode(resp));
    }

    @Test
    void hopLimitIsConfigurableAndAppliesWithoutReplayHeaders() throws Exception {
        props.setMaxHops(1);
        boolean[] over = {false};
        MockHttpServletResponse resp = run(req(PATH, null, null, "2"), over);
        assertFalse(over[0]);
        assertEquals(A2aError.HOP_LIMIT_EXCEEDED, errorCode(resp));
    }

    @Test
    void missingHeadersAcceptedByDefault() throws Exception {
        boolean[] ok = {false};
        run(req(PATH, null, null, null), ok);
        assertTrue(ok[0]);
        // and repeating the identical request is fine: no replay protection without headers
        boolean[] again = {false};
        run(req(PATH, null, null, null), again);
        assertTrue(again[0]);
    }

    @Test
    void missingHeadersRefusedWhenRequired() throws Exception {
        props.setRequireReplayProtection(true);
        boolean[] reached = {false};
        MockHttpServletResponse resp = run(req(PATH, null, null, null), reached);
        assertFalse(reached[0]);
        assertEquals(A2aError.REPLAY_HEADERS_REQUIRED, errorCode(resp));

        boolean[] partial = {false};
        resp = run(req(PATH, now(), "nonce-part-001", null), partial);
        assertFalse(partial[0]);
        assertEquals(A2aError.REPLAY_HEADERS_REQUIRED, errorCode(resp));

        boolean[] full = {false};
        run(req(PATH, now(), "nonce-full-001", "0"), full);
        assertTrue(full[0]);
    }

    @Test
    void timestampWithoutNonceIsMalformedEvenWhenNotRequired() throws Exception {
        boolean[] reached = {false};
        MockHttpServletResponse resp = run(req(PATH, now(), null, null), reached);
        assertFalse(reached[0]);
        assertEquals(A2aError.REPLAY_HEADERS_REQUIRED, errorCode(resp));
    }

    @Test
    void nonceCacheIsTenantScoped() throws Exception {
        boolean[] a = {false};
        run(req(PATH, now(), "shared-nonce-01", "0"), a);
        assertTrue(a[0]);

        TenantContext.set(TenantContext.of("tenant-b", "bob", Set.of()));
        boolean[] b = {false};
        run(req(PATH, now(), "shared-nonce-01", "0"), b);
        assertTrue(b[0], "tenant B must not be blocked by tenant A's nonce");

        TenantContext.set(TenantContext.of("tenant-a", "alice", Set.of()));
        boolean[] replayA = {false};
        run(req(PATH, now(), "shared-nonce-01", "0"), replayA);
        assertFalse(replayA[0]);
    }

    @Test
    void concurrentSameNonceExactlyOneWins() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(
                    pool.submit(
                            () -> {
                                start.await();
                                if (nonces.claim("tenant-a", "race-nonce-01", Duration.ofSeconds(300))
                                        == A2aNonceStore.Outcome.ACCEPTED) {
                                    wins.incrementAndGet();
                                }
                                return null;
                            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        assertEquals(1, wins.get());
    }

    @Test
    void nonceExpiresAfterTtlAndCapacityIsBounded() {
        java.util.concurrent.atomic.AtomicReference<Instant> t =
                new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock moving =
                new Clock() {
                    public java.time.ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    public Clock withZone(java.time.ZoneId z) {
                        return this;
                    }

                    public Instant instant() {
                        return t.get();
                    }
                };
        A2aNonceStore s = new A2aNonceStore(moving, 2, null);
        Duration ttl = Duration.ofSeconds(10);
        assertEquals(A2aNonceStore.Outcome.ACCEPTED, s.claim("t", "n1", ttl));
        assertEquals(A2aNonceStore.Outcome.ACCEPTED, s.claim("t", "n2", ttl));
        assertEquals(A2aNonceStore.Outcome.FULL, s.claim("t", "n3", ttl));
        assertEquals(A2aNonceStore.Outcome.ACCEPTED, s.claim("other", "n3", ttl));
        t.set(NOW.plusSeconds(11));
        assertEquals(A2aNonceStore.Outcome.ACCEPTED, s.claim("t", "n3", ttl));
        assertEquals(A2aNonceStore.Outcome.ACCEPTED, s.claim("t", "n1", ttl));
    }

    // ----------------------------------------------------------- signatures

    private MockHttpServletRequest signed(String nonce, String body, String signBody) {
        MockHttpServletRequest r = req(PATH, now(), nonce, "0");
        r.setContent(body.getBytes(StandardCharsets.UTF_8));
        r.addHeader(
                A2aMessageSecurityFilter.SIGNATURE_HEADER,
                A2aMessageSigner.sign(
                        SECRET,
                        "POST",
                        PATH,
                        now(),
                        nonce,
                        0,
                        signBody.getBytes(StandardCharsets.UTF_8)));
        return r;
    }

    @Test
    void validSignaturePassesAndBodyStillReadable() throws Exception {
        props.setSigningSecret(SECRET);
        String[] seen = {null};
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(
                signed("sig-nonce-0001", BODY, BODY),
                resp,
                (rq, rs) -> {
                    try {
                        seen[0] = new String(rq.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        assertEquals(BODY, seen[0]);
    }

    @Test
    void tamperedBodyFailsSignature() throws Exception {
        props.setSigningSecret(SECRET);
        boolean[] reached = {false};
        String tampered = BODY.replace("GetTask", "CancelTask");
        MockHttpServletResponse resp = run(signed("sig-nonce-0002", tampered, BODY), reached);
        assertFalse(reached[0]);
        assertEquals(A2aError.SIGNATURE_INVALID, errorCode(resp));
        // the failed attempt must not have consumed the nonce
        boolean[] ok = {false};
        run(signed("sig-nonce-0002", BODY, BODY), ok);
        assertTrue(ok[0]);
    }

    @Test
    void tamperedHopFailsSignature() throws Exception {
        props.setSigningSecret(SECRET);
        MockHttpServletRequest r = signed("sig-nonce-0003", BODY, BODY);
        // relay lowers/raises the hop after signing: header says 3, MAC covered 0
        r.removeHeader(A2aMessageSecurityFilter.HOP_HEADER);
        r.addHeader(A2aMessageSecurityFilter.HOP_HEADER, "3");
        boolean[] reached = {false};
        MockHttpServletResponse resp = run(r, reached);
        assertFalse(reached[0]);
        assertEquals(A2aError.SIGNATURE_INVALID, errorCode(resp));
    }

    @Test
    void signatureRequiredRejectsUnsignedButThirdPartyDefaultUnaffected() throws Exception {
        props.setSigningSecret(SECRET);
        boolean[] unsignedOk = {false};
        run(req(PATH, null, null, null), unsignedOk);
        assertTrue(unsignedOk[0]); // secret alone does not force signatures

        props.setRequireSignature(true);
        boolean[] reached = {false};
        MockHttpServletResponse resp = run(req(PATH, null, null, null), reached);
        assertFalse(reached[0]);
        assertEquals(A2aError.SIGNATURE_INVALID, errorCode(resp));
    }

    // ------------------------------------------------------- dialect / scope

    @Test
    void restRouteGetsGoogleRpcStatus() throws Exception {
        String rest = "/api/a2a/rest/message:send";
        run(req(rest, now(), "rest-nonce-001", "0"), new boolean[1]);
        boolean[] reached = {false};
        MockHttpServletResponse resp = run(req(rest, now(), "rest-nonce-001", "0"), reached);
        assertFalse(reached[0]);
        assertEquals(409, resp.getStatus());
        JsonNode err = mapper.readTree(resp.getContentAsString()).path("error");
        assertEquals("ABORTED", err.path("status").asText());
        assertEquals("MESSAGE_REPLAYED", err.path("details").get(0).path("reason").asText());
    }

    @Test
    void nonA2aPathsAndGetsAreNotTouched() {
        props.setRequireReplayProtection(true);
        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/agents");
        other.setRequestURI("/api/agents");
        assertTrue(filter.shouldNotFilter(other));
        MockHttpServletRequest get = new MockHttpServletRequest("GET", PATH);
        get.setRequestURI(PATH);
        assertTrue(filter.shouldNotFilter(get));
        MockHttpServletRequest scoped = new MockHttpServletRequest("POST", PATH);
        scoped.setRequestURI("/api/agents/1b4e28ba-2fa1-11d2-883f-0016d3cca427/a2a");
        assertFalse(filter.shouldNotFilter(scoped));
    }

    @Test
    void hopContextIsBoundDuringChainAndCleared() throws Exception {
        Integer[] seen = {null};
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(
                req(PATH, now(), "ctx-nonce-0001", "2"),
                resp,
                (rq, rs) -> seen[0] = A2aHopContext.current());
        assertEquals(2, seen[0]);
        assertNull(A2aHopContext.current());
        assertEquals(3, callOutbound(2));
    }

    private static int callOutbound(int inbound) throws Exception {
        return A2aHopContext.callWith(inbound, A2aHopContext::outboundHop);
    }

    @Test
    void signerRoundTripAndPeerLookup() {
        String sig = A2aMessageSigner.sign(SECRET, "POST", PATH, "1", "n", 0, new byte[] {1});
        assertTrue(A2aMessageSigner.verify(SECRET, sig, "post", PATH, "1", "n", 0, new byte[] {1}));
        assertFalse(A2aMessageSigner.verify(SECRET, sig, "POST", PATH, "1", "n", 0, new byte[] {2}));
        assertNull(props.secretForPeer("peer.example"));
        A2aMessageSecurityProperties.SignedPeer p = new A2aMessageSecurityProperties.SignedPeer();
        p.setHost("Peer.Example");
        p.setSecret("s");
        props.setSignedPeers(java.util.List.of(p));
        assertNotNull(props.secretForPeer("peer.example"));
    }
}
