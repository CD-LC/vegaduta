package com.agenticai.core.approval;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.feedback.FeedbackService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link HumanApprovalGateService} is, per its own javadoc, "the single entry point every action
 * that might need human sign-off goes through" - every channel send, every gated tool call, every
 * sensitive workflow node routes through here. Had zero test coverage before this suite despite
 * being the platform's central safety gate, with real documented history of a subtle bug (an
 * earlier version silently never executed auto-approved actions at all - see this class's javadoc)
 * that only a behavioral test like the ones below would have caught immediately.
 *
 * <p>{@link ApprovalRequest} is mocked rather than constructed for cases needing a specific {@code
 * id}/{@code status}/{@code retryCount} - those fields are normally populated by JPA on persist,
 * which never happens in a pure unit test, so controlling them via stubbing is simpler and more
 * direct than reflection.
 */
class HumanApprovalGateServiceTest {

    private ApprovalPolicyService policyService;
    private ApprovalRequestRepository requestRepository;
    private ApprovalActionExecutorRegistry executorRegistry;
    private ApprovalRequestRecorder recorder;
    private FeedbackService feedbackService;
    private HumanApprovalGateService gateService;

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long BASE_DELAY_SECONDS = 10;
    private static final long MAX_DELAY_SECONDS = 100;

    private static final class RetryableTestFailure extends RuntimeException
            implements RetryableActionFailure {
        RetryableTestFailure(String message) {
            super(message);
        }
    }

    @BeforeEach
    void setUp() {
        policyService = mock(ApprovalPolicyService.class);
        requestRepository = mock(ApprovalRequestRepository.class);
        executorRegistry = mock(ApprovalActionExecutorRegistry.class);
        recorder = mock(ApprovalRequestRecorder.class);
        feedbackService = mock(FeedbackService.class);
        gateService =
                new HumanApprovalGateService(
                        policyService,
                        requestRepository,
                        executorRegistry,
                        recorder,
                        feedbackService,
                        com.agenticai.core.json.JsonMappers.plain(),
                        MAX_RETRY_ATTEMPTS,
                        BASE_DELAY_SECONDS,
                        MAX_DELAY_SECONDS);
    }

    private ApprovalRequest mockRequest(UUID id, ApprovalStatus status, int retryCount) {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(id);
        when(request.getStatus()).thenReturn(status);
        when(request.getActionType()).thenReturn("channel.whatsapp.send");
        when(request.getRetryCount()).thenReturn(retryCount);
        return request;
    }

    // --- evaluate(): auto-approve vs pending routing ---------------------------

    @Test
    void evaluateAutoApprovesWhenPolicyDoesNotRequireApproval() {
        when(policyService.requiresApproval("channel.whatsapp.send")).thenReturn(false);
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.APPROVED, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);
        when(executorRegistry.find(anyString())).thenReturn(Optional.empty());

        ApprovalEvaluation result =
                gateService.evaluate("channel.whatsapp.send", "summary", "{}", "user-1");

        assertTrue(result.approved());
        assertNull(result.pendingRequestId());
        verify(recorder).recordNew("channel.whatsapp.send", "summary", "{}", "user-1", true);
    }

    @Test
    void evaluateCreatesPendingRequestWhenPolicyRequiresApproval() {
        UUID pendingId = UUID.randomUUID();
        when(policyService.requiresApproval("channel.whatsapp.send")).thenReturn(true);
        ApprovalRequest request = mockRequest(pendingId, ApprovalStatus.PENDING, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);

        ApprovalEvaluation result =
                gateService.evaluate("channel.whatsapp.send", "summary", "{}", "user-1");

        assertFalse(result.approved());
        assertEquals(pendingId, result.pendingRequestId());
        verify(recorder).recordNew("channel.whatsapp.send", "summary", "{}", "user-1", false);
        // The pending path must never touch the executor - execution is
        // deferred entirely to a later approve() call.
        verify(executorRegistry, never()).find(any());
    }

    @Test
    void evaluateAutoApprovedRunsTheRegisteredExecutor() {
        when(policyService.requiresApproval(any())).thenReturn(false);
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.APPROVED, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);
        ApprovalActionExecutor executor = mock(ApprovalActionExecutor.class);
        when(executorRegistry.find("channel.whatsapp.send")).thenReturn(Optional.of(executor));

        gateService.evaluate("channel.whatsapp.send", "summary", "{}", "user-1");

        verify(executor, times(1)).execute(request);
        verify(recorder, never()).recordExecutionFailed(any(), any(), any());
    }

    @Test
    void evaluateAutoApprovedWithNoRegisteredExecutorRecordsFailureButDoesNotThrow() {
        // Real historical bug this class's javadoc documents: an
        // auto-approved action must never be silently treated as done when
        // nothing actually performs it.
        when(policyService.requiresApproval(any())).thenReturn(false);
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.APPROVED, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);
        when(executorRegistry.find(any())).thenReturn(Optional.empty());

        ApprovalEvaluation result =
                assertDoesNotThrow(
                        () ->
                                gateService.evaluate(
                                        "channel.whatsapp.send", "summary", "{}", "user-1"));

        assertTrue(result.approved());
        verify(recorder).recordNoExecutorRegistered(request, "user-1");
    }

    // --- executor failure: non-retryable, retryable-under-cap, dead-lettered ---

    @Test
    void nonRetryableExecutorFailureIsRecordedAndPropagatedToTheCaller() {
        when(policyService.requiresApproval(any())).thenReturn(false);
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.APPROVED, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);
        ApprovalActionExecutor executor = mock(ApprovalActionExecutor.class);
        RuntimeException boom = new RuntimeException("channel API returned 401");
        org.mockito.Mockito.doThrow(boom).when(executor).execute(request);
        when(executorRegistry.find(any())).thenReturn(Optional.of(executor));

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                gateService.evaluate(
                                        "channel.whatsapp.send", "summary", "{}", "user-1"));

        assertEquals("channel API returned 401", thrown.getMessage());
        verify(recorder).recordExecutionFailed(request, "user-1", "channel API returned 401");
        verify(recorder, never()).recordRetryScheduled(any(), any(), any());
        verify(recorder, never()).recordDeadLettered(any(), any());
    }

    @Test
    void retryableExecutorFailureUnderCapSchedulesRetryAndStillPropagates() {
        when(policyService.requiresApproval(any())).thenReturn(false);
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.APPROVED, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);
        ApprovalActionExecutor executor = mock(ApprovalActionExecutor.class);
        RetryableTestFailure transientFailure = new RetryableTestFailure("timed out");
        org.mockito.Mockito.doThrow(transientFailure).when(executor).execute(request);
        when(executorRegistry.find(any())).thenReturn(Optional.of(executor));

        assertThrows(
                RetryableTestFailure.class,
                () -> gateService.evaluate("channel.whatsapp.send", "summary", "{}", "user-1"));

        verify(recorder).recordRetryScheduled(eq(request), eq("timed out"), any(Instant.class));
        verify(recorder, never()).recordExecutionFailed(any(), any(), any());
        verify(recorder, never()).recordDeadLettered(any(), any());
    }

    @Test
    void retryableExecutorFailureAtAttemptCapIsDeadLetteredInsteadOfRetried() {
        when(policyService.requiresApproval(any())).thenReturn(false);
        // retryCount already at the cap - this attempt must dead-letter, not schedule another
        // retry.
        ApprovalRequest request =
                mockRequest(UUID.randomUUID(), ApprovalStatus.APPROVED, MAX_RETRY_ATTEMPTS);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);
        ApprovalActionExecutor executor = mock(ApprovalActionExecutor.class);
        RetryableTestFailure transientFailure = new RetryableTestFailure("still failing");
        org.mockito.Mockito.doThrow(transientFailure).when(executor).execute(request);
        when(executorRegistry.find(any())).thenReturn(Optional.of(executor));

        assertThrows(
                RetryableTestFailure.class,
                () -> gateService.evaluate("channel.whatsapp.send", "summary", "{}", "user-1"));

        verify(recorder).recordDeadLettered(request, "still failing");
        verify(recorder, never()).recordRetryScheduled(any(), any(), any());
    }

    @Test
    void backoffDelayGrowsExponentiallyAndIsCappedAtMaxDelay() {
        // Drives recordFailureAndMaybeScheduleRetry indirectly through a
        // real retryable failure at increasing retryCounts, one case per
        // call - policyService.requiresApproval(...) needs no stub here,
        // Mockito's default (false) for an unstubbed boolean method is
        // exactly what the auto-approve path needs.

        // attempt 1 (retryCount 0 -> 1): base * 2^0 = 10s
        assertBackoffWithinExpectedWindow(0, BASE_DELAY_SECONDS);
        // attempt 2 (retryCount 1 -> 2): base * 2^1 = 20s
        assertBackoffWithinExpectedWindow(1, BASE_DELAY_SECONDS * 2);
        // attempt 3 (retryCount 2 -> 3): base * 2^2 = 40s
        assertBackoffWithinExpectedWindow(2, BASE_DELAY_SECONDS * 4);
        // attempt 5 (retryCount 4 -> 5, the last attempt before the retry
        // cap dead-letters instead - see the dead-letter test below):
        // uncapped would be base * 2^4 = 160s, which exceeds
        // maxDelaySeconds (100s) and must clamp to exactly 100s.
        assertBackoffWithinExpectedWindow(4, MAX_DELAY_SECONDS);
    }

    private void assertBackoffWithinExpectedWindow(
            int currentRetryCount, long expectedDelaySeconds) {
        ApprovalRequest request =
                mockRequest(UUID.randomUUID(), ApprovalStatus.APPROVED, currentRetryCount);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);
        ApprovalActionExecutor executor = mock(ApprovalActionExecutor.class);
        org.mockito.Mockito.doThrow(new RetryableTestFailure("x")).when(executor).execute(request);
        when(executorRegistry.find(any())).thenReturn(Optional.of(executor));

        Instant before = Instant.now();
        assertThrows(
                RetryableTestFailure.class,
                () -> gateService.evaluate("channel.whatsapp.send", "s", "{}", "u"));

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(recorder).recordRetryScheduled(eq(request), eq("x"), captor.capture());
        long actualDelaySeconds = captor.getValue().getEpochSecond() - before.getEpochSecond();
        // A couple of seconds of tolerance for wall-clock time elapsed
        // between "before" and the code's own Instant.now() call.
        assertTrue(
                Math.abs(actualDelaySeconds - expectedDelaySeconds) <= 2,
                "expected ~" + expectedDelaySeconds + "s, got " + actualDelaySeconds + "s");
    }

    // --- approve() ---------------------------------------------------------

    @Test
    void approveOnPendingRequestMarksApprovedAndRunsExecutor() {
        UUID id = UUID.randomUUID();
        ApprovalRequest pending = mockRequest(id, ApprovalStatus.PENDING, 0);
        ApprovalRequest approved = mockRequest(id, ApprovalStatus.APPROVED, 0);
        when(requestRepository.findById(id)).thenReturn(Optional.of(pending));
        when(recorder.recordApproved(pending, "admin-1")).thenReturn(approved);
        ApprovalActionExecutor executor = mock(ApprovalActionExecutor.class);
        when(executorRegistry.find(any())).thenReturn(Optional.of(executor));

        ApprovalRequest result = gateService.approve(id, "admin-1");

        assertEquals(approved, result);
        verify(executor).execute(approved);
    }

    @Test
    void approveOnNonexistentRequestThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(requestRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(
                ApprovalRequestNotFoundException.class, () -> gateService.approve(id, "admin-1"));
    }

    @Test
    void approveOnAlreadyDecidedRequestThrows() {
        UUID id = UUID.randomUUID();
        ApprovalRequest alreadyApproved = mockRequest(id, ApprovalStatus.APPROVED, 0);
        when(requestRepository.findById(id)).thenReturn(Optional.of(alreadyApproved));

        assertThrows(
                ApprovalRequestAlreadyDecidedException.class,
                () -> gateService.approve(id, "admin-1"));
    }

    @Test
    void approveOnRejectedRequestThrows() {
        UUID id = UUID.randomUUID();
        ApprovalRequest alreadyRejected = mockRequest(id, ApprovalStatus.REJECTED, 0);
        when(requestRepository.findById(id)).thenReturn(Optional.of(alreadyRejected));

        assertThrows(
                ApprovalRequestAlreadyDecidedException.class,
                () -> gateService.approve(id, "admin-1"));
    }

    // --- reject() ------------------------------------------------------------

    @Test
    void rejectOnPendingRequestMarksRejectedAndNeverRunsExecutor() {
        UUID id = UUID.randomUUID();
        ApprovalRequest pending = mockRequest(id, ApprovalStatus.PENDING, 0);
        ApprovalRequest rejected = mockRequest(id, ApprovalStatus.REJECTED, 0);
        when(requestRepository.findById(id)).thenReturn(Optional.of(pending));
        when(recorder.recordRejected(pending, "admin-1", "not needed")).thenReturn(rejected);
        ApprovalActionExecutor executor = mock(ApprovalActionExecutor.class);
        when(executorRegistry.find(any())).thenReturn(Optional.of(executor));

        ApprovalRequest result = gateService.reject(id, "admin-1", "not needed");

        assertEquals(rejected, result);
        // Rejecting must never execute the action - this is the whole point
        // of a human saying no. (The registry IS consulted now, but only for
        // the onRejected cleanup hook - see the tests below.)
        verify(executor, never()).execute(any());
        verify(executor).onRejected(rejected, "admin-1", "not needed");
    }

    @Test
    void rejectWithNoRegisteredExecutorStillJustRecordsTheRejection() {
        UUID id = UUID.randomUUID();
        ApprovalRequest pending = mockRequest(id, ApprovalStatus.PENDING, 0);
        ApprovalRequest rejected = mockRequest(id, ApprovalStatus.REJECTED, 0);
        when(requestRepository.findById(id)).thenReturn(Optional.of(pending));
        when(recorder.recordRejected(pending, "admin-1", "no")).thenReturn(rejected);
        when(executorRegistry.find(any())).thenReturn(Optional.empty());

        ApprovalRequest result = assertDoesNotThrow(() -> gateService.reject(id, "admin-1", "no"));

        assertEquals(rejected, result);
    }

    @Test
    void rejectionCleanupFailureNeverFailsTheRejectionItself() {
        // The onRejected hook is best-effort by contract - a workflow-run
        // bookkeeping failure must never un-decide a human's "no" or turn
        // the reject call into a 5xx.
        UUID id = UUID.randomUUID();
        ApprovalRequest pending = mockRequest(id, ApprovalStatus.PENDING, 0);
        ApprovalRequest rejected = mockRequest(id, ApprovalStatus.REJECTED, 0);
        when(requestRepository.findById(id)).thenReturn(Optional.of(pending));
        when(recorder.recordRejected(pending, "admin-1", "no")).thenReturn(rejected);
        ApprovalActionExecutor executor = mock(ApprovalActionExecutor.class);
        org.mockito.Mockito.doThrow(new RuntimeException("run table unavailable"))
                .when(executor)
                .onRejected(any(), any(), any());
        when(executorRegistry.find(any())).thenReturn(Optional.of(executor));

        ApprovalRequest result = assertDoesNotThrow(() -> gateService.reject(id, "admin-1", "no"));

        assertEquals(rejected, result);
    }

    @Test
    void rejectOnNonexistentRequestThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(requestRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(
                ApprovalRequestNotFoundException.class,
                () -> gateService.reject(id, "admin-1", "no"));
    }

    @Test
    void rejectOnAlreadyDecidedRequestThrows() {
        UUID id = UUID.randomUUID();
        ApprovalRequest deadLettered = mockRequest(id, ApprovalStatus.DEAD_LETTERED, 3);
        when(requestRepository.findById(id)).thenReturn(Optional.of(deadLettered));

        assertThrows(
                ApprovalRequestAlreadyDecidedException.class,
                () -> gateService.reject(id, "admin-1", "no"));
    }

    // --- approval decision -> RL reward loop --------------------------------
    // (workbench hardening: an approve/reject decision on a workflow
    // Approval node used to only gate execution and otherwise vanish - it
    // never reached core/rl. See HumanApprovalGateService#recordRlFeedback.)

    private ApprovalRequest mockWorkflowNodeRequest(UUID id, ApprovalStatus status, String runId) {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(id);
        when(request.getStatus()).thenReturn(status);
        when(request.getActionType()).thenReturn("workflow.node.approval");
        when(request.getPayloadJson())
                .thenReturn(
                        "{\"workflowName\":\"My Flow\",\"runId\":\""
                                + runId
                                + "\",\"nodeId\":\"n1\",\"adminOnly\":false}");
        when(request.getRetryCount()).thenReturn(0);
        return request;
    }

    @Test
    void approvingAWorkflowNodeRequestRecordsAPositiveRlRewardForThatRunsSession() {
        UUID id = UUID.randomUUID();
        String runId = UUID.randomUUID().toString();
        ApprovalRequest pending = mockWorkflowNodeRequest(id, ApprovalStatus.PENDING, runId);
        ApprovalRequest approved = mockWorkflowNodeRequest(id, ApprovalStatus.APPROVED, runId);
        when(requestRepository.findById(id)).thenReturn(Optional.of(pending));
        when(recorder.recordApproved(pending, "admin-1")).thenReturn(approved);

        gateService.approve(id, "admin-1");

        verify(feedbackService).recordApprovalDecision(runId, true);
    }

    @Test
    void rejectingAWorkflowNodeRequestRecordsANegativeRlRewardForThatRunsSession() {
        UUID id = UUID.randomUUID();
        String runId = UUID.randomUUID().toString();
        ApprovalRequest pending = mockWorkflowNodeRequest(id, ApprovalStatus.PENDING, runId);
        ApprovalRequest rejected = mockWorkflowNodeRequest(id, ApprovalStatus.REJECTED, runId);
        when(requestRepository.findById(id)).thenReturn(Optional.of(pending));
        when(recorder.recordRejected(pending, "admin-1", "not good")).thenReturn(rejected);

        gateService.reject(id, "admin-1", "not good");

        verify(feedbackService).recordApprovalDecision(runId, false);
    }

    @Test
    void nonWorkflowNodeApprovalsNeverTouchTheRlFeedbackPipeline() {
        // mockRequest() defaults actionType to "channel.whatsapp.send" - a
        // channel send has no workflow run/session to attach a reward to,
        // and must not even attempt the lookup.
        UUID id = UUID.randomUUID();
        ApprovalRequest pending = mockRequest(id, ApprovalStatus.PENDING, 0);
        ApprovalRequest approved = mockRequest(id, ApprovalStatus.APPROVED, 0);
        when(requestRepository.findById(id)).thenReturn(Optional.of(pending));
        when(recorder.recordApproved(pending, "admin-1")).thenReturn(approved);

        gateService.approve(id, "admin-1");

        verify(feedbackService, never()).recordApprovalDecision(any(), anyBoolean());
    }

    @Test
    void rlFeedbackFailureNeverFailsTheApprovalDecisionItself() {
        UUID id = UUID.randomUUID();
        String runId = UUID.randomUUID().toString();
        ApprovalRequest pending = mockWorkflowNodeRequest(id, ApprovalStatus.PENDING, runId);
        ApprovalRequest approved = mockWorkflowNodeRequest(id, ApprovalStatus.APPROVED, runId);
        when(requestRepository.findById(id)).thenReturn(Optional.of(pending));
        when(recorder.recordApproved(pending, "admin-1")).thenReturn(approved);
        org.mockito.Mockito.doThrow(new RuntimeException("rl db down"))
                .when(feedbackService)
                .recordApprovalDecision(any(), anyBoolean());
        ApprovalActionExecutor executor = mock(ApprovalActionExecutor.class);
        when(executorRegistry.find(any())).thenReturn(Optional.of(executor));

        ApprovalRequest result = assertDoesNotThrow(() -> gateService.approve(id, "admin-1"));

        assertEquals(approved, result);
        verify(executor).execute(approved);
    }

    // --- recordToolCallForApproval() --------------------------------------

    @Test
    void recordToolCallForApprovalNeverRunsAnExecutorRegardlessOfPolicy() {
        // Deliberately bypasses evaluate()'s executor-lookup path entirely -
        // NOTHING may execute at record time; the replay executor only ever
        // runs later, via approve()'s executor lookup.
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.PENDING, 0);
        when(recorder.recordNew("mcp.tool.dangerous_action", "summary", "{}", "user-1", false))
                .thenReturn(request);

        ApprovalRequest result =
                gateService.recordToolCallForApproval(
                        "mcp.tool.dangerous_action", "summary", "{}", "user-1");

        assertEquals(request, result);
        verify(recorder).recordNew("mcp.tool.dangerous_action", "summary", "{}", "user-1", false);
        verify(executorRegistry, never()).find(any());
    }

    @Test
    void replayCapableOverloadRecordsEnvelopeWithTenantAndSubjectFromCurrentContext()
            throws Exception {
        UUID agentId = UUID.randomUUID();
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.PENDING, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);
        com.agenticai.core.security.TenantContext.set(
                com.agenticai.core.security.TenantContext.of(
                        "tenant-a", "user-sub-1", java.util.Set.of()));
        try {
            gateService.recordToolCallForApproval(
                    "mcp_tool.send_message",
                    "send_message",
                    "Tool call: send_message",
                    "{\"to\":\"x\"}",
                    "agent:" + agentId + ":session:sess-1",
                    agentId,
                    "sess-1");
        } finally {
            com.agenticai.core.security.TenantContext.clear();
        }

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder)
                .recordNew(
                        eq("mcp_tool.send_message"),
                        eq("Tool call: send_message"),
                        payloadCaptor.capture(),
                        eq("agent:" + agentId + ":session:sess-1"),
                        eq(false));
        tools.jackson.databind.JsonNode payload =
                com.agenticai.core.json.JsonMappers.plain().readTree(payloadCaptor.getValue());
        assertEquals("send_message", payload.path("replay").path("toolName").asString());
        assertEquals(agentId.toString(), payload.path("replay").path("agentId").asString());
        assertEquals("sess-1", payload.path("replay").path("sessionId").asString());
        assertEquals("tenant-a", payload.path("replay").path("tenantId").asString());
        assertEquals("user-sub-1", payload.path("replay").path("subject").asString());
        // Valid-JSON arguments are embedded parsed, not double-encoded.
        assertEquals("x", payload.path("arguments").path("to").asString());
        verify(executorRegistry, never()).find(any());
    }

    @Test
    void toolDigestIsRecordedInTheEnvelopeWhenGiven() throws Exception {
        UUID agentId = UUID.randomUUID();
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.PENDING, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);

        gateService.recordToolCallForApproval(
                "mcp_tool.t",
                "t",
                "Tool call: t",
                "{}",
                "agent:" + agentId + ":session:s",
                agentId,
                "s",
                "abc123");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder)
                .recordNew(
                        anyString(), anyString(), payloadCaptor.capture(), anyString(), eq(false));
        assertEquals(
                "abc123",
                com.agenticai.core.json.JsonMappers.plain()
                        .readTree(payloadCaptor.getValue())
                        .path("replay")
                        .path("toolDigest")
                        .asString());
    }

    @Test
    void noToolDigestKeyIsWrittenWhenNoneIsGiven() throws Exception {
        UUID agentId = UUID.randomUUID();
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.PENDING, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);

        gateService.recordToolCallForApproval(
                "mcp_tool.t",
                "t",
                "Tool call: t",
                "{}",
                "agent:" + agentId + ":session:s",
                agentId,
                "s");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder)
                .recordNew(
                        anyString(), anyString(), payloadCaptor.capture(), anyString(), eq(false));
        assertTrue(
                com.agenticai.core.json.JsonMappers.plain()
                        .readTree(payloadCaptor.getValue())
                        .path("replay")
                        .path("toolDigest")
                        .isMissingNode());
    }

    @Test
    void replayCapableOverloadPreservesUnparseableArgumentsVerbatim() throws Exception {
        UUID agentId = UUID.randomUUID();
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.PENDING, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);

        gateService.recordToolCallForApproval(
                "mcp_tool.t",
                "t",
                "Tool call: t",
                "not json {",
                "agent:" + agentId + ":session:s",
                agentId,
                "s");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder)
                .recordNew(
                        anyString(), anyString(), payloadCaptor.capture(), anyString(), eq(false));
        tools.jackson.databind.JsonNode payload =
                com.agenticai.core.json.JsonMappers.plain().readTree(payloadCaptor.getValue());
        assertEquals("not json {", payload.path("argumentsRaw").asString());
        assertFalse(payload.has("arguments"));
        // No TenantContext bound in this test - the envelope simply omits
        // tenant/subject rather than failing to record.
        assertTrue(
                payload.path("replay").path("tenantId").isNull()
                        || payload.path("replay").path("tenantId").isMissingNode());
    }

    // --- retryExecution() (RetryFailedActionsJob's only caller) ----------------

    @Test
    void retryExecutionSuccessRecordsRetrySucceeded() {
        ApprovalRequest request =
                mockRequest(UUID.randomUUID(), ApprovalStatus.EXECUTION_FAILED, 2);
        ApprovalActionExecutor executor = mock(ApprovalActionExecutor.class);
        when(executorRegistry.find(any())).thenReturn(Optional.of(executor));

        assertDoesNotThrow(() -> gateService.retryExecution(request));

        verify(executor).execute(request);
        verify(recorder).recordRetrySucceeded(request);
    }

    @Test
    void retryExecutionFailureNeverPropagatesToTheCaller() {
        // Unlike evaluate()/approve(), a scheduled job has no HTTP caller
        // waiting - one request's failure must never abort the whole batch.
        ApprovalRequest request =
                mockRequest(UUID.randomUUID(), ApprovalStatus.EXECUTION_FAILED, 1);
        ApprovalActionExecutor executor = mock(ApprovalActionExecutor.class);
        org.mockito.Mockito.doThrow(new RuntimeException("still broken"))
                .when(executor)
                .execute(request);
        when(executorRegistry.find(any())).thenReturn(Optional.of(executor));

        assertDoesNotThrow(() -> gateService.retryExecution(request));

        verify(recorder)
                .recordExecutionFailed(
                        request, ApprovalRequestRecorder.RETRY_JOB_ACTOR, "still broken");
    }

    @Test
    void retryExecutionWithNoRegisteredExecutorRecordsAndReturnsWithoutThrowing() {
        ApprovalRequest request =
                mockRequest(UUID.randomUUID(), ApprovalStatus.EXECUTION_FAILED, 1);
        when(executorRegistry.find(any())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> gateService.retryExecution(request));

        verify(recorder)
                .recordNoExecutorRegistered(request, ApprovalRequestRecorder.RETRY_JOB_ACTOR);
    }
}
