package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * What the turn does while, and because, a tool call is parked on a human approval - run through
 * the real turn pipeline, real guard chain, real approval service and real session memory (only the
 * LLM is scripted). Complements {@link ApprovalContinuationE2ETest}, which asks whether the agent
 * finishes AFTER the human approves.
 */
class ApprovalParkedTurnE2ETest {

    private static final String GOAL = "Send invoice INV-7731 to billing@acme.test.";
    private static final String INVOICE = "send_invoice";
    private static final String RECEIPT = "send_receipt";
    private static final String LOOKUP = "lookup";

    private ApprovalTurnHarness harness;

    /**
     * The wording check applies to every model here: these tests are about WHY a stopped reply is
     * or is not continued (a turn parked on an approval is not), and under the default scope the
     * harness's model is not a weak tool caller, so a "not continued" outcome would prove nothing.
     */
    private static void everyModel(com.agenticai.core.agents.turn.ContinuationProperties p) {
        p.setHeuristicScope(com.agenticai.core.agents.turn.ContinuationProperties.Scope.ALL_MODELS);
    }

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    private static RecordingTool tool(String name, String output) {
        return RecordingTool.returning(name, "Test tool " + name, output);
    }

    // ------------------------------------------------------------- the parked turn is not nudged

    /**
     * The model parks, and the turn ends there - the platform's fixed pause reply closes it; the
     * model is never called to write a wrap-up. The continuation loop used to treat a model's
     * "I will carry on" prose as a stopped task and re-prompt ("call the tools and do it") against
     * tools that are all stubbed while the turn is parked.
     */
    @Test
    void aParkedTurnEndsWithoutAskingTheModelToWriteTheClosingReply() {
        harness =
                ApprovalTurnHarness.builder()
                        .continuationPolicy(ApprovalParkedTurnE2ETest::everyModel)
                        .tool(tool(INVOICE, "sent"))
                        .gateTool(INVOICE)
                        .responder(
                                request ->
                                        request.hasToolResult()
                                                ? ScriptedLlm.text(
                                                        "I will send the invoice as soon as you say go.")
                                                : ScriptedLlm.callTool(
                                                        "c1", INVOICE, "{\"to\":\"a\"}"))
                        .build();

        String reply = harness.chat(GOAL);

        assertAll(
                () ->
                        assertEquals(
                                1,
                                harness.llm().modelCalls(),
                                "one call that parks; the pause reply is platform text - the model is not asked again. Requests:\n"
                                        + harness.llm().requests().stream()
                                                .map(r -> r.number() + ": " + r.conversation())
                                                .toList()),
                () -> assertTrue(reply.contains("approval"), reply),
                () -> assertEquals(1, harness.approvalRequests().size()));
    }

    /**
     * The substring exemption is gone: a reply that merely contains "approval" in a turn that is
     * NOT parked and stops at an announced-but-undone step is still an unfinished task.
     */
    @Test
    void aReplyMentioningApprovalIsStillNudgedWhenTheTurnIsNotParked() {
        harness =
                ApprovalTurnHarness.builder()
                        .continuationPolicy(ApprovalParkedTurnE2ETest::everyModel)
                        .tool(tool(LOOKUP, "found"))
                        .responder(
                                request ->
                                        request.number() == 1
                                                ? ScriptedLlm.text(
                                                        "Now that the earlier approval went through, I will write the report.")
                                                : ScriptedLlm.text("The report is done."))
                        .build();

        String reply = harness.chat(GOAL);

        assertTrue(
                harness.llm().modelCalls() >= 2,
                "the model was never asked to carry on; it was called "
                        + harness.llm().modelCalls()
                        + " time(s); reply: "
                        + reply);
        assertTrue(reply.contains("The report is done."), reply);
    }

    // -------------------------------------------------------- the scoped memory id in the envelope

    @Test
    void theReplayEnvelopeCarriesTheScopedMemoryIdTheTurnReadsItsHistoryUnder() throws Exception {
        harness =
                ApprovalTurnHarness.builder()
                        .continuationPolicy(ApprovalParkedTurnE2ETest::everyModel)
                        .tool(tool(INVOICE, "sent"))
                        .gateTool(INVOICE)
                        .responder(
                                request ->
                                        request.hasToolResult()
                                                ? ScriptedLlm.text("Waiting.")
                                                : ScriptedLlm.callTool("c1", INVOICE, "{}"))
                        .build();

        harness.chat(GOAL);

        JsonNode replay =
                new ObjectMapper()
                        .readTree(harness.pendingApproval().getPayloadJson())
                        .path("replay");
        assertEquals(harness.scopedMemoryKey(), replay.path("memorySessionId").asText());
        assertEquals(harness.sessionId(), replay.path("sessionId").asText());
    }

    // -------------------------------------------------------------------------- sibling calls

    /**
     * Two tool calls in ONE assistant message, the first gated. The second used to get a "NOT
     * executed" stub and vanish: no record, nothing replayed after approval. Now it is its own
     * pending approval, and approving it runs it.
     */
    @Test
    void aSiblingCallInTheSameAssistantMessageIsQueuedAndRunsAfterItIsApproved() {
        RecordingTool invoice = tool(INVOICE, "invoice sent");
        RecordingTool receipt = tool(RECEIPT, "receipt sent");
        harness =
                ApprovalTurnHarness.builder()
                        .continuationPolicy(ApprovalParkedTurnE2ETest::everyModel)
                        .tool(invoice)
                        .tool(receipt)
                        .gateTool(INVOICE)
                        .responder(
                                request ->
                                        request.hasToolResult()
                                                ? ScriptedLlm.text("Both are waiting on you.")
                                                : new ScriptedLlm.Reply(
                                                        "",
                                                        List.of(
                                                                new AssistantMessage.ToolCall(
                                                                        "c1",
                                                                        "function",
                                                                        INVOICE,
                                                                        "{\"to\":\"a\"}"),
                                                                new AssistantMessage.ToolCall(
                                                                        "c2",
                                                                        "function",
                                                                        RECEIPT,
                                                                        "{\"to\":\"b\"}")),
                                                        "tool_calls"))
                        .build();

        harness.chat(GOAL);

        List<ApprovalRequest> requests = harness.approvalRequests();
        assertAll(
                () -> assertEquals(0, invoice.executions(), "gated: not run before approval"),
                () -> assertEquals(0, receipt.executions(), "held behind the park: not run either"),
                () -> assertEquals(2, requests.size(), "both calls are recorded: " + requests),
                () ->
                        assertEquals(
                                List.of("mcp_tool." + INVOICE, "mcp_tool." + RECEIPT),
                                requests.stream().map(ApprovalRequest::getActionType).toList()),
                () ->
                        assertTrue(
                                requests.get(1).getPayloadJson() != null
                                        && requests.get(1).getPayloadJson().contains(RECEIPT),
                                "the sibling is recorded as its own pending approval with its args: "
                                        + requests.get(1).getPayloadJson()),
                () ->
                        assertEquals(
                                1,
                                harness.llm().modelCalls(),
                                "the turn ends on the park - the model is never called to close it"));

        for (ApprovalRequest request : requests) {
            harness.approvalService().approve(request.getId(), ApprovalTurnHarness.APPROVER);
        }

        // The request row's status flip happens on the executor path: under load it can still
        // read PENDING a moment after approve() returned. Give the propagation a moment instead
        // of asserting on the first read.
        assertTrue(
                harness.awaitRequestStatuses(ApprovalStatus.APPROVED, requests.size()),
                "all requests landed APPROVED: " + harness.requestStatuses());

        assertAll(
                () -> assertEquals(1, invoice.executions()),
                () -> assertEquals(1, receipt.executions(), "the queued sibling ran once approved"),
                () -> assertEquals(List.of("{\"to\":\"b\"}"), receipt.argumentsSeen()),
                () ->
                        assertTrue(
                                harness.requestStatuses().stream()
                                        .allMatch(s -> s == ApprovalStatus.APPROVED),
                                harness.requestStatuses().toString()),
                () -> {
                    List<ChatTurn> history = harness.historyTheNextTurnWillSee();
                    assertTrue(
                            history.stream()
                                    .anyMatch(
                                            t ->
                                                    t.content()
                                                            .contains(
                                                                    "'" + RECEIPT + "' completed")),
                            "the sibling's outcome is in the history the next turn reads: "
                                    + history);
                });
    }

    // ------------------------------------------------------------------- a failed recording

    @Test
    void whenTheApprovalRequestCannotBeRecordedTheModelGetsAnErrorResultAndTheTurnIsNotStuck() {
        RecordingTool lookup = tool(LOOKUP, "found it");
        harness =
                ApprovalTurnHarness.builder()
                        .continuationPolicy(ApprovalParkedTurnE2ETest::everyModel)
                        .tool(tool(INVOICE, "sent"))
                        .tool(lookup)
                        .gateTool(INVOICE)
                        .responder(
                                request -> {
                                    if (request.number() == 1) {
                                        return ScriptedLlm.callTool("c1", INVOICE, "{}");
                                    }
                                    if (request.number() == 2) {
                                        return ScriptedLlm.callTool("c2", LOOKUP, "{}");
                                    }
                                    return ScriptedLlm.text(
                                            "Could not submit the invoice; looked it up instead.");
                                })
                        .build();
        when(harness.approvals().requestRepository().save(any(ApprovalRequest.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        harness.chat(GOAL);

        List<String> toolResults = harness.llm().lastRequest().toolResults();
        assertAll(
                () -> assertEquals(0, harness.approvalRequests().size(), "nothing was recorded"),
                () ->
                        assertTrue(
                                toolResults.stream()
                                        .anyMatch(
                                                r ->
                                                        r.contains(INVOICE)
                                                                && r.contains("NOT executed")
                                                                && r.toLowerCase()
                                                                        .contains(
                                                                                "could not be recorded")),
                                "the model is told plainly that nothing is waiting: "
                                        + toolResults),
                () ->
                        assertEquals(
                                1,
                                lookup.executions(),
                                "the turn is not stuck 'parked': the next tool call runs"),
                () ->
                        assertFalse(
                                toolResults.stream()
                                        .anyMatch(
                                                r ->
                                                        r.contains(LOOKUP)
                                                                && r.contains(
                                                                        "waiting for human approval")),
                                toolResults.toString()));
    }

    // ---------------------------------------------------------------------- the step limit

    /**
     * Partial text has already streamed when the tool-step ceiling is hit. That stop used to be
     * swallowed by the half-response closer and the turn "completed" normally with a truncation
     * marker; it must reach the caller as the error it is.
     */
    @Test
    void theToolStepLimitIsNotTurnedIntoANormalCompletionAfterPartialText() {
        harness =
                ApprovalTurnHarness.builder()
                        .continuationPolicy(ApprovalParkedTurnE2ETest::everyModel)
                        .tool(tool(LOOKUP, "found"))
                        .maxToolCallsPerTurn(1)
                        .responder(
                                request ->
                                        new ScriptedLlm.Reply(
                                                "Looking. ",
                                                List.of(
                                                        new AssistantMessage.ToolCall(
                                                                "c" + request.number(),
                                                                "function",
                                                                LOOKUP,
                                                                "{\"n\":"
                                                                        + request.number()
                                                                        + "}")),
                                                "tool_calls"))
                        .build();

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> harness.chat(GOAL));

        Throwable cause = thrown;
        boolean sawStepLimit = false;
        while (cause != null) {
            sawStepLimit |= cause instanceof ToolStepLimitExceededException;
            cause = cause.getCause();
        }
        assertTrue(sawStepLimit, "expected the step-limit error to surface, got: " + thrown);
    }
}
