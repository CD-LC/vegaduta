package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.turn.replay.ReplayTurnRunner;
import com.agenticai.core.agents.turn.replay.ReplayTurnRunner.Status;
import com.agenticai.core.agents.turn.replay.ReplayTurnRunner.ToolEvidence;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The offline replay of one message (what the eval runner uses) through the REAL turn pipeline: the real
 * guard chain, the real approval service and session memory, with only the LLM scripted. Pins what the
 * old text-only bridge could not show: a gated tool holds the turn exactly as in production (but records no
 * approval, so nobody is notified) unless the caller asked for the sandbox auto-approval; the auto-approval
 * is counted and says so; a tool the caller excluded is still held; and the evidence names how each call really
 * ended. What may really RUN in a sandboxed replay is {@link EvalSandboxTurnE2ETest}.
 */
class ReplayTurnRunnerE2ETest {

    private static final String SEND = "send_invoice";
    private static final String ACTOR = "eval-auto-approve";

    private ApprovalTurnHarness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    private ApprovalTurnHarness.Builder invoiceAgent(RecordingTool send) {
        return ApprovalTurnHarness.builder()
                .tool(send)
                .gateTool(SEND)
                .responder(request -> request.hasToolResult()
                        ? ScriptedLlm.text("Invoice handled.")
                        : ScriptedLlm.callTool("c1", SEND, "{\"to\":\"billing@acme.test\"}"));
    }

    private ReplayTurnRunner runner() {
        return EvalSandboxTurnE2ETest.runner(harness);
    }

    private ReplayTurnRunner.Request request(boolean withTools, String actor, List<String> excluded) {
        return new ReplayTurnRunner.Request(
                harness.agentId(), "eval:run-1:0", harness.subject(), "Send invoice INV-1 to billing@acme.test.",
                withTools, actor, excluded, Duration.ofSeconds(60));
    }

    @Test
    void withoutAnAutoApprovalAGatedToolIsHeldAsInProductionButNothingIsRecordedForAHuman() {
        RecordingTool send = RecordingTool.returning(SEND, "Sends an invoice", "sent");
        harness = invoiceAgent(send).build();

        ReplayTurnRunner.Result result = runner().run(request(true, null, List.of()));

        assertAll(
                () -> assertEquals(0, send.executions(), "a gated tool must not run without a decision"),
                () -> assertTrue(harness.approvalRequests().isEmpty(),
                        "an eval must not put a request in an approver's inbox or send a notification"),
                () -> assertEquals(0, result.autoApprovedCalls()),
                () -> assertEquals(List.of(SEND), result.heldTools()),
                () -> assertEquals(1, result.toolCalls().size()),
                () -> assertEquals(Status.AWAITING_APPROVAL, result.toolCalls().get(0).status()),
                () -> assertEquals(SEND, result.toolCalls().get(0).tool()));
    }

    @Test
    void theAutoApprovalApprovesTheGatedCallAndCountsItButAToolNobodyClassifiedIsStillSimulated() {
        RecordingTool send = RecordingTool.returning(SEND, "Sends an invoice", "sent");
        harness = invoiceAgent(send).build();

        ReplayTurnRunner.Result result = runner().run(request(true, ACTOR, List.of("local_shell", "*payment*")));

        ToolEvidence call = result.toolCalls().get(0);
        assertAll(
                () -> assertEquals(0, send.executions(), "an invoice sender is not a known reader: it never really runs"),
                () -> assertTrue(harness.approvalRequests().isEmpty(), "nothing was parked for a human"),
                () -> assertEquals(1, result.autoApprovedCalls(), "the sandbox did approve it"),
                () -> assertEquals(List.of(SEND), result.simulatedTools()),
                () -> assertEquals("Invoice handled.", result.replyText(), "the turn went on after the simulated success"),
                () -> assertEquals(1, result.toolCalls().size()),
                () -> assertEquals(SEND, call.tool()),
                () -> assertEquals(Status.SIMULATED, call.status()),
                // The full arguments from the tool-call log, not the ledger's cut summary.
                () -> assertEquals("{\"to\":\"billing@acme.test\"}", call.input()),
                () -> assertTrue(call.output().contains("NOT executed"), call.output()));
    }

    @Test
    void anExcludedToolIsStillHeldEvenUnderTheGrant() {
        RecordingTool send = RecordingTool.returning(SEND, "Sends an invoice", "sent");
        harness = invoiceAgent(send).build();

        ReplayTurnRunner.Result result = runner().run(request(true, ACTOR, List.of("send_*")));

        assertAll(
                () -> assertEquals(0, send.executions()),
                () -> assertTrue(harness.approvalRequests().isEmpty()),
                () -> assertEquals(List.of(SEND), result.heldTools()),
                () -> assertEquals(0, result.autoApprovedCalls()),
                () -> assertEquals(Status.AWAITING_APPROVAL, result.toolCalls().get(0).status()));
    }

    @Test
    void theSandboxEndsWithTheReplayAnOrdinaryTurnOfTheSameAgentStillParksForARealApproval() {
        RecordingTool send = RecordingTool.returning(SEND, "Sends an invoice", "sent");
        harness = invoiceAgent(send).build();
        runner().run(request(true, ACTOR, List.of()));
        assertTrue(harness.approvalRequests().isEmpty());

        harness.chat("Send invoice INV-2 to billing@acme.test.", "ordinary-session");

        assertAll(
                () -> assertEquals(0, send.executions(), "an ordinary turn never runs a gated tool undecided"),
                () -> assertEquals(1, harness.approvalRequests().size(),
                        "an ordinary turn parks for a human: the sandbox did not leak into it"));
    }

    @Test
    void textOnlyOffersNoToolsAndReportsNoToolCalls() {
        RecordingTool send = RecordingTool.returning(SEND, "Sends an invoice", "sent");
        harness = ApprovalTurnHarness.builder()
                .tool(send)
                .gateTool(SEND)
                .responder(request -> ScriptedLlm.text("I would send it."))
                .build();

        ReplayTurnRunner.Result result = runner().run(request(false, ACTOR, List.of()));

        assertAll(
                () -> assertEquals("I would send it.", result.replyText()),
                () -> assertTrue(result.toolCalls().isEmpty()),
                () -> assertEquals(0, send.executions()),
                () -> assertEquals(0, result.autoApprovedCalls(), "an auto-approval request means nothing without tools"),
                () -> assertTrue(harness.llm().requests().get(0).offeredTools().isEmpty(),
                        "the model was offered tools: " + harness.llm().requests().get(0).offeredTools()),
                () -> assertTrue(harness.approvalRequests().isEmpty()));
    }

    @Test
    void aReaderTheModelCallsThatIsNotGatedIsReportedAsRan() {
        RecordingTool lookup = RecordingTool.returning("search_knowledge_base", "Searches the knowledge base", "found");
        harness = ApprovalTurnHarness.builder()
                .tool(lookup)
                .responder(request -> request.hasToolResult()
                        ? ScriptedLlm.text("Found it.")
                        : ScriptedLlm.callTool("c1", "search_knowledge_base", "{\"to\":\"x\"}"))
                .build();

        ReplayTurnRunner.Result result = runner().run(request(true, null, List.of()));

        assertAll(
                () -> assertEquals(1, lookup.executions()),
                () -> assertEquals(Status.OK, result.toolCalls().get(0).status()),
                () -> assertTrue(result.simulatedTools().isEmpty()),
                () -> assertFalse(result.toolCalls().get(0).input().isBlank()));
    }

    @Test
    void aReplayWithToolsAndNoSandboxRuleIsRefusedInsteadOfRunningUnsandboxed() {
        RecordingTool send = RecordingTool.returning(SEND, "Sends an invoice", "sent");
        harness = invoiceAgent(send).build();
        ReplayTurnRunner unsandboxed = new ReplayTurnRunner(
                harness.chatService(), harness.toolCallLogService(), HarnessProviders.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> unsandboxed.run(request(true, ACTOR, List.of())));

        assertEquals(0, send.executions());
        assertTrue(harness.approvalRequests().isEmpty());
    }
}
