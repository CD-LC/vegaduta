package com.agenticai.core.agents.turnjournal;

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
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.guardrails.GuardedToolCallback;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.guardrails.SandboxApprovalGrants;
import com.agenticai.core.guardrails.ToolEffectSandbox;
import com.agenticai.core.guardrails.TurnApprovalState;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * What the turn journal says about a tool call of an offline eval turn, through a REAL {@link GuardedToolCallback} under the
 * journal wrapper: a call the sandbox approved is journaled as {@code eval-approved} (never as a person's decision), a call
 * the sandbox simulated as {@code simulated} (the tool did not run, which is not {@code ok}), and a call held for approval
 * as {@code parked} with a pending event that names no request, because none was recorded.
 */
class JournalingToolCallbackEvalSandboxTest {

    private static final UUID AGENT = UUID.randomUUID();

    private InMemoryTurnJournal journal;
    private TurnRecorder recorder;
    private TurnApprovalState approval;
    private ApprovalPolicyService policy;
    private HumanApprovalGateService gate;
    private GuardrailService guardrails;

    @BeforeEach
    void setUp() {
        journal = new InMemoryTurnJournal();
        recorder = TurnRecorder.start(journal, AGENT, UUID.randomUUID(), "sess-1");
        approval = new TurnApprovalState("c1:mem");
        policy = mock(ApprovalPolicyService.class);
        when(policy.requiresApproval("mcp_tool.send_invoice")).thenReturn(true);
        when(policy.requiresApproval("mcp_tool.export_report")).thenReturn(true);
        gate = mock(HumanApprovalGateService.class);
        guardrails = mock(GuardrailService.class);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
    }

    private static final class Raw implements ToolCallback {
        final String name;
        final String output;
        int calls;

        Raw(String name, String output) {
            this.name = name;
            this.output = output;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            calls++;
            return output;
        }
    }

    private ToolCallback chain(Raw raw) {
        GuardedToolCallback guarded = new GuardedToolCallback(
                        raw, new AtomicInteger(), 10, guardrails, policy, gate, AGENT, "sess-1")
                .withTurnApproval(approval);
        return recorder.wrapTools(List.of(guarded), approval).get(0);
    }

    private List<TurnEvent> events() {
        return journal.read(AGENT, recorder.turnId());
    }

    private TurnEvent only(TurnEventType type) {
        List<TurnEvent> found = events().stream().filter(e -> e.type() == type).toList();
        assertEquals(1, found.size(), () -> "events: " + events());
        return found.get(0);
    }

    private static final ToolEffectSandbox READS = (tool, input) -> ToolEffectSandbox.Verdict.run();
    private static final ToolEffectSandbox WRITES = (tool, input) -> ToolEffectSandbox.Verdict.simulate("declared WRITE");

    @Test
    void aGatedCallTheSandboxApprovedIsJournaledAsEvalApprovedBeforeItsToolCall() {
        try (SandboxApprovalGrants.Registration reg =
                SandboxApprovalGrants.registerSandbox("j-1", "eval-auto-approve", List.of(), READS)) {
            approval.enableSandboxGrant(reg.grant());
            Raw raw = new Raw("export_report", "report");

            assertEquals("report", chain(raw).call("{}"));

            TurnEvent decision = only(TurnEventType.APPROVAL);
            assertTrue(decision.summary().contains("tool=export_report"), decision.summary());
            assertTrue(decision.summary().contains("decision=decided:eval-approved"), decision.summary());
            assertFalse(decision.summary().contains("request="), "no request exists to point at: " + decision.summary());
            assertTrue(only(TurnEventType.TOOL_CALL).summary().contains("status=ok"), events().toString());
            assertTrue(events().indexOf(decision) < events().indexOf(only(TurnEventType.TOOL_CALL)));
            assertEquals(1, raw.calls);
        }
    }

    @Test
    void anApprovedCallThatTheSandboxSimulatedIsApprovedAndSimulatedNotOk() {
        try (SandboxApprovalGrants.Registration reg =
                SandboxApprovalGrants.registerSandbox("j-2", "eval-auto-approve", List.of(), WRITES)) {
            approval.enableSandboxGrant(reg.grant());
            Raw raw = new Raw("send_invoice", "sent");

            chain(raw).call("{}");

            assertTrue(only(TurnEventType.APPROVAL).summary().contains("decision=decided:eval-approved"), events().toString());
            String call = only(TurnEventType.TOOL_CALL).summary();
            assertTrue(call.contains("status=simulated"), call);
            assertFalse(call.contains("status=ok"), "the tool did not run, so the journal must not say it did: " + call);
            assertEquals(0, raw.calls);
        }
    }

    @Test
    void aSimulatedCallThatNeededNoApprovalLeavesNoApprovalEvent() {
        try (SandboxApprovalGrants.Registration reg =
                SandboxApprovalGrants.registerSandbox("j-3", null, List.of(), WRITES)) {
            approval.enableSandboxGrant(reg.grant());
            Raw raw = new Raw("remember_fact", "stored");

            chain(raw).call("{}");

            assertTrue(only(TurnEventType.TOOL_CALL).summary().contains("status=simulated"), events().toString());
            assertTrue(events().stream().noneMatch(e -> e.type() == TurnEventType.APPROVAL), events().toString());
            assertEquals(0, raw.calls);
        }
    }

    @Test
    void aHeldCallIsParkedWithAPendingEventAndNoRequestWasRecorded() {
        try (SandboxApprovalGrants.Registration reg =
                SandboxApprovalGrants.registerSandbox("j-4", null, List.of(), READS)) {
            approval.enableSandboxGrant(reg.grant());
            Raw raw = new Raw("send_invoice", "sent");

            chain(raw).call("{\"to\":\"a\"}");

            assertTrue(only(TurnEventType.TOOL_CALL).summary().contains("status=parked"), events().toString());
            TurnEvent pending = only(TurnEventType.APPROVAL);
            assertTrue(pending.summary().contains("decision=pending"), pending.summary());
            assertFalse(pending.summary().contains("request="), pending.summary());
            assertEquals(0, raw.calls);
            verify(gate, never()).recordToolCallForApproval(any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void anOrdinaryTurnNeverProducesAnEvalApprovedEvent() {
        Raw raw = new Raw("lookup", "found");

        chain(raw).call("{}");

        assertTrue(events().stream().noneMatch(e -> e.type() == TurnEventType.APPROVAL), events().toString());
    }
}
