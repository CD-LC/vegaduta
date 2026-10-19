package com.agenticai.core.agents.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.edge.EdgeTurnTelemetryController.EdgeTurnReportRequest;
import com.agenticai.core.rl.api.RlTurnRecorder;
import com.agenticai.core.rl.domain.RewardSource;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;

/**
 * {@code POST /api/edge/turns} - fire-and-forget edge-turn telemetry that
 * NEVER 401s/errors back to the caller (see the controller's own javadoc),
 * and only writes an {@link RlTurnRecorder} episode for a real Keycloak
 * session. Plain unit tests calling the controller method directly, same
 * convention as {@code EdgeToolExecutionControllerTest}.
 */
class EdgeTurnTelemetryControllerTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final Principal PRINCIPAL = () -> "keycloak-user-123";

    private AgentService agentService;
    private RlTurnRecorder recorder;
    private EdgeTurnRepository edgeTurnRepository;
    private com.agenticai.core.devices.DeviceAuthResolver deviceAuthResolver;
    private EdgeTurnTelemetryRateLimiter rateLimiter;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RlTurnRecorder> recorderProvider = mock(ObjectProvider.class);
    private EdgeTurnTelemetryController controller;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        recorder = mock(RlTurnRecorder.class);
        edgeTurnRepository = mock(EdgeTurnRepository.class);
        deviceAuthResolver = mock(com.agenticai.core.devices.DeviceAuthResolver.class);
        rateLimiter = mock(EdgeTurnTelemetryRateLimiter.class);
        when(rateLimiter.tryAcquire(any())).thenReturn(true);
        when(recorderProvider.getIfAvailable()).thenReturn(recorder);
        controller = new EdgeTurnTelemetryController(
            agentService, recorderProvider, edgeTurnRepository, deviceAuthResolver, rateLimiter);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.RUNNER)).thenReturn(mock(Agent.class));
        when(recorder.beginTurn(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());
    }

    private static EdgeTurnReportRequest request(String outcome) {
        return new EdgeTurnReportRequest(AGENT_ID, "agent-session", 1, "Llama-3.2-1B", outcome, "test-reason", 42L);
    }

    /** A normal-sized servlet request from a fixed test address. */
    private static MockHttpServletRequest httpRequest() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/edge/turns");
        servletRequest.setRemoteAddr("198.51.100.7");
        servletRequest.setContent(new byte[128]);
        return servletRequest;
    }

    @Test
    void missingAgentIdIsRejected() {
        var req = new EdgeTurnReportRequest(null, "s", 1, "m", "success", null, null);
        ResponseEntity<Void> response = controller.report(req, null, PRINCIPAL, httpRequest());
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void missingOutcomeIsRejected() {
        var req = new EdgeTurnReportRequest(AGENT_ID, "s", 1, "m", null, null, null);
        ResponseEntity<Void> response = controller.report(req, null, PRINCIPAL, httpRequest());
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void anonymousCallerIsAcceptedButNeverWritesAnRlEpisodeOrAnEdgeTurnRow() {
        Principal anonymous = new AnonymousAuthenticationToken(
            "key", "anonymousUser", java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        ResponseEntity<Void> response = controller.report(request("success"), null, anonymous, httpRequest());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(recorder, never()).beginTurn(any(), any(), any(), any(), any());
        verify(agentService, never()).requireAccess(any(), any());
        // Wave 2 (EDGE-B): the anonymous /try surface stays log-only — no
        // tenant schema exists to meter it into.
        verify(edgeTurnRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // 2026-08-07: X-Device-Token fallthrough (DeviceAuthResolver pattern,
    // mirroring EdgeToolExecutionController#withCallerSubject) — desktop
    // daemons upgrade from log-only to persisted EdgeTurn rows.
    // ------------------------------------------------------------------

    @Test
    void deviceTokenCallerPersistsAndRecordsLikeAKeycloakOne() {
        // DeviceAuthResolver.run resolves the token and executes the action
        // with TenantContext bound (mocked here by just invoking it).
        when(deviceAuthResolver.run(eq("dev-token"), any())).thenAnswer(invocation -> {
            java.util.function.Function<com.agenticai.core.devices.MobileDevice, Object> action =
                invocation.getArgument(1);
            return action.apply(mock(com.agenticai.core.devices.MobileDevice.class));
        });

        ResponseEntity<Void> response = controller.report(request("success"), "dev-token", null, httpRequest());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(agentService).requireAccess(AGENT_ID, PermissionLevel.RUNNER);
        verify(edgeTurnRepository).save(any());
        verify(recorder).endTurn(any(), eq(true));
    }

    @Test
    void invalidDeviceTokenDegradesToLogOnlyAndStillAnswers202() {
        when(deviceAuthResolver.run(eq("bad-token"), any()))
            .thenThrow(new RuntimeException("Unknown device token."));

        ResponseEntity<Void> response = controller.report(request("success"), "bad-token", null, httpRequest());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(edgeTurnRepository, never()).save(any());
        verify(recorder, never()).beginTurn(any(), any(), any(), any(), any());
    }

    @Test
    void keycloakSessionWinsOverAPresentDeviceToken() {
        // Mirrors withCallerSubject's precedence: the device token is only
        // consulted when there is no Keycloak session.
        ResponseEntity<Void> response = controller.report(request("success"), "dev-token", PRINCIPAL, httpRequest());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(deviceAuthResolver, never()).run(any(), any());
        verify(edgeTurnRepository).save(any());
    }

    // ------------------------------------------------------------------
    // Edge Wave 2 — EDGE-B: edge_turn persistence (V191)
    // ------------------------------------------------------------------

    @Test
    void authenticatedReportPersistsAnEdgeTurnRow() {
        ResponseEntity<Void> response = controller.report(request("fallback"), null, PRINCIPAL, httpRequest());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        org.mockito.ArgumentCaptor<EdgeTurn> captor = org.mockito.ArgumentCaptor.forClass(EdgeTurn.class);
        verify(edgeTurnRepository).save(captor.capture());
        EdgeTurn saved = captor.getValue();
        assertEquals(AGENT_ID, saved.getAgentId());
        assertEquals("agent-session", saved.getSessionKey());
        assertEquals(Short.valueOf((short) 1), saved.getTier());
        assertEquals("Llama-3.2-1B", saved.getModelId());
        assertEquals("fallback", saved.getOutcome());
        assertEquals("test-reason", saved.getReason());
        assertEquals(Long.valueOf(42L), saved.getLatencyMs());
    }

    @Test
    void oversizedFreeTextIsTruncatedNotRejected() {
        String longReason = "x".repeat(2_000);
        var req = new EdgeTurnReportRequest(AGENT_ID, "s", 0, "m", "error", longReason, null);

        ResponseEntity<Void> response = controller.report(req, null, PRINCIPAL, httpRequest());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        org.mockito.ArgumentCaptor<EdgeTurn> captor = org.mockito.ArgumentCaptor.forClass(EdgeTurn.class);
        verify(edgeTurnRepository).save(captor.capture());
        assertEquals(512, captor.getValue().getReason().length());
    }

    @Test
    void persistenceFailureNeverBlocksTheRlPathOrTheCaller() {
        when(edgeTurnRepository.save(any())).thenThrow(new RuntimeException("db down"));

        ResponseEntity<Void> response = controller.report(request("success"), null, PRINCIPAL, httpRequest());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        // The RL episode still records — each sink fails independently.
        verify(recorder).endTurn(any(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void accessDeniedSkipsPersistenceToo() {
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.RUNNER))
            .thenThrow(new ResourceAccessDeniedException());

        ResponseEntity<Void> response = controller.report(request("success"), null, PRINCIPAL, httpRequest());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(edgeTurnRepository, never()).save(any());
    }

    @Test
    void authenticatedSuccessRecordsTaskSuccessAndCostRewards() {
        UUID episodeId = UUID.randomUUID();
        when(recorder.beginTurn(eq(AGENT_ID), eq("agent-session"), any(), isNull(), eq(java.util.Map.of()))).thenReturn(episodeId);

        ResponseEntity<Void> response = controller.report(request("success"), null, PRINCIPAL, httpRequest());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(agentService).requireAccess(AGENT_ID, PermissionLevel.RUNNER);
        verify(recorder).recordStep(eq(episodeId), eq("user"), eq("message"), isNull(), isNull(), isNull(), isNull());
        verify(recorder).recordStep(eq(episodeId), eq("assistant"), eq("answer"), isNull(), isNull(), isNull(), eq(42L));
        verify(recorder).endTurn(episodeId, true);
        verify(recorder).addReward(episodeId, RewardSource.TASK_SUCCESS, 1.0, null);
        verify(recorder).addReward(episodeId, RewardSource.COST, 1.0, null);
        verify(recorder).computeReward(episodeId);
    }

    @Test
    void authenticatedFallbackRecordsZeroTaskSuccessAndNoCostReward() {
        controller.report(request("fallback"), null, PRINCIPAL, httpRequest());

        verify(recorder).addReward(any(), eq(RewardSource.TASK_SUCCESS), eq(0.0), eq((Double) null));
        verify(recorder, never()).addReward(any(), eq(RewardSource.COST), any(Double.class), any());
    }

    @Test
    void agentAccessDeniedNeverPropagatesToTheCaller() {
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.RUNNER))
            .thenThrow(new ResourceAccessDeniedException());

        ResponseEntity<Void> response = controller.report(request("success"), null, PRINCIPAL, httpRequest());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(recorder, never()).beginTurn(any(), any(), any(), any(), any());
    }

    @Test
    void noRlPolicyWiredIsANoOp() {
        when(recorderProvider.getIfAvailable()).thenReturn(null);

        ResponseEntity<Void> response = controller.report(request("success"), null, PRINCIPAL, httpRequest());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
    }

    // ------------------------------------------------------------------
    // Pentest L7 (2026-08-27): per-IP rate limit + payload size cap on
    // this permitAll'd beacon. Both drop silently with a 202 - the
    // fire-and-forget contract must not leak whether the lid was hit.
    // ------------------------------------------------------------------

    @Test
    void overTheRateLimitIsDroppedSilentlyWithA202() {
        when(rateLimiter.tryAcquire("198.51.100.7")).thenReturn(false);

        ResponseEntity<Void> response = controller.report(request("success"), null, PRINCIPAL, httpRequest());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        // Nothing downstream runs for a dropped report - no access check,
        // no EdgeTurn row, no RL episode.
        verify(agentService, never()).requireAccess(any(), any());
        verify(edgeTurnRepository, never()).save(any());
        verify(recorder, never()).beginTurn(any(), any(), any(), any(), any());
    }

    @Test
    void oversizedPayloadIsDroppedBeforeTheRateLimiterAndBothSinks() {
        MockHttpServletRequest oversized = httpRequest();
        oversized.setContent(new byte[(int) EdgeTurnTelemetryController.MAX_BODY_BYTES + 1]);

        ResponseEntity<Void> response = controller.report(request("success"), null, PRINCIPAL, oversized);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(rateLimiter, never()).tryAcquire(any());
        verify(agentService, never()).requireAccess(any(), any());
        verify(edgeTurnRepository, never()).save(any());
        verify(recorder, never()).beginTurn(any(), any(), any(), any(), any());
    }

    @Test
    void withinTheLimitTheReportCountsAgainstTheCallersAddress() {
        controller.report(request("success"), null, PRINCIPAL, httpRequest());

        verify(rateLimiter).tryAcquire("198.51.100.7");
        verify(edgeTurnRepository).save(any());
    }
}
