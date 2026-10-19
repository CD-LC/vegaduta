package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Once one call in a turn is parked for approval, no other tool of that turn runs - the model must
 * not carry on (and finish the task) with sibling or follow-up calls before the human has decided.
 */
class GuardedToolCallbackTurnPauseTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private ToolCallback tool(String name, String output) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(cb.getToolDefinition()).thenReturn(def);
        when(cb.call(anyString())).thenReturn(output);
        return cb;
    }

    private GuardedToolCallback guard(ToolCallback cb, AtomicInteger turn, ApprovalPolicyService policy, HumanApprovalGateService gate) {
        GuardrailService guardrails = mock(GuardrailService.class);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
        return new GuardedToolCallback(cb, turn, 10, guardrails, policy, gate, AGENT_ID, "s1");
    }

    @Test
    void aParkedCallStopsEveryOtherToolInTheSameTurn() {
        ApprovalPolicyService policy = mock(ApprovalPolicyService.class);
        when(policy.requiresApproval("mcp_tool.gated")).thenReturn(true);
        HumanApprovalGateService gate = mock(HumanApprovalGateService.class);
        ApprovalRequest pending = mock(ApprovalRequest.class);
        when(pending.getId()).thenReturn(UUID.randomUUID());
        when(gate.recordToolCallForApproval(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
            .thenReturn(pending);

        AtomicInteger turn = new AtomicInteger();
        ToolCallback gated = tool("gated", "gated-ran");
        ToolCallback other = tool("other", "other-ran");

        String parked = guard(gated, turn, policy, gate).call("{}");
        assertTrue(parked.contains("NOT executed"));
        assertTrue(parked.contains("STOP NOW"));

        String after = guard(other, turn, policy, gate).call("{}");
        assertTrue(after.contains("NOT executed"));
        assertTrue(after.contains("waiting for the user's approval"));
        verify(other, never()).call(anyString());
    }

    @Test
    void aDifferentTurnIsNotAffected() {
        ApprovalPolicyService policy = mock(ApprovalPolicyService.class);
        when(policy.requiresApproval("mcp_tool.gated")).thenReturn(true);
        HumanApprovalGateService gate = mock(HumanApprovalGateService.class);
        ApprovalRequest pending = mock(ApprovalRequest.class);
        when(pending.getId()).thenReturn(UUID.randomUUID());
        when(gate.recordToolCallForApproval(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
            .thenReturn(pending);

        guard(tool("gated", "x"), new AtomicInteger(), policy, gate).call("{}");

        assertEquals("other-ran", guard(tool("other", "other-ran"), new AtomicInteger(), policy, gate).call("{}"));
    }
}
