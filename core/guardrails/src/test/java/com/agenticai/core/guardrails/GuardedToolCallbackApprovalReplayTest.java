package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The approval gate's replay handshake, from {@link GuardedToolCallback}'s
 * side: a gated call without a grant records a PENDING request (with the
 * replay-capable 7-arg overload, so the recorded row can actually be
 * re-run later) and never executes; a same-thread {@link
 * ApprovalReplayGrant} for the exact action type lets exactly ONE call
 * through; and a call the policy doesn't gate never touches (or burns) a
 * grant at all.
 */
class GuardedToolCallbackApprovalReplayTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String SESSION_ID = "session-1";
    private static final String TOOL_NAME = "send_message";
    private static final String ACTION_TYPE = "mcp_tool." + TOOL_NAME;
    private static final String TOOL_INPUT = "{\"to\":\"x\"}";
    private static final String TOOL_OUTPUT = "{\"ok\":true}";

    private ToolCallback delegate;
    private GuardrailService guardrailService;
    private ApprovalPolicyService approvalPolicyService;
    private HumanApprovalGateService approvalGateService;
    private GuardedToolCallback guarded;

    @BeforeEach
    void setUp() {
        delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(TOOL_NAME);
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(TOOL_INPUT)).thenReturn(TOOL_OUTPUT);

        guardrailService = mock(GuardrailService.class);
        when(guardrailService.sanitizeToolOutput(anyString(), anyString(), any()))
            .thenAnswer(invocation -> invocation.getArgument(1));

        approvalPolicyService = mock(ApprovalPolicyService.class);
        approvalGateService = mock(HumanApprovalGateService.class);

        guarded = new GuardedToolCallback(
            delegate, new AtomicInteger(0), 10, guardrailService,
            approvalPolicyService, approvalGateService, AGENT_ID, SESSION_ID
        );
    }

    @AfterEach
    void cleanUpThreadLocal() {
        ApprovalReplayGrant.clear();
    }

    private ApprovalRequest pendingRequest(UUID id) {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(id);
        return request;
    }

    @Test
    void gatedCallWithoutGrantRecordsReplayCapablePendingRequestAndNeverExecutes() {
        when(approvalPolicyService.requiresApproval(ACTION_TYPE)).thenReturn(true);
        UUID requestId = UUID.randomUUID();
        // Built BEFORE the when(): stubbing a fresh mock inside thenReturn's
        // argument list is Mockito's unfinished-stubbing trap.
        ApprovalRequest pending = pendingRequest(requestId);
        when(approvalGateService.recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
            .thenReturn(pending);

        String result = guarded.call(TOOL_INPUT);

        verify(delegate, never()).call(anyString());
        // The 7-arg replay-capable overload, with everything the later
        // replay needs - not the legacy raw-payload one.
        verify(approvalGateService).recordToolCallForApproval(
            eq(ACTION_TYPE), eq(TOOL_NAME), eq("Tool call: " + TOOL_NAME), eq(TOOL_INPUT),
            eq("agent:" + AGENT_ID + ":session:" + SESSION_ID), eq(AGENT_ID), eq(SESSION_ID),
            org.mockito.ArgumentMatchers.notNull(), any());
        assertTrue(result.contains(requestId.toString()));
        assertTrue(result.contains("NOT executed"));
    }

    @Test
    void grantForTheExactActionTypeLetsExactlyOneGatedCallThrough() {
        when(approvalPolicyService.requiresApproval(ACTION_TYPE)).thenReturn(true);
        UUID requestId = UUID.randomUUID();
        ApprovalRequest pending = pendingRequest(requestId);
        when(approvalGateService.recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
            .thenReturn(pending);

        ApprovalReplayGrant.grant(ACTION_TYPE);
        String firstResult = guarded.call(TOOL_INPUT);
        assertEquals(TOOL_OUTPUT, firstResult);
        verify(delegate).call(TOOL_INPUT);

        // Single use: the grant was consumed by the first call, so an
        // identical second call re-enters the normal gate.
        String secondResult = guarded.call(TOOL_INPUT);
        assertTrue(secondResult.contains("NOT executed"));
    }

    @Test
    void grantForADifferentActionTypeDoesNotBypassTheGate() {
        when(approvalPolicyService.requiresApproval(ACTION_TYPE)).thenReturn(true);
        ApprovalRequest pending = pendingRequest(UUID.randomUUID());
        when(approvalGateService.recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
            .thenReturn(pending);

        ApprovalReplayGrant.grant("mcp_tool.some_other_tool");
        String result = guarded.call(TOOL_INPUT);

        verify(delegate, never()).call(anyString());
        assertTrue(result.contains("NOT executed"));
        // The non-matching grant survives for the call it was issued for.
        assertTrue(ApprovalReplayGrant.consume("mcp_tool.some_other_tool"));
    }

    @Test
    void ungatedCallNeverConsumesAGrant() {
        when(approvalPolicyService.requiresApproval(ACTION_TYPE)).thenReturn(false);

        ApprovalReplayGrant.grant(ACTION_TYPE);
        String result = guarded.call(TOOL_INPUT);

        assertEquals(TOOL_OUTPUT, result);
        // The grant check only runs when the policy gates the call - an
        // ungated call must not burn a grant issued for a replay elsewhere
        // on this thread.
        assertTrue(ApprovalReplayGrant.consume(ACTION_TYPE));
    }
}
