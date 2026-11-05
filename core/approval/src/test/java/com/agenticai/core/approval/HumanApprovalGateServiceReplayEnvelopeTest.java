package com.agenticai.core.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Replay-envelope recording tests for {@link HumanApprovalGateService#recordToolCallWithOrigin} and
 * its overloads.
 */
class HumanApprovalGateServiceReplayEnvelopeTest {

    private ApprovalPolicyService policyService;
    private ApprovalRequestRepository requestRepository;
    private ApprovalActionExecutorRegistry executorRegistry;
    private ApprovalRequestRecorder recorder;
    private FeedbackService feedbackService;
    private HumanApprovalGateService gateService;

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long BASE_DELAY_SECONDS = 10;
    private static final long MAX_DELAY_SECONDS = 100;

    @BeforeEach
    void setUp() {
        policyService = mock(ApprovalPolicyService.class);
        requestRepository = mock(ApprovalRequestRepository.class);
        executorRegistry = mock(ApprovalActionExecutorRegistry.class);
        recorder = mock(ApprovalRequestRecorder.class);
        when(recorder.claimExecution(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        feedbackService = mock(FeedbackService.class);
        gateService =
                new HumanApprovalGateService(
                        policyService,
                        requestRepository,
                        executorRegistry,
                        recorder,
                        feedbackService,
                        new ObjectMapper(),
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
        com.fasterxml.jackson.databind.JsonNode payload =
                new ObjectMapper().readTree(payloadCaptor.getValue());
        assertEquals("send_message", payload.path("replay").path("toolName").asText());
        assertEquals(agentId.toString(), payload.path("replay").path("agentId").asText());
        assertEquals("sess-1", payload.path("replay").path("sessionId").asText());
        assertEquals("tenant-a", payload.path("replay").path("tenantId").asText());
        assertEquals("user-sub-1", payload.path("replay").path("subject").asText());
        // Valid-JSON arguments are embedded parsed, not double-encoded.
        assertEquals("x", payload.path("arguments").path("to").asText());
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
                new ObjectMapper()
                        .readTree(payloadCaptor.getValue())
                        .path("replay")
                        .path("toolDigest")
                        .asText());
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
                new ObjectMapper()
                        .readTree(payloadCaptor.getValue())
                        .path("replay")
                        .path("toolDigest")
                        .isMissingNode());
    }

    @Test
    void theScopedMemorySessionIdIsRecordedInTheEnvelopeWhenGiven() throws Exception {
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
                "abc123",
                "c1:scoped");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder)
                .recordNew(
                        anyString(), anyString(), payloadCaptor.capture(), anyString(), eq(false));
        com.fasterxml.jackson.databind.JsonNode replay =
                new ObjectMapper().readTree(payloadCaptor.getValue()).path("replay");
        assertEquals("c1:scoped", replay.path("memorySessionId").asText());
        assertEquals(
                "s", replay.path("sessionId").asText(), "the raw session id is still recorded");
    }

    @Test
    void noMemorySessionIdKeyIsWrittenWhenNoneIsGiven() throws Exception {
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
        assertTrue(
                new ObjectMapper()
                        .readTree(payloadCaptor.getValue())
                        .path("replay")
                        .path("memorySessionId")
                        .isMissingNode());
    }

    @Test
    void theGoalAndWhoAskedAreRecordedInTheEnvelopeSoTheTaskCanBeContinued() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID requested = UUID.randomUUID();
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.PENDING, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);

        gateService.recordToolCallWithOrigin(
                "mcp_tool.t",
                "t",
                "Tool call: t",
                "{\"a\":1}",
                "agent:" + agentId + ":session:s",
                agentId,
                "s",
                "abc123",
                new ToolCallOrigin("c1:scoped", "Send invoice INV-7731.", requested, "user-1"));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder)
                .recordNew(
                        anyString(), anyString(), payloadCaptor.capture(), anyString(), eq(false));
        com.fasterxml.jackson.databind.JsonNode payload =
                new ObjectMapper().readTree(payloadCaptor.getValue());
        com.fasterxml.jackson.databind.JsonNode replay = payload.path("replay");
        assertEquals("c1:scoped", replay.path("memorySessionId").asText());
        assertEquals("Send invoice INV-7731.", replay.path("goal").asText());
        assertEquals(requested.toString(), replay.path("requestedAgentId").asText());
        assertEquals("user-1", replay.path("subjectKey").asText());
        assertEquals(
                agentId.toString(),
                replay.path("agentId").asText(),
                "the routed agent is still the one that ran the tool");
        assertEquals(1, payload.path("arguments").path("a").asInt());
    }

    @Test
    void anOriginWithOnlyAHistoryIdRecordsNothingElse() throws Exception {
        UUID agentId = UUID.randomUUID();
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.PENDING, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);

        gateService.recordToolCallWithOrigin(
                "mcp_tool.t",
                "t",
                "Tool call: t",
                "{}",
                "agent:" + agentId + ":session:s",
                agentId,
                "s",
                "abc123",
                ToolCallOrigin.ofMemorySession("c1:scoped"));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder)
                .recordNew(
                        anyString(), anyString(), payloadCaptor.capture(), anyString(), eq(false));
        com.fasterxml.jackson.databind.JsonNode replay =
                new ObjectMapper().readTree(payloadCaptor.getValue()).path("replay");
        assertEquals("c1:scoped", replay.path("memorySessionId").asText());
        assertTrue(replay.path("goal").isMissingNode());
        assertTrue(replay.path("requestedAgentId").isMissingNode());
        assertTrue(replay.path("subjectKey").isMissingNode());
    }

    @Test
    void theRunScopedToolSelectionIsRecordedSoTheContinuationKeepsIt() throws Exception {
        UUID agentId = UUID.randomUUID();
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.PENDING, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);

        gateService.recordToolCallWithOrigin(
                "mcp_tool.t",
                "t",
                "Tool call: t",
                "{}",
                "agent:" + agentId + ":session:s",
                agentId,
                "s",
                "abc123",
                new ToolCallOrigin(
                        "c1:scoped", "goal", agentId, "user-1", java.util.List.of("t", "u")));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder)
                .recordNew(
                        anyString(), anyString(), payloadCaptor.capture(), anyString(), eq(false));
        com.fasterxml.jackson.databind.JsonNode names =
                new ObjectMapper()
                        .readTree(payloadCaptor.getValue())
                        .path("replay")
                        .path("runToolNames");
        assertTrue(names.isArray());
        assertEquals("[\"t\",\"u\"]", names.toString());
    }

    @Test
    void aTurnNarrowedToNoToolsRecordsAnEmptyListNotNothing() throws Exception {
        UUID agentId = UUID.randomUUID();
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.PENDING, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);

        gateService.recordToolCallWithOrigin(
                "mcp_tool.t",
                "t",
                "Tool call: t",
                "{}",
                "agent:" + agentId + ":session:s",
                agentId,
                "s",
                "abc123",
                new ToolCallOrigin("c1:scoped", "goal", agentId, "user-1", java.util.List.of()));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder)
                .recordNew(
                        anyString(), anyString(), payloadCaptor.capture(), anyString(), eq(false));
        com.fasterxml.jackson.databind.JsonNode names =
                new ObjectMapper()
                        .readTree(payloadCaptor.getValue())
                        .path("replay")
                        .path("runToolNames");
        assertTrue(names.isArray() && names.isEmpty());
    }

    @Test
    void aTurnThatWasNotNarrowedRecordsNoSelection() throws Exception {
        UUID agentId = UUID.randomUUID();
        ApprovalRequest request = mockRequest(UUID.randomUUID(), ApprovalStatus.PENDING, 0);
        when(recorder.recordNew(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(request);

        gateService.recordToolCallWithOrigin(
                "mcp_tool.t",
                "t",
                "Tool call: t",
                "{}",
                "agent:" + agentId + ":session:s",
                agentId,
                "s",
                "abc123",
                new ToolCallOrigin("c1:scoped", "goal", agentId, "user-1"));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder)
                .recordNew(
                        anyString(), anyString(), payloadCaptor.capture(), anyString(), eq(false));
        assertTrue(
                new ObjectMapper()
                        .readTree(payloadCaptor.getValue())
                        .path("replay")
                        .path("runToolNames")
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
        com.fasterxml.jackson.databind.JsonNode payload =
                new ObjectMapper().readTree(payloadCaptor.getValue());
        assertEquals("not json {", payload.path("argumentsRaw").asText());
        assertFalse(payload.has("arguments"));
        // No TenantContext bound in this test - the envelope simply omits
        // tenant/subject rather than failing to record.
        assertTrue(
                payload.path("replay").path("tenantId").isNull()
                        || payload.path("replay").path("tenantId").isMissingNode());
    }
}
