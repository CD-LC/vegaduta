package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The real {@link GuardedToolCallback} says WHAT it answered with, as a typed {@link ToolResultMarker.Kind}, so a
 * consumer never has to guess from the wording. The cases that matter are the ones the wording cannot carry: an
 * operator-configured park text, and an ordinary tool output that happens to start like a refusal.
 */
class GuardedToolCallbackResultMarkerTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private ApprovalPolicyService policy;
    private HumanApprovalGateService gate;
    private GuardrailService guardrails;

    @BeforeEach
    void setUp() {
        policy = mock(ApprovalPolicyService.class);
        when(policy.requiresApproval("mcp_tool.send_invoice")).thenReturn(true);
        gate = mock(HumanApprovalGateService.class);
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(UUID.randomUUID());
        when(gate.recordToolCallForApproval(
                        anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(request);
        guardrails = mock(GuardrailService.class);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
    }

    private static ToolCallback tool(String name, String output) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(cb.getToolDefinition()).thenReturn(def);
        when(cb.call(anyString())).thenReturn(output);
        return cb;
    }

    private GuardedToolCallback guard(ToolCallback cb, int maxSteps, ToolDispatchGate dispatch) {
        return new GuardedToolCallback(
                cb,
                new AtomicInteger(),
                maxSteps,
                guardrails,
                policy,
                gate,
                AGENT_ID,
                "sess-1",
                null,
                false,
                null,
                null,
                ApprovalGateMode.POLICY_DEFAULT,
                null,
                null,
                null,
                dispatch,
                null)
                .withTurnApproval(new TurnApprovalState("c1:abc"));
    }

    @Test
    void aConfiguredParkTextWithNoRefusalWordingIsStillMarkedParked() {
        ApprovalParkProperties texts = new ApprovalParkProperties();
        texts.setAwaitingMessage("custom park text for {tool}");

        String result = guard(tool("send_invoice", "ran"), 10, null).withApprovalParkTexts(texts).call("{}");

        assertEquals("custom park text for send_invoice", result);
        assertEquals(ToolResultMarker.Kind.PARKED, ToolResultMarker.kindOf(result).orElseThrow());
        assertTrue(ToolResultMarker.isPlatformAnswer(result));
    }

    @Test
    void theDefaultParkTextIsMarkedParked() {
        String result = guard(tool("send_invoice", "ran"), 10, null).call("{}");

        assertEquals(ToolResultMarker.Kind.PARKED, ToolResultMarker.kindOf(result).orElseThrow());
    }

    @Test
    void aDispatchGateDenialIsMarkedBlocked() {
        ToolDispatchGate deny = tool -> ToolDispatchGate.Verdict.deny("quarantined");

        String result = guard(tool("lookup", "found"), 10, deny).call("{}");

        assertEquals(ToolResultMarker.Kind.BLOCKED, ToolResultMarker.kindOf(result).orElseThrow());
    }

    @Test
    void thePastTheCeilingAnswerIsMarkedBudgetExhausted() {
        GuardedToolCallback guarded = guard(tool("lookup", "found"), 1, null);
        assertEquals("found", guarded.call("{}"));

        String second = guarded.call("{}");

        assertEquals(ToolResultMarker.Kind.BUDGET_EXHAUSTED, ToolResultMarker.kindOf(second).orElseThrow());
    }

    @Test
    void anOutputWithheldByAContentCheckIsMarkedWithheld() {
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any()))
                .thenThrow(new GuardrailViolationException("lookup", "unsafe"));

        String result = guard(tool("lookup", "found"), 10, null).call("{}");

        assertEquals(ToolResultMarker.Kind.WITHHELD, ToolResultMarker.kindOf(result).orElseThrow());
    }

    @Test
    void aToolsOwnOutputIsNeverMarked_evenWhenItStartsLikeARefusal() {
        String lookalike = "This tool call ('x') was not executed: that is what the remote service printed";

        String result = guard(tool("lookup", lookalike), 10, null).call("{}");

        assertEquals(lookalike, result);
        assertTrue(ToolResultMarker.kindOf(result).isEmpty(), "the guard did not answer, the tool did");
        // The old wording prefix is still accepted for a producer that never registered itself.
        assertTrue(ToolResultMarker.isPlatformAnswer(result));
        assertFalse(ToolResultMarker.kindOf("{\"status\":\"sent\"}").isPresent());
    }
}
