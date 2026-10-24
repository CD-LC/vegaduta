package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.agenticai.core.guardrails.procedures.ProcedureGuardEvaluator;
import com.agenticai.core.guardrails.taint.TaintGate;
import com.agenticai.core.guardrails.taint.TaintMode;
import com.agenticai.core.guardrails.taint.ToolTaintProfile;
import com.agenticai.core.guardrails.taint.TurnTaint;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@link ApprovalGateMode#GATE_UNLESS_READ_ONLY}: a SUPERVISED agent's call that its dispatch gate vouches for as an
 * explicitly classified read-only tool runs without a human, and only the human-approval requirement is skipped. Anything
 * that is not that - a plain allow, an approval requirement, a refusal - and every other guard (step ceiling, procedures,
 * taint) behaves exactly as under {@link ApprovalGateMode#ALWAYS_GATE}.
 */
class GuardedToolCallbackReadOnlyAutoAllowTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String INPUT = "{\"q\":\"build status\"}";
    private static final String OUTPUT = "{\"ok\":true}";
    private static final String TOOL = "web_search";

    private final ApprovalPolicyService policy = mock(ApprovalPolicyService.class);
    private final HumanApprovalGateService gate = mock(HumanApprovalGateService.class);
    private final GuardrailService guardrails = mock(GuardrailService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<String> audited = new ArrayList<>();
    private final TurnApprovalState state = new TurnApprovalState("scoped");

    private int executions;

    private GuardedToolCallback guard(ApprovalGateMode mode, ToolDispatchGate dispatch, int maxSteps,
        ProcedureGuardEvaluator procedures, TaintGate taint) {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(TOOL);
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(INPUT)).thenAnswer(i -> {
            executions++;
            return OUTPUT;
        });
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
        ApprovalRequest pending = mock(ApprovalRequest.class);
        when(pending.getId()).thenReturn(UUID.randomUUID());
        when(gate.recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
            .thenReturn(pending);
        GuardedToolCallback guarded = new GuardedToolCallback(
            delegate, new AtomicInteger(0), maxSteps, guardrails, policy, gate, AGENT, "session-1",
            null, false, null, null, mode, null, null, procedures, dispatch, meters)
            .withTurnApproval(state)
            .withReadOnlyAutoAllowAudit((tool, agent, session, detail) -> audited.add(tool + "|" + agent + "|" + detail));
        return taint == null ? guarded : guarded.withTaintGate(taint);
    }

    private GuardedToolCallback guard(ApprovalGateMode mode, ToolDispatchGate dispatch) {
        return guard(mode, dispatch, 10, null, null);
    }

    private void assertParked(String result) {
        assertTrue(result.contains("NOT executed"), result);
        assertEquals(0, executions, "a parked call never runs");
        assertTrue(state.isParked());
        assertTrue(audited.isEmpty(), "a call that needed a person is not an auto-allowed one");
    }

    // ------------------------------------------------------------------------------ the allowed call

    @Test
    void aReadOnlyVerdictRunsWithoutApprovalAndIsRecordedForReview() {
        GuardedToolCallback guarded = guard(ApprovalGateMode.GATE_UNLESS_READ_ONLY, name -> ToolDispatchGate.Verdict.allowReadOnly());

        String result = guarded.call(INPUT);

        assertEquals(OUTPUT, result);
        assertEquals(1, executions);
        assertFalse(state.isParked());
        verify(gate, never()).recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any());
        assertEquals(List.of(TOOL + "|" + AGENT + "|auto-allowed: read-only under SUPERVISED"), audited);
        assertEquals(1.0, meters.counter(ReadOnlyAutoAllow.METRIC).count());
        assertTrue(state.takeAutoAllowedReadOnly(TOOL, INPUT), "the turn journal can see the decision");
        assertFalse(state.takeAutoAllowedReadOnly(TOOL, INPUT), "and reads it once per call");
    }

    @Test
    void aFailingAuditSinkNeverFailsTheCall() {
        GuardedToolCallback guarded = guard(ApprovalGateMode.GATE_UNLESS_READ_ONLY, name -> ToolDispatchGate.Verdict.allowReadOnly())
            .withReadOnlyAutoAllowAudit((tool, agent, session, detail) -> {
                throw new IllegalStateException("audit store down");
            });

        assertEquals(OUTPUT, guarded.call(INPUT));
        assertEquals(1, executions);
    }

    // ------------------------------------------------------------------ everything else is still gated

    @Test
    void aPlainAllowIsNotReadOnlyAndParks() {
        // An unclassified tool, or one the classification could not vouch for: ALLOW without the read-only mark.
        GuardedToolCallback guarded = guard(ApprovalGateMode.GATE_UNLESS_READ_ONLY, name -> ToolDispatchGate.Verdict.allow());

        assertParked(guarded.call(INPUT));
    }

    @Test
    void noDispatchGateMeansNoReadOnlyVerdictAndTheCallParks() {
        assertParked(guard(ApprovalGateMode.GATE_UNLESS_READ_ONLY, null).call(INPUT));
    }

    @Test
    void aDispatchGateThatThrowsParksTheCall() {
        ToolDispatchGate broken = name -> {
            throw new IllegalStateException("registry down");
        };

        // Fail-open turns a throwing gate into a plain allow, which is not a read-only verdict; fail-closed refuses it.
        String result = guard(ApprovalGateMode.GATE_UNLESS_READ_ONLY, broken).call(INPUT);

        assertEquals(0, executions);
        assertTrue(result.contains("NOT executed") || !result.equals(OUTPUT), result);
    }

    @Test
    void anApprovalRequirementStillParks() {
        GuardedToolCallback guarded = guard(ApprovalGateMode.GATE_UNLESS_READ_ONLY,
            name -> ToolDispatchGate.Verdict.requireApproval("a hook asked"));

        assertParked(guarded.call(INPUT));
    }

    @Test
    void aRefusalIsStillARefusal() {
        GuardedToolCallback guarded = guard(ApprovalGateMode.GATE_UNLESS_READ_ONLY,
            name -> ToolDispatchGate.Verdict.deny("quarantined"));

        String result = guarded.call(INPUT);

        assertEquals(0, executions);
        assertTrue(result.contains("quarantined"), result);
        assertTrue(audited.isEmpty());
    }

    @Test
    void theAlwaysGateModeIgnoresTheReadOnlyMark() {
        GuardedToolCallback guarded = guard(ApprovalGateMode.ALWAYS_GATE, name -> ToolDispatchGate.Verdict.allowReadOnly());

        assertParked(guarded.call(INPUT));
    }

    @Test
    void theStepCeilingStillAppliesToAnUnattendedCall() {
        GuardedToolCallback guarded = guard(
            ApprovalGateMode.GATE_UNLESS_READ_ONLY, name -> ToolDispatchGate.Verdict.allowReadOnly(), 1, null, null);

        assertEquals(OUTPUT, guarded.call(INPUT));
        String second = guarded.call(INPUT);

        assertEquals(1, executions, "the call past the ceiling was answered, not run");
        assertTrue(ToolBudgetExhausted.isBudgetExhausted(second), second);
        assertEquals(1, audited.size(), "only the call that ran is audited as auto-allowed");
    }

    @Test
    void aProcedureThatRequiresApprovalStillParks() {
        ProcedureGuardEvaluator procedures = mock(ProcedureGuardEvaluator.class);
        when(procedures.check(any(), anyString(), anyString())).thenReturn(
            new ProcedureGuardEvaluator.Decision(ProcedureGuardEvaluator.Outcome.REQUIRE_APPROVAL, "refunds", "ask first"));
        GuardedToolCallback guarded = guard(
            ApprovalGateMode.GATE_UNLESS_READ_ONLY, name -> ToolDispatchGate.Verdict.allowReadOnly(), 10, procedures, null);

        assertParked(guarded.call(INPUT));
    }

    @Test
    void aTaintGateThatRequiresApprovalStillParks() {
        TurnTaint tainted = new TurnTaint();
        tainted.mark("fetch_web_page");
        TaintGate taint = new TaintGate(
            TaintMode.ENFORCE, tainted, (tool, input) -> new ToolTaintProfile(false, true, "sink"), meters, null, AGENT,
            "session-1");
        GuardedToolCallback guarded = guard(
            ApprovalGateMode.GATE_UNLESS_READ_ONLY, name -> ToolDispatchGate.Verdict.allowReadOnly(), 10, null, taint);

        assertParked(guarded.call(INPUT));
    }

    // ------------------------------------------------------------------------------ the verdict itself

    @Test
    void onlyAnAllowCanBeReadOnly() {
        assertTrue(ToolDispatchGate.Verdict.allowReadOnly().readOnly());
        assertFalse(ToolDispatchGate.Verdict.allow().readOnly());
        assertFalse(new ToolDispatchGate.Verdict(ToolDispatchGate.Outcome.DENY, "x", true).readOnly());
        assertFalse(new ToolDispatchGate.Verdict(ToolDispatchGate.Outcome.REQUIRE_APPROVAL, "x", true).readOnly());
        assertFalse(new ToolDispatchGate.Verdict(ToolDispatchGate.Outcome.ALLOW, null).readOnly());
    }
}
