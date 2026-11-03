package com.agenticai.core.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Phase 0 package G: the client identity used for fraud decisions is the container-resolved peer address only
 * (X-Forwarded-For / X-Real-IP are client-controlled and a hostname there used to trigger a DNS lookup), and the
 * challenge flow is closed: a signed, short-lived, single-use pass downgrades CHALLENGE to ALLOW.
 */
class FraudClientIpAndChallengePassTest {

    private static final String SCHEMA = "tenant_acme";
    private static final UUID TENANT = FraudTenantIds.toUuid(SCHEMA);
    private static final String KEY = "test-only-key-not-a-secret";

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    // ---- client IP ----

    private static MockHttpServletRequest req(String remote) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/agents");
        r.setRemoteAddr(remote);
        return r;
    }

    @Test
    void spoofedForwardedForDoesNotChangeTheIdentity() {
        MockHttpServletRequest r = req("203.0.113.9");
        r.addHeader("X-Forwarded-For", "198.51.100.77, 10.0.0.1");
        r.addHeader("X-Real-IP", "198.51.100.88");
        ClientContext ctx = ClientContext.fromRequest(r, TENANT, null);
        assertEquals("203.0.113.9", ctx.ip.getHostAddress());
        assertEquals("203.0.113.9", ctx.sessionId);
    }

    @Test
    void aHostnameInForwardedForIsIgnoredAndNeverResolved() {
        // Old code resolved "localhost" through InetAddress.getByName and used 127.0.0.1 as the client.
        MockHttpServletRequest r = req("203.0.113.9");
        r.addHeader("X-Forwarded-For", "localhost");
        assertEquals("203.0.113.9", ClientContext.extractClientIp(r).getHostAddress());
    }

    @Test
    void hostnamesAndJunkAreNotParsedAsIps() {
        assertNull(ClientContext.parseLiteralIp("localhost"));
        assertNull(ClientContext.parseLiteralIp("evil.example.com"));
        assertNull(ClientContext.parseLiteralIp("add.bed"));
        assertNull(ClientContext.parseLiteralIp("999.1.1.1"));
        assertNull(ClientContext.parseLiteralIp(""));
        assertNull(ClientContext.parseLiteralIp(null));
        assertEquals("203.0.113.9", ClientContext.parseLiteralIp("203.0.113.9").getHostAddress());
        assertTrue(ClientContext.parseLiteralIp("[2001:db8::1]") instanceof java.net.Inet6Address);
        assertTrue(ClientContext.parseLiteralIp("fe80::1%eth0") instanceof java.net.Inet6Address);
    }

    @Test
    void theInterceptorAnalysesTheResolvedPeerNotASpoofedHeader() throws Exception {
        AbuseDetectionService abuse = mock(AbuseDetectionService.class);
        doReturn(FraudDecision.ALLOW).when(abuse).analyzeRequest(any(), any());
        TenantContext.set(TenantContext.of(SCHEMA, UUID.randomUUID().toString(), Set.of()));
        MockHttpServletRequest r = req("203.0.113.9");
        r.addHeader("X-Forwarded-For", "192.0.2.200");

        new FraudDetectionInterceptor(abuse, new ObjectMapper(), true)
            .preHandle(r, new MockHttpServletResponse(), new Object());

        ArgumentCaptor<ClientContext> ctx = ArgumentCaptor.forClass(ClientContext.class);
        verify(abuse).analyzeRequest(ctx.capture(), any());
        assertEquals("203.0.113.9", ctx.getValue().ip.getHostAddress());
    }

    // ---- challenge pass ----

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-03T10:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static InetAddress ip(String s) {
        return ClientContext.parseLiteralIp(s);
    }

    @Test
    void aValidPassIsAcceptedExactlyOnce() {
        ChallengePass cp = new ChallengePass(KEY, new MutableClock(), Duration.ofMinutes(5));
        String token = cp.issue(TENANT, ip("203.0.113.9"), "nonce-1");
        assertNotNull(token);
        assertTrue(cp.redeem(token, TENANT, ip("203.0.113.9")));
        assertFalse(cp.redeem(token, TENANT, ip("203.0.113.9")), "replay");
    }

    @Test
    void anExpiredPassIsRejected() {
        MutableClock clock = new MutableClock();
        ChallengePass cp = new ChallengePass(KEY, clock, Duration.ofMinutes(5));
        String token = cp.issue(TENANT, ip("203.0.113.9"), "nonce-1");
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        assertFalse(cp.redeem(token, TENANT, ip("203.0.113.9")));
    }

    @Test
    void forgedOrRebindedPassesAreRejected() {
        ChallengePass cp = new ChallengePass(KEY, new MutableClock(), Duration.ofMinutes(5));
        String token = cp.issue(TENANT, ip("203.0.113.9"), "nonce-1");
        // Wrong IP, wrong tenant.
        assertFalse(cp.redeem(token, TENANT, ip("203.0.113.10")));
        assertFalse(cp.redeem(token, UUID.randomUUID(), ip("203.0.113.9")));
        // Tampered signature, tampered payload, the old unsigned tenant.nonce.timestamp format, junk.
        assertFalse(cp.redeem(token.substring(0, token.length() - 2) + "AA", TENANT, ip("203.0.113.9")));
        assertFalse(cp.redeem("x" + token, TENANT, ip("203.0.113.9")));
        assertFalse(cp.redeem(TENANT.toString().substring(0, 8) + ".nonce123.1762164000000", TENANT, ip("203.0.113.9")));
        assertFalse(cp.redeem("", TENANT, ip("203.0.113.9")));
        assertFalse(cp.redeem(null, TENANT, ip("203.0.113.9")));
        // Signed with another key.
        String other = new ChallengePass("another-key", new MutableClock(), Duration.ofMinutes(5))
            .issue(TENANT, ip("203.0.113.9"), "nonce-2");
        assertFalse(cp.redeem(other, TENANT, ip("203.0.113.9")));
        // None of the failures consumed the genuine pass.
        assertTrue(cp.redeem(token, TENANT, ip("203.0.113.9")));
    }

    @Test
    void withoutAKeyNothingIsIssuedOrAccepted() {
        ChallengePass cp = new ChallengePass("", new MutableClock(), Duration.ofMinutes(5));
        assertNull(cp.issue(TENANT, ip("203.0.113.9"), "n"));
        assertFalse(cp.redeem("a.b", TENANT, ip("203.0.113.9")));
    }

    // ---- interceptor end to end ----

    private AbuseDetectionService challengingService() {
        AbuseDetectionService real = new AbuseDetectionService(
            mock(IpBlacklistRepository.class), mock(UserBlacklistRepository.class),
            mock(ChallengeNonceRepository.class), mock(HCaptchaClient.class), mock(AbuseEventRecorder.class));
        ReflectionTestUtils.setField(real, "encryptionKey", KEY);
        AbuseDetectionService spy = spy(real);
        doReturn(FraudDecision.CHALLENGE).when(spy).analyzeRequest(any(), any());
        doReturn("nonce-x").when(spy).generateChallengeNonce(any(), any(), any());
        return spy;
    }

    @Test
    void theInterceptorLetsAValidPassThroughOnceAndRechallengesTheReplay() throws Exception {
        AbuseDetectionService abuse = challengingService();
        TenantContext.set(TenantContext.of(SCHEMA, UUID.randomUUID().toString(), Set.of()));
        String pass = abuse.issueChallengePass(TENANT, ip("203.0.113.9"), "nonce-1");
        FraudDetectionInterceptor interceptor = new FraudDetectionInterceptor(abuse, new ObjectMapper(), true);

        MockHttpServletRequest first = req("203.0.113.9");
        first.addHeader("X-Challenge-Pass", pass);
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(first, res1, new Object()));
        assertEquals(Boolean.TRUE, first.getAttribute("fraudChallengePassed"));

        MockHttpServletRequest replay = req("203.0.113.9");
        replay.addHeader("X-Challenge-Pass", pass);
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(replay, res2, new Object()));
        assertEquals(403, res2.getStatus());
        assertNull(replay.getAttribute("fraudChallengePassed"));
    }

    @Test
    void aForgedPassHeaderDoesNotDowngradeTheChallenge() throws Exception {
        AbuseDetectionService abuse = challengingService();
        TenantContext.set(TenantContext.of(SCHEMA, UUID.randomUUID().toString(), Set.of()));
        MockHttpServletRequest r = req("203.0.113.9");
        r.addHeader("X-Challenge-Pass", "anything-at-all");
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertFalse(new FraudDetectionInterceptor(abuse, new ObjectMapper(), true).preHandle(r, res, new Object()));
        assertEquals(403, res.getStatus());
        assertEquals("HCAPTCHA", res.getHeader("X-Fraud-Challenge"));
    }
}
