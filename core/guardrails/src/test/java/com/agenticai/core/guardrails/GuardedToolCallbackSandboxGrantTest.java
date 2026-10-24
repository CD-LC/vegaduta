package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The sandbox auto-approval of an eval turn, from {@link GuardedToolCallback}'s side. A gated call parks
 * (records a pending approval, never runs) unless the turn's approval state carries a {@link
 * SandboxApprovalGrants.Grant} that permits the tool; the grant skips only the park, counts what it let
 * through, and never covers a tool it excludes.
 */
class GuardedToolCallbackSandboxGrantTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String INPUT = "{\"to\":\"x\"}";
    private static final String OUTPUT = "{\"ok\":true}";

    private final ApprovalPolicyService policy = mock(ApprovalPolicyService.class);
    private final HumanApprovalGateService gate = mock(HumanApprovalGateService.class);
    private final GuardrailService guardrails = mock(GuardrailService.class);

    private GuardedToolCallback guardFor(String toolName, TurnApprovalState state) {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(toolName);
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(INPUT)).thenReturn(OUTPUT);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(policy.requiresApproval("mcp_tool." + toolName)).thenReturn(true);
        ApprovalRequest pending = mock(ApprovalRequest.class);
        when(pending.getId()).thenReturn(UUID.randomUUID());
        when(gate.recordToolCallForApproval(
                anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
            .thenReturn(pending);
        GuardedToolCallback guarded = new GuardedToolCallback(
            delegate, new AtomicInteger(0), 10, guardrails, policy, gate, AGENT_ID, "session-1");
        return guarded.withTurnApproval(state);
    }

    @Test
    void gatedCallWithoutAGrantParksAndNeverRuns() {
        GuardedToolCallback guarded = guardFor("send_message", new TurnApprovalState("scoped"));

        String result = guarded.call(INPUT);

        assertTrue(result.contains("NOT executed"), result);
        verify(gate).recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void gatedCallUnderAPermittingGrantRunsWithoutRecordingAnApproval() {
        TurnApprovalState state = new TurnApprovalState("scoped");
        try (SandboxApprovalGrants.Registration reg =
                 SandboxApprovalGrants.register("turn-1", "eval-auto-approve", List.of("local_shell"))) {
            state.enableSandboxGrant(reg.grant());
            GuardedToolCallback guarded = guardFor("send_message", state);

            String result = guarded.call(INPUT);

            assertEquals(OUTPUT, result);
            verify(gate, never()).recordToolCallForApproval(
                anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any());
            assertFalse(state.isParked(), "an auto-approved call must not park the turn");
            assertEquals(1, reg.grant().usedCount());
            assertEquals(List.of("send_message"), reg.grant().usedTools());
        }
    }

    @Test
    void anExcludedToolStillParksUnderTheGrant() {
        TurnApprovalState state = new TurnApprovalState("scoped");
        try (SandboxApprovalGrants.Registration reg =
                 SandboxApprovalGrants.register("turn-2", "eval-auto-approve", List.of("local_shell", "*payment*"))) {
            state.enableSandboxGrant(reg.grant());
            GuardedToolCallback guarded = guardFor("local_shell", state);

            String result = guarded.call(INPUT);

            assertTrue(result.contains("NOT executed"), result);
            assertTrue(state.isParked());
            assertEquals(0, reg.grant().usedCount());
        }
    }

    @Test
    void aGlobExclusionCoversEveryMatchingToolName() {
        try (SandboxApprovalGrants.Registration reg =
                 SandboxApprovalGrants.register("turn-3", "eval-auto-approve", List.of("*payment*", "*transfer*"))) {
            SandboxApprovalGrants.Grant grant = reg.grant();
            assertFalse(grant.permits("create_payment_link"));
            assertFalse(grant.permits("bank_Transfer_funds"));
            assertTrue(grant.permits("send_message"));
            assertFalse(grant.permits(null));
        }
    }

    @Test
    void theGrantIsRemovedWhenItsRegistrationCloses() {
        SandboxApprovalGrants.Registration reg =
            SandboxApprovalGrants.register("turn-4", "eval-auto-approve", List.of());
        assertTrue(SandboxApprovalGrants.forTurn("turn-4") == reg.grant());
        reg.close();
        assertNull(SandboxApprovalGrants.forTurn("turn-4"));
        assertNull(SandboxApprovalGrants.forTurn("never-registered"));
        assertNull(SandboxApprovalGrants.forTurn(null));
    }

    @Test
    void aTurnIdCannotHoldTwoGrants() {
        try (SandboxApprovalGrants.Registration ignored =
                 SandboxApprovalGrants.register("turn-5", "eval-auto-approve", List.of())) {
            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> SandboxApprovalGrants.register("turn-5", "someone-else", List.of()));
        }
    }
}
