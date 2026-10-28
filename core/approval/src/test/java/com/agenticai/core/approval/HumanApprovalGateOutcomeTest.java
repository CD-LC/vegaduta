package com.agenticai.core.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.feedback.FeedbackService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The gate tells the conversation that asked for an action how the action ended ({@link
 * ApprovalOutcomePort}), whichever executor ran it, and records an action a tool gated ITSELF on with
 * the conversation it came from ({@link HumanApprovalGateService#evaluateInTurn}).
 */
class HumanApprovalGateOutcomeTest {

    private static final String ACTION = "codeintel.workspace.run";

    private ApprovalPolicyService policyService;
    private ApprovalActionExecutorRegistry registry;
    private ApprovalRequestRecorder recorder;
    private ApprovalRequestRepository requestRepository;
    private ApprovalOutcomePort port;
    private ApprovalActionExecutor executor;
    private HumanApprovalGateService gate;

    private static final class Retryable extends RuntimeException implements RetryableActionFailure {
        Retryable(String message) {
            super(message);
        }
    }

    @BeforeEach
    void setUp() {
        policyService = mock(ApprovalPolicyService.class);
        requestRepository = mock(ApprovalRequestRepository.class);
        registry = mock(ApprovalActionExecutorRegistry.class);
        recorder = mock(ApprovalRequestRecorder.class);
        when(recorder.claimExecution(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        port = mock(ApprovalOutcomePort.class);
        executor = mock(ApprovalActionExecutor.class);
        when(registry.find(ACTION)).thenReturn(Optional.of(executor));
        gate = new HumanApprovalGateService(
            policyService, requestRepository, registry, recorder, mock(FeedbackService.class), new ObjectMapper(), 2, 10, 100);
        gate.setOutcomePort(port);
    }

    @AfterEach
    void clear() {
        ApprovalReplayGrant.clear();
    }

    private ApprovalRequest request(ApprovalStatus status, int retryCount, String result) {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(UUID.randomUUID());
        when(request.getStatus()).thenReturn(status);
        when(request.getActionType()).thenReturn(ACTION);
        when(request.getRetryCount()).thenReturn(retryCount);
        when(request.getExecutionResult()).thenReturn(result);
        when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
        when(recorder.recordApproved(eq(request), anyString())).thenReturn(request);
        when(recorder.recordRejected(eq(request), anyString(), any())).thenReturn(request);
        when(recorder.recordExpired(eq(request), any())).thenReturn(request);
        return request;
    }

    @Test
    void anExecutedActionIsReportedCompletedWithItsRecordedOutput() {
        ApprovalRequest request = request(ApprovalStatus.PENDING, 0, "exit 0");

        gate.approve(request.getId(), "admin");

        verify(executor).execute(request);
        verify(port).afterDecision(request, ApprovalOutcomePort.Outcome.COMPLETED, "exit 0");
    }

    @Test
    void aFailureThatWillNotBeRetriedIsReportedFailedWithItsMessage() {
        ApprovalRequest request = request(ApprovalStatus.PENDING, 0, null);
        org.mockito.Mockito.doThrow(new IllegalStateException("code-intel said no")).when(executor).execute(request);

        assertThrows(IllegalStateException.class, () -> gate.approve(request.getId(), "admin"));

        verify(port).afterDecision(request, ApprovalOutcomePort.Outcome.FAILED, "code-intel said no");
    }

    @Test
    void aFailureThatWillBeRetriedIsNotReportedYet() {
        ApprovalRequest request = request(ApprovalStatus.PENDING, 0, null);
        org.mockito.Mockito.doThrow(new Retryable("blip")).when(executor).execute(request);

        assertThrows(Retryable.class, () -> gate.approve(request.getId(), "admin"));

        verify(port, never()).afterDecision(any(), any(), any());
    }

    @Test
    void theLastFailedAttemptIsReportedBecauseTheCallIsOver() {
        ApprovalRequest request = request(ApprovalStatus.PENDING, 2, null);
        org.mockito.Mockito.doThrow(new Retryable("still down")).when(executor).execute(request);

        assertThrows(Retryable.class, () -> gate.approve(request.getId(), "admin"));

        verify(recorder).recordDeadLettered(request, "still down");
        verify(port).afterDecision(request, ApprovalOutcomePort.Outcome.FAILED, "still down");
    }

    @Test
    void aRetrySucceedingLaterIsReportedCompleted() {
        ApprovalRequest request = request(ApprovalStatus.EXECUTION_FAILED, 1, "ok now");

        gate.retryExecution(request);

        verify(port).afterDecision(request, ApprovalOutcomePort.Outcome.COMPLETED, "ok now");
    }

    @Test
    void aRejectionAndAnExpiryAreBothReportedDeniedWithTheirReason() {
        ApprovalRequest rejected = request(ApprovalStatus.PENDING, 0, null);
        ApprovalRequest expired = request(ApprovalStatus.PENDING, 0, null);

        gate.reject(rejected.getId(), "admin", "not now");
        gate.expire(expired.getId(), "nobody decided in time");

        verify(executor, never()).execute(any());
        verify(port).afterDecision(rejected, ApprovalOutcomePort.Outcome.DENIED, "not now");
        verify(port).afterDecision(expired, ApprovalOutcomePort.Outcome.DENIED, "nobody decided in time");
    }

    @Test
    void anExecutorThatReportsItselfIsNotReportedTwice() {
        when(executor.reportsOutcomeItself()).thenReturn(true);
        ApprovalRequest approved = request(ApprovalStatus.PENDING, 0, "done");
        ApprovalRequest rejected = request(ApprovalStatus.PENDING, 0, null);

        gate.approve(approved.getId(), "admin");
        gate.reject(rejected.getId(), "admin", "no");

        verify(port, never()).afterDecision(any(), any(), any());
    }

    @Test
    void aPortThatThrowsNeverChangesTheDecision() {
        ApprovalRequest request = request(ApprovalStatus.PENDING, 0, "done");
        when(port.afterDecision(any(), any(), any())).thenThrow(new IllegalStateException("redis down"));

        gate.approve(request.getId(), "admin");

        verify(executor).execute(request);
    }

    @Test
    void withNoPortTheGateBehavesAsBefore() {
        gate.setOutcomePort(null);
        ApprovalRequest request = request(ApprovalStatus.PENDING, 0, "done");

        gate.approve(request.getId(), "admin");

        verify(executor).execute(request);
    }

    // ------------------------------------------------------------------ evaluateInTurn

    private ApprovalRequest recorded() {
        ApprovalRequest saved = mock(ApprovalRequest.class);
        when(saved.getId()).thenReturn(UUID.randomUUID());
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean())).thenReturn(saved);
        return saved;
    }

    @Test
    void outsideAToolCallItIsExactlyEvaluate() {
        when(policyService.requiresApproval(ACTION)).thenReturn(true);
        recorded();

        ApprovalEvaluation result = gate.evaluateInTurn(ACTION, "run_in_code_workspace", "s", "{\"command\":\"x\"}", "user-1");

        assertFalse(result.approved());
        verify(recorder).recordNew(ACTION, "s", "{\"command\":\"x\"}", "user-1", false);
    }

    @Test
    void insideAToolCallThePendingRequestCarriesTheConversationNextToItsOwnFields() throws Exception {
        UUID agent = UUID.randomUUID();
        UUID requested = UUID.randomUUID();
        when(policyService.requiresApproval(ACTION)).thenReturn(true);
        ApprovalRequest saved = recorded();
        ToolCallOrigin origin = new ToolCallOrigin("c1:scoped", "Run the tests", requested, "subject-key");

        ApprovalEvaluation result;
        try (ToolCallScope.Scope ignored = ToolCallScope.open("run_in_code_workspace", agent, "sess-1", null, origin)) {
            result = gate.evaluateInTurn(ACTION, "ignored", "summary", "{\"command\":\"x\",\"adminOnly\":true}", "user-1");
        }

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(recorder).recordNew(eq(ACTION), eq("summary"), payload.capture(), eq("user-1"), eq(false));
        JsonNode json = new ObjectMapper().readTree(payload.getValue());
        JsonNode replay = json.path("replay");
        assertFalse(result.approved());
        assertEquals(saved.getId(), result.pendingRequestId());
        assertEquals("x", json.path("command").asText(), "the action's own fields stay at the top level");
        assertTrue(json.path("adminOnly").asBoolean(), "so the admin-only rule reads them unchanged");
        assertEquals("run_in_code_workspace", replay.path("toolName").asText());
        assertEquals(agent.toString(), replay.path("agentId").asText());
        assertEquals(requested.toString(), replay.path("requestedAgentId").asText());
        assertEquals("sess-1", replay.path("sessionId").asText());
        assertEquals("c1:scoped", replay.path("memorySessionId").asText());
        assertEquals("Run the tests", replay.path("goal").asText());
        assertEquals("subject-key", replay.path("subjectKey").asText());
    }

    @Test
    void anAutoApprovedActionIsRecordedPlainBecauseItRunsInsideTheTurnThatAsked() {
        when(policyService.requiresApproval(ACTION)).thenReturn(false);
        ApprovalRequest saved = recorded();
        when(saved.getStatus()).thenReturn(ApprovalStatus.APPROVED);
        when(registry.find(ACTION)).thenReturn(Optional.empty());
        ToolCallOrigin origin = new ToolCallOrigin("c1:scoped", "goal", UUID.randomUUID(), "k");

        ApprovalEvaluation result;
        try (ToolCallScope.Scope ignored = ToolCallScope.open("t", UUID.randomUUID(), "s", null, origin)) {
            result = gate.evaluateInTurn(ACTION, "t", "summary", "{\"a\":1}", "user-1");
        }

        assertTrue(result.approved());
        verify(recorder).recordNew(ACTION, "summary", "{\"a\":1}", "user-1", true);
    }

    @Test
    void aPayloadThatIsNotAnObjectIsRecordedPlainRatherThanFailTheCall() {
        when(policyService.requiresApproval(ACTION)).thenReturn(true);
        recorded();
        ToolCallOrigin origin = new ToolCallOrigin("c1:scoped", "goal", UUID.randomUUID(), "k");

        try (ToolCallScope.Scope ignored = ToolCallScope.open("t", UUID.randomUUID(), "s", null, origin)) {
            assertNotNull(gate.evaluateInTurn(ACTION, "t", "summary", "[1,2]", "user-1"));
        }

        verify(recorder).recordNew(ACTION, "summary", "[1,2]", "user-1", false);
    }
}
