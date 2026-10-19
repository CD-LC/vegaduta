package com.agenticai.core.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * JAVA-005: the interceptor read a request attribute nothing sets, so it logged a WARN per request and never ran
 * a check. It now takes the tenant and user from {@link TenantContext} via {@link FraudTenantIds} (the same
 * mapping the fraud admin API uses). These tests also pin the safety posture: the request-rate / auth-attempt
 * heuristics only observe unless {@code fraud-detection.enforce=true}, while explicit blacklist entries are
 * always enforced.
 */
class FraudDetectionInterceptorTest {

    private static final String SCHEMA = "tenant_acme";
    private static final UUID TENANT_ID = FraudTenantIds.toUuid(SCHEMA);
    private static final UUID USER_ID = UUID.randomUUID();

    private AbuseDetectionService abuse;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        abuse = mock(AbuseDetectionService.class);
        when(abuse.analyzeRequest(any(), any())).thenReturn(FraudDecision.ALLOW);
        request = new MockHttpServletRequest("GET", "/api/agents");
        request.setRemoteAddr("203.0.113.9");
        response = new MockHttpServletResponse();
        TenantContext.set(TenantContext.of(SCHEMA, USER_ID.toString(), Set.of()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private FraudDetectionInterceptor interceptor(boolean enforce) {
        return new FraudDetectionInterceptor(abuse, new ObjectMapper(), enforce);
    }

    @Test
    void theTenantComesFromTenantContextSoTheChecksActuallyRun() throws Exception {
        assertTrue(interceptor(false).preHandle(request, response, new Object()));

        ArgumentCaptor<ClientContext> ctx = ArgumentCaptor.forClass(ClientContext.class);
        verify(abuse).analyzeRequest(ctx.capture(), any());
        assertEquals(TENANT_ID, ctx.getValue().tenantId);
        assertEquals(USER_ID, ctx.getValue().userId);
    }

    @Test
    void aRequestWithNoTenantIsSkippedWithoutAnalysis() throws Exception {
        TenantContext.clear();
        assertTrue(interceptor(true).preHandle(request, response, new Object()));
        verify(abuse, never()).analyzeRequest(any(), any());
    }

    @Test
    void aNonUuidSubjectMapsToAStableNameBasedUserId() throws Exception {
        TenantContext.set(TenantContext.of(SCHEMA, "demo:session-1", Set.of()));
        interceptor(false).preHandle(request, response, new Object());
        ArgumentCaptor<ClientContext> ctx = ArgumentCaptor.forClass(ClientContext.class);
        verify(abuse).analyzeRequest(ctx.capture(), any());
        assertEquals(FraudTenantIds.toUuid("demo:session-1"), ctx.getValue().userId);
    }

    // ---- enforcement posture ----

    @Test
    void aHeuristicBlockOnlyObservesByDefaultSoLegitimateTrafficIsNeverRejected() throws Exception {
        when(abuse.analyzeRequest(any(), any())).thenReturn(FraudDecision.BLOCK);
        assertTrue(interceptor(false).preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void aHeuristicChallengeOnlyObservesByDefault() throws Exception {
        when(abuse.analyzeRequest(any(), any())).thenReturn(FraudDecision.CHALLENGE);
        assertTrue(interceptor(false).preHandle(request, response, new Object()));
        verify(abuse, never()).generateChallengeNonce(any(), any(), any());
    }

    @Test
    void whenEnforcedABlockAnswers403() throws Exception {
        when(abuse.analyzeRequest(any(), any())).thenReturn(FraudDecision.BLOCK);
        assertFalse(interceptor(true).preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
        assertEquals("BLOCKED", response.getHeader("X-Fraud-Status"));
    }

    @Test
    void whenEnforcedAChallengeIssuesANonce() throws Exception {
        when(abuse.analyzeRequest(any(), any())).thenReturn(FraudDecision.CHALLENGE);
        when(abuse.generateChallengeNonce(any(), any(), any())).thenReturn("nonce-1");
        assertFalse(interceptor(true).preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
        assertEquals("nonce-1", response.getHeader("X-Challenge-Nonce"));
    }

    @Test
    void anExplicitIpBlacklistEntryIsEnforcedEvenInObserveMode() throws Exception {
        when(abuse.analyzeRequest(any(), any())).thenReturn(FraudDecision.BLOCK);
        when(abuse.isIpBlacklisted(any(), any())).thenReturn(true);
        assertFalse(interceptor(false).preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void anAllowDecisionPassesThrough() throws Exception {
        assertTrue(interceptor(true).preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }
}
