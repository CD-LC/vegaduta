package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The 2026-09-28 additions to {@link GuardedToolCallback}: the pre-dispatch
 * {@link ToolDispatchGate}, the {@code agent.tool.calls} timer / error
 * counter, and {@link ToolContext} forwarding.
 */
class GuardedToolCallbackGovernanceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String SESSION_ID = "session-1";
    private static final String TOOL_NAME = "send_invoice";
    private static final String TOOL_INPUT = "{\"to\":\"x\"}";
    private static final String TOOL_OUTPUT = "{\"ok\":true}";

    private ToolCallback delegate;
    private GuardrailService guardrailService;
    private ApprovalPolicyService approvalPolicyService;
    private HumanApprovalGateService approvalGateService;
    private SimpleMeterRegistry meters;

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
        meters = new SimpleMeterRegistry();
    }

    @AfterEach
    void cleanUp() {
        ApprovalReplayGrant.clear();
        GuardrailFailMode.set(GuardrailFailMode.CLOSED);
    }

    private GuardedToolCallback guarded(ToolDispatchGate gate, int maxSteps) {
        return new GuardedToolCallback(
            delegate, new AtomicInteger(0), maxSteps, guardrailService, approvalPolicyService, approvalGateService,
            AGENT_ID, SESSION_ID, null, false, null, null, ApprovalGateMode.POLICY_DEFAULT, null, null, null,
            gate, meters);
    }

    @Test
    void aDeniedToolIsNeverExecutedAndTheModelIsToldWhy() {
        GuardedToolCallback guarded = guarded(name -> ToolDispatchGate.Verdict.deny("it is quarantined."), 10);

        String result = guarded.call(TOOL_INPUT);

        verify(delegate, never()).call(anyString());
        assertTrue(result.contains("it is quarantined."), result);
        assertEquals(1, meters.timer(GuardedToolCallback.CALL_TIMER, "tool", TOOL_NAME, "outcome", "blocked").count());
    }

    @Test
    void requireApprovalRoutesThroughTheExistingApprovalGateEvenWhenPolicySaysNo() {
        when(approvalPolicyService.requiresApproval("mcp_tool." + TOOL_NAME)).thenReturn(false);
        ApprovalRequest pending = mock(ApprovalRequest.class);
        when(pending.getId()).thenReturn(UUID.randomUUID());
        when(approvalGateService.recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
            .thenReturn(pending);
        GuardedToolCallback guarded = guarded(name -> ToolDispatchGate.Verdict.requireApproval("irreversible"), 10);

        String result = guarded.call(TOOL_INPUT);

        verify(delegate, never()).call(anyString());
        assertTrue(result.contains("requires human approval"), result);
        assertEquals(1, meters.timer(GuardedToolCallback.CALL_TIMER,
            "tool", TOOL_NAME, "outcome", "approval_pending").count());
    }

    @Test
    void neverGateAgentsAreNotOverriddenByTheClassificationGate() {
        GuardedToolCallback guarded = new GuardedToolCallback(
            delegate, new AtomicInteger(0), 10, guardrailService, approvalPolicyService, approvalGateService,
            AGENT_ID, SESSION_ID, null, false, null, null, ApprovalGateMode.NEVER_GATE, null, null, null,
            name -> ToolDispatchGate.Verdict.requireApproval("irreversible"), meters);

        assertEquals(TOOL_OUTPUT, guarded.call(TOOL_INPUT));
        verify(approvalGateService, never()).recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void policyDefaultAgentsStillParkOnTheClassificationGate() {
        when(approvalPolicyService.requiresApproval("mcp_tool." + TOOL_NAME)).thenReturn(false);
        ApprovalRequest pending = mock(ApprovalRequest.class);
        when(pending.getId()).thenReturn(UUID.randomUUID());
        when(approvalGateService.recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
            .thenReturn(pending);
        GuardedToolCallback guarded = new GuardedToolCallback(
            delegate, new AtomicInteger(0), 10, guardrailService, approvalPolicyService, approvalGateService,
            AGENT_ID, SESSION_ID, null, false, null, null, ApprovalGateMode.POLICY_DEFAULT, null, null, null,
            name -> ToolDispatchGate.Verdict.requireApproval("irreversible"), meters);

        assertTrue(guarded.call(TOOL_INPUT).contains("requires human approval"));
        verify(delegate, never()).call(anyString());
    }

    @Test
    void aGateThatThrowsFailsClosedByDefault() {
        GuardedToolCallback guarded = guarded(name -> {
            throw new IllegalStateException("db down");
        }, 10);

        String result = guarded.call(TOOL_INPUT);

        verify(delegate, never()).call(anyString());
        assertTrue(result.contains("was not executed"), result);
        assertTrue(result.contains("could not be checked"), result);
    }

    @Test
    void aGateThatThrowsFailsOpenWhenConfiguredOpen() {
        GuardrailFailMode.set(GuardrailFailMode.OPEN);
        GuardedToolCallback guarded = guarded(name -> {
            throw new IllegalStateException("db down");
        }, 10);

        assertEquals(TOOL_OUTPUT, guarded.call(TOOL_INPUT));
        assertEquals(1, meters.timer(GuardedToolCallback.CALL_TIMER, "tool", TOOL_NAME, "outcome", "success").count());
    }

    @Test
    void aMissingGateIsNotAFailureAndStillAllowsTheCall() {
        GuardedToolCallback guarded = guarded(null, 10);

        assertEquals(TOOL_OUTPUT, guarded.call(TOOL_INPUT));
    }

    @Test
    void aNullGateAndNullMetricsBehaveExactlyAsBefore() {
        GuardedToolCallback guarded = new GuardedToolCallback(
            delegate, new AtomicInteger(0), 10, guardrailService, approvalPolicyService, approvalGateService,
            AGENT_ID, SESSION_ID);

        assertEquals(TOOL_OUTPUT, guarded.call(TOOL_INPUT));
    }

    @Test
    void aThrowingToolIsTimedAsAnErrorAndCounted() {
        when(delegate.call(TOOL_INPUT)).thenThrow(new IllegalArgumentException("bad"));
        GuardedToolCallback guarded = guarded(null, 10);

        assertThrows(IllegalArgumentException.class, () -> guarded.call(TOOL_INPUT));
        assertEquals(1, meters.timer(GuardedToolCallback.CALL_TIMER, "tool", TOOL_NAME, "outcome", "error").count());
        assertEquals(1.0, meters.counter(GuardedToolCallback.ERROR_COUNTER,
            "tool", TOOL_NAME, "exception", "IllegalArgumentException").count());
    }

    @Test
    void theStepLimitIsStillAHardStopAndIsTaggedAsSuch() {
        GuardedToolCallback guarded = guarded(null, 0);

        assertThrows(ToolStepLimitExceededException.class, () -> guarded.call(TOOL_INPUT));
        assertEquals(1, meters.timer(GuardedToolCallback.CALL_TIMER,
            "tool", TOOL_NAME, "outcome", "step_limit").count());
    }

    @Test
    void theToolContextReachesTheWrappedTool() {
        ToolContext context = new ToolContext(Map.of("k", "v"));
        when(delegate.call(TOOL_INPUT, context)).thenReturn(TOOL_OUTPUT);
        GuardedToolCallback guarded = guarded(null, 10);

        assertEquals(TOOL_OUTPUT, guarded.call(TOOL_INPUT, context));
        verify(delegate).call(TOOL_INPUT, context);
        verify(delegate, never()).call(TOOL_INPUT);
    }
}
