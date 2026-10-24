package com.agenticai.core.agents.turnjournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.guardrails.ApprovalGateMode;
import com.agenticai.core.guardrails.ApprovalParkProperties;
import com.agenticai.core.guardrails.GuardedToolCallback;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.guardrails.ToolDispatchGate;
import com.agenticai.core.guardrails.TurnApprovalState;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * What the turn journal says about a tool call. The journal wraps the tool from OUTSIDE the guard chain, so a normal
 * return does not mean the tool ran; these tests put a REAL {@link GuardedToolCallback} under the journal wrapper and
 * check the status of each way a call can end.
 */
class JournalingToolCallbackTest {

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
        gate = mock(HumanApprovalGateService.class);
        guardrails = mock(GuardrailService.class);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
    }

    private static final class Raw implements ToolCallback {
        final String name;
        final String output;
        int calls;
        RuntimeException failure;

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
            if (failure != null) {
                throw failure;
            }
            return output;
        }
    }

    private ToolCallback chain(Raw raw, ToolDispatchGate dispatch, ApprovalParkProperties texts) {
        GuardedToolCallback guarded =
                new GuardedToolCallback(
                                raw,
                                new AtomicInteger(),
                                10,
                                guardrails,
                                policy,
                                gate,
                                AGENT,
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
                        .withTurnApproval(approval);
        if (texts != null) {
            guarded.withApprovalParkTexts(texts);
        }
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

    private ToolCallback supervisedChain(Raw raw, ToolDispatchGate dispatch) {
        GuardedToolCallback guarded =
                new GuardedToolCallback(
                                raw,
                                new AtomicInteger(),
                                10,
                                guardrails,
                                policy,
                                gate,
                                AGENT,
                                "sess-1",
                                null,
                                false,
                                null,
                                null,
                                ApprovalGateMode.GATE_UNLESS_READ_ONLY,
                                null,
                                null,
                                null,
                                dispatch,
                                null)
                        .withTurnApproval(approval);
        return recorder.wrapTools(List.of(guarded), approval).get(0);
    }

    @Test
    void aReadOnlyCallLetThroughUnderSupervisedIsJournaledAsAutoAllowedBeforeTheCall() {
        Raw raw = new Raw("web_search", "{\"hits\":3}");

        assertEquals("{\"hits\":3}", supervisedChain(raw, tool -> ToolDispatchGate.Verdict.allowReadOnly()).call("{}"));

        List<TurnEvent> events = events();
        TurnEvent approvalEvent = only(TurnEventType.APPROVAL);
        assertTrue(approvalEvent.summary().contains("tool=web_search"), approvalEvent.summary());
        assertTrue(approvalEvent.summary().contains("decision=auto-allowed"), approvalEvent.summary());
        assertTrue(approvalEvent.summary().contains("reason=read-only-under-SUPERVISED"), approvalEvent.summary());
        assertFalse(approvalEvent.summary().contains("request="), "nobody was asked, so there is no request");
        assertTrue(only(TurnEventType.TOOL_CALL).summary().contains("status=ok"), events.toString());
        assertTrue(
                events.indexOf(approvalEvent) < events.indexOf(only(TurnEventType.TOOL_CALL)),
                "the decision precedes the call: " + events);
        assertEquals(1, raw.calls);
    }

    @Test
    void aSupervisedCallThatIsNotReadOnlyIsParkedAndNeverJournaledAsAutoAllowed() {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(UUID.randomUUID());
        when(gate.recordToolCallForApproval(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
        Raw raw = new Raw("remember_fact", "stored");

        supervisedChain(raw, tool -> ToolDispatchGate.Verdict.allow()).call("{}");

        assertTrue(only(TurnEventType.APPROVAL).summary().contains("decision=pending"), events().toString());
        assertTrue(only(TurnEventType.TOOL_CALL).summary().contains("status=parked"), events().toString());
        assertEquals(0, raw.calls);
    }

    @Test
    void anExecutedCallIsOk() {
        Raw raw = new Raw("lookup", "{\"found\":true}");

        assertEquals("{\"found\":true}", chain(raw, null, null).call("{}"));

        assertTrue(only(TurnEventType.TOOL_CALL).summary().contains("status=ok"), events().toString());
        assertEquals(1, raw.calls);
    }

    @Test
    void aCallTheGuardRefusedIsBlockedNotOk() {
        Raw raw = new Raw("lookup", "found");
        ToolDispatchGate deny = tool -> ToolDispatchGate.Verdict.deny("quarantined");

        chain(raw, deny, null).call("{}");

        String summary = only(TurnEventType.TOOL_CALL).summary();
        assertTrue(summary.contains("status=blocked"), summary);
        assertFalse(summary.contains("status=ok"), summary);
        assertEquals(0, raw.calls, "the tool never ran");
    }

    @Test
    void aCallParkedForApprovalIsParkedAndLeavesAnApprovalEventNamingTheRequest() {
        ApprovalRequest request = mock(ApprovalRequest.class);
        UUID requestId = UUID.randomUUID();
        when(request.getId()).thenReturn(requestId);
        when(gate.recordToolCallForApproval(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
        Raw raw = new Raw("send_invoice", "sent");

        chain(raw, null, null).call("{\"to\":\"a\"}");

        assertTrue(only(TurnEventType.TOOL_CALL).summary().contains("status=parked"), events().toString());
        TurnEvent approvalEvent = only(TurnEventType.APPROVAL);
        assertTrue(approvalEvent.summary().contains("tool=send_invoice"), approvalEvent.summary());
        assertTrue(approvalEvent.summary().contains("decision=pending"), approvalEvent.summary());
        assertTrue(
                approvalEvent.summary().contains("request=" + requestId),
                "the id must survive the redaction that masks token-shaped text: " + approvalEvent.summary());
        assertEquals(0, raw.calls);
    }

    @Test
    void aParkTextWithNoRefusalWordingIsStillParked() {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(UUID.randomUUID());
        when(gate.recordToolCallForApproval(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
        ApprovalParkProperties texts = new ApprovalParkProperties();
        texts.setAwaitingMessage("Hold on for {tool}");

        // No approval state handed to the journal: the typed marker alone has to carry it.
        TurnRecorder bare = TurnRecorder.start(journal, AGENT, UUID.randomUUID(), "sess-1");
        GuardedToolCallback guarded =
                new GuardedToolCallback(
                                new Raw("send_invoice", "sent"),
                                new AtomicInteger(),
                                10,
                                guardrails,
                                policy,
                                gate,
                                AGENT,
                                "sess-1")
                        .withTurnApproval(approval)
                        .withApprovalParkTexts(texts);
        bare.wrapTools(List.of(guarded)).get(0).call("{}");

        List<TurnEvent> events = journal.read(AGENT, bare.turnId());
        assertTrue(
                events.stream().anyMatch(e -> e.type() == TurnEventType.TOOL_CALL && e.summary().contains("status=parked")),
                events.toString());
        assertTrue(events.stream().anyMatch(e -> e.type() == TurnEventType.APPROVAL), events.toString());
    }

    @Test
    void aThrownCallIsError() {
        Raw raw = new Raw("lookup", "x");
        raw.failure = new IllegalStateException("boom");

        assertThrows(RuntimeException.class, () -> chain(raw, null, null).call("{}"));

        assertTrue(only(TurnEventType.TOOL_CALL).summary().contains("status=error"), events().toString());
    }

    @Test
    void theTurnIsCurrentOnTheCallingThreadWhileATool_runsAndRestoredAfterwards() {
        AtomicReference<UUID> seen = new AtomicReference<>();
        ToolCallback probe =
                new ToolCallback() {
                    @Override
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder().name("probe").description("p").inputSchema("{}").build();
                    }

                    @Override
                    public String call(String toolInput) {
                        seen.set(TurnRecorder.currentTurnId());
                        return "ok";
                    }
                };

        assertNull(TurnRecorder.currentTurnId());
        recorder.wrapTools(List.of(probe)).get(0).call("{}");

        assertEquals(recorder.turnId(), seen.get(), "a sub-agent turn started by the tool can name its parent");
        assertNull(TurnRecorder.currentTurnId());
    }
}
