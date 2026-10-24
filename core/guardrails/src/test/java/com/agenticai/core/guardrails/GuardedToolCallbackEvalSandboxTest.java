package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * The isolated eval turn, from {@link GuardedToolCallback}'s side: a tool the sandbox rule says only reads runs; any other
 * call is answered with a simulated result and the tool is never reached (even a call the sandbox just approved); a call
 * that needs a human is held exactly as in production but records no approval request, so nobody is notified; a rule that
 * fails simulates; and a turn without a sandbox is unaffected.
 */
class GuardedToolCallbackEvalSandboxTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String INPUT = "{\"to\":\"x\"}";
    private static final String OUTPUT = "{\"ok\":true}";

    private final ApprovalPolicyService policy = mock(ApprovalPolicyService.class);
    private final HumanApprovalGateService gate = mock(HumanApprovalGateService.class);
    private final GuardrailService guardrails = mock(GuardrailService.class);
    private ToolCallback delegate;

    private GuardedToolCallback guardFor(String toolName, boolean gated, TurnApprovalState state) {
        delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(toolName);
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(INPUT)).thenReturn(OUTPUT);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(policy.requiresApproval("mcp_tool." + toolName)).thenReturn(gated);
        ApprovalRequest pending = mock(ApprovalRequest.class);
        when(pending.getId()).thenReturn(UUID.randomUUID());
        when(gate.recordToolCallForApproval(
                anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
            .thenReturn(pending);
        GuardedToolCallback guarded = new GuardedToolCallback(
            delegate, new AtomicInteger(0), 10, guardrails, policy, gate, AGENT_ID, "session-1");
        return guarded.withTurnApproval(state);
    }

    private void verifyNoApprovalRecorded() {
        verify(gate, never()).recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any());
        verify(gate, never()).recordToolCallWithOrigin(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any());
    }

    private static final ToolEffectSandbox READS = (tool, input) -> ToolEffectSandbox.Verdict.run();
    private static final ToolEffectSandbox WRITES = (tool, input) -> ToolEffectSandbox.Verdict.simulate("declared WRITE");

    // ------------------------------------------------------------------ effects

    @Test
    void aToolTheRuleSaysOnlyReadsRunsForReal() {
        TurnApprovalState state = new TurnApprovalState("scoped");
        try (SandboxApprovalGrants.Registration reg =
                 SandboxApprovalGrants.registerSandbox("eff-1", null, List.of(), READS)) {
            state.enableSandboxGrant(reg.grant());
            GuardedToolCallback guarded = guardFor("lookup", false, state);

            String result = guarded.call(INPUT);

            assertEquals(OUTPUT, result);
            verify(delegate).call(INPUT);
            assertTrue(reg.grant().simulatedTools().isEmpty());
        }
    }

    @Test
    void anyOtherCallIsAnsweredWithASimulatedResultAndTheToolIsNeverReached() {
        TurnApprovalState state = new TurnApprovalState("scoped");
        try (SandboxApprovalGrants.Registration reg =
                 SandboxApprovalGrants.registerSandbox("eff-2", null, List.of(), WRITES)) {
            state.enableSandboxGrant(reg.grant());
            GuardedToolCallback guarded = guardFor("send_invoice", false, state);

            String result = guarded.call(INPUT);

            verify(delegate, never()).call(any(String.class));
            assertTrue(result.contains("send_invoice") && result.contains("NOT executed"), result);
            assertEquals(java.util.Optional.of(ToolResultMarker.Kind.SANDBOXED), ToolResultMarker.kindOf(result),
                "the platform's answer is typed, so the ledger and the journal can tell it from a tool's output");
            assertEquals(List.of("send_invoice"), reg.grant().simulatedTools());
            assertFalse(state.isParked(), "a simulated call is not a held one");
            verifyNoApprovalRecorded();
        }
    }

    @Test
    void aRuleThatFailsSimulatesTheCallInsteadOfRunningIt() {
        TurnApprovalState state = new TurnApprovalState("scoped");
        ToolEffectSandbox broken = (tool, input) -> {
            throw new IllegalStateException("registry down");
        };
        try (SandboxApprovalGrants.Registration reg =
                 SandboxApprovalGrants.registerSandbox("eff-3", null, List.of(), broken)) {
            state.enableSandboxGrant(reg.grant());
            GuardedToolCallback guarded = guardFor("lookup", false, state);

            String result = guarded.call(INPUT);

            verify(delegate, never()).call(any(String.class));
            assertTrue(result.contains("NOT executed"), result);
            assertEquals(List.of("lookup"), reg.grant().simulatedTools());
        }
    }

    // ------------------------------------------------------------------ approvals

    @Test
    void aGatedCallIsHeldWithoutRecordingAnApprovalSoNobodyIsNotified() {
        TurnApprovalState state = new TurnApprovalState("scoped");
        try (SandboxApprovalGrants.Registration reg =
                 SandboxApprovalGrants.registerSandbox("app-1", null, List.of(), READS)) {
            state.enableSandboxGrant(reg.grant());
            GuardedToolCallback guarded = guardFor("send_message", true, state);

            String result = guarded.call(INPUT);

            verify(delegate, never()).call(any(String.class));
            verifyNoApprovalRecorded();
            assertTrue(result.contains("requires human approval") && result.contains("NOT executed"),
                "the model reads the same hold as in production: " + result);
            assertTrue(state.isParked(), "the turn stops at the approval, as a person's would");
            assertEquals(List.of("send_message"), reg.grant().heldTools());
            assertEquals(0, reg.grant().usedCount());
            assertTrue(state.requestIdFor("send_message", INPUT).startsWith("eval-sandbox-"),
                "the held call carries an id nothing links to a request row");
        }
    }

    @Test
    void aCallMadeAfterTheHoldIsQueuedWithoutARecordEither() {
        TurnApprovalState state = new TurnApprovalState("scoped");
        try (SandboxApprovalGrants.Registration reg =
                 SandboxApprovalGrants.registerSandbox("app-2", null, List.of(), READS)) {
            state.enableSandboxGrant(reg.grant());
            GuardedToolCallback guarded = guardFor("send_message", true, state);
            guarded.call(INPUT);

            String queued = guarded.call("{\"to\":\"y\"}");

            assertTrue(queued.contains("NOT executed") || queued.contains("queued"), queued);
            verifyNoApprovalRecorded();
            assertEquals(2, reg.grant().heldTools().size());
        }
    }

    @Test
    void anApprovingSandboxLetsAGatedReaderRunAndCountsIt() {
        TurnApprovalState state = new TurnApprovalState("scoped");
        try (SandboxApprovalGrants.Registration reg =
                 SandboxApprovalGrants.registerSandbox("app-3", "eval-auto-approve", List.of("local_shell"), READS)) {
            state.enableSandboxGrant(reg.grant());
            GuardedToolCallback guarded = guardFor("export_report", true, state);

            String result = guarded.call(INPUT);

            assertEquals(OUTPUT, result);
            assertEquals(1, reg.grant().usedCount());
            assertEquals(List.of("export_report"), reg.grant().usedTools());
            assertFalse(state.isParked());
            verifyNoApprovalRecorded();
        }
    }

    @Test
    void anApprovedCallThatCouldChangeSomethingIsStillSimulated() {
        TurnApprovalState state = new TurnApprovalState("scoped");
        try (SandboxApprovalGrants.Registration reg =
                 SandboxApprovalGrants.registerSandbox("app-4", "eval-auto-approve", List.of(), WRITES)) {
            state.enableSandboxGrant(reg.grant());
            GuardedToolCallback guarded = guardFor("send_message", true, state);

            String result = guarded.call(INPUT);

            verify(delegate, never()).call(any(String.class));
            assertEquals(1, reg.grant().usedCount(), "the sandbox approved it ...");
            assertEquals(List.of("send_message"), reg.grant().simulatedTools(), "... and still did not run it");
            assertTrue(result.contains("NOT executed"), result);
        }
    }

    @Test
    void aToolTheOperatorExcludedIsHeldEvenWhenTheSandboxApproves() {
        TurnApprovalState state = new TurnApprovalState("scoped");
        try (SandboxApprovalGrants.Registration reg =
                 SandboxApprovalGrants.registerSandbox("app-5", "eval-auto-approve", List.of("*payment*"), READS)) {
            state.enableSandboxGrant(reg.grant());
            GuardedToolCallback guarded = guardFor("create_payment_link", true, state);

            guarded.call(INPUT);

            verify(delegate, never()).call(any(String.class));
            assertEquals(0, reg.grant().usedCount());
            assertEquals(List.of("create_payment_link"), reg.grant().heldTools());
            assertTrue(state.isParked());
        }
    }

    // ------------------------------------------------------------------ what is not changed

    @Test
    void anOrdinaryTurnHasNoSandboxSoAGatedCallStillRecordsARealApproval() {
        GuardedToolCallback guarded = guardFor("send_message", true, new TurnApprovalState("scoped"));

        String result = guarded.call(INPUT);

        verify(delegate, never()).call(any(String.class));
        verify(gate).recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any());
        assertTrue(result.contains("requires human approval"), result);
    }

    @Test
    void anOrdinaryTurnRunsAnUngatedToolWhateverItsClass() {
        GuardedToolCallback guarded = guardFor("send_invoice", false, new TurnApprovalState("scoped"));

        assertEquals(OUTPUT, guarded.call(INPUT));
        verify(delegate).call(INPUT);
    }

    @Test
    void theOlderApprovalOnlyGrantDoesNotIsolateTheTurn() {
        TurnApprovalState state = new TurnApprovalState("scoped");
        try (SandboxApprovalGrants.Registration reg =
                 SandboxApprovalGrants.register("old-1", "eval-auto-approve", List.of())) {
            state.enableSandboxGrant(reg.grant());
            GuardedToolCallback guarded = guardFor("send_invoice", false, state);

            assertEquals(OUTPUT, guarded.call(INPUT), "a grant made with register() keeps its narrow behaviour");
            assertFalse(reg.grant().isolated());
        }
    }

    // ------------------------------------------------------------------ the registry

    @Test
    void anIsolationNeedsTheRuleThatDecidesWhatMayRun() {
        assertThrows(NullPointerException.class, () -> SandboxApprovalGrants.registerSandbox("x-1", null, List.of(), null));
    }

    @Test
    void aSandboxWithNoActorApprovesNothing() {
        try (SandboxApprovalGrants.Registration reg =
                 SandboxApprovalGrants.registerSandbox("x-2", "  ", List.of(), READS)) {
            assertFalse(reg.grant().approves());
            assertFalse(reg.grant().permits("anything"));
            assertTrue(reg.grant().isolated());
        }
        assertEquals(null, SandboxApprovalGrants.forTurn("x-2"), "closing the registration removes the grant");
    }
}
