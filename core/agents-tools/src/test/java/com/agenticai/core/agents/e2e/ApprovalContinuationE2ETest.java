package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestAlreadyDecidedException;
import com.agenticai.core.approval.ApprovalStatus;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * "A tool needs approval, the human decides, does the agent finish the job?" - the end-to-end
 * contract the platform advertises for human-in-the-loop, run through the real turn pipeline, real
 * guard chain, real approval service/executor, real continuation launcher and real session memory
 * ({@link ApprovalTurnHarness}); only the LLM is scripted.
 *
 * <p>The client sends NOTHING after the decision: the server starts the continuation turn itself
 * ({@code ApprovalContinuationLauncher}) and the tests wait for it the way the web does, by
 * reading the stored conversation ({@link ApprovalTurnHarness#awaitContinuation}).
 *
 * <p>The scripted model is a policy, not a fixed script: it answers from what it was SHOWN. Shown
 * the approved tool's output it finishes the task; shown nothing it can only do what a model with
 * no evidence does, ask for the tool again. So "the model re-asked" in a failure message means "the
 * model was never given the output", which is the real-world symptom.
 */
class ApprovalContinuationE2ETest {

    private static final String GOAL =
            "Send invoice INV-7731 to billing@acme.test and tell me the confirmation number.";
    private static final String CONFIRMATION = "CONF-9042";
    private static final String TOOL_OUTPUT =
            "Invoice INV-7731 sent to billing@acme.test (confirmation " + CONFIRMATION + ")";
    private static final String TOOL = "send_invoice";
    private static final String FINAL_ANSWER =
            "Done. Invoice INV-7731 was sent; the confirmation number is " + CONFIRMATION + ".";

    private ApprovalTurnHarness harness;
    private RecordingTool sendInvoice;

    /**
     * What a sensible model does with what it is shown: finish if it has the tool's output anywhere
     * in its context, tell the user it is waiting if the only tool result it has is a park notice,
     * otherwise call the tool.
     */
    private static ScriptedLlm.Responder sensibleModel() {
        return request -> {
            if (request.mentions(CONFIRMATION)) {
                return ScriptedLlm.text(FINAL_ANSWER);
            }
            if (request.hasToolResult()) {
                return ScriptedLlm.text(
                        "I'm waiting for your approval before the invoice can be sent.");
            }
            return ScriptedLlm.callTool(
                    "call-" + request.number(), TOOL, "{\"to\":\"billing@acme.test\"}");
        };
    }

    private ApprovalTurnHarness gatedInvoiceAgent() {
        sendInvoice =
                RecordingTool.returning(
                        TOOL,
                        "Sends an invoice by email and returns its confirmation.",
                        TOOL_OUTPUT);
        harness =
                ApprovalTurnHarness.builder()
                        .tool(sendInvoice)
                        .gateTool(TOOL)
                        .responder(sensibleModel())
                        .build();
        return harness;
    }

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    // --------------------------------------------------------------- harness fidelity (green)

    @Test
    void aGatedCallParksTheTurnWithoutRunningTheToolAndRecordsOneRequest() {
        ApprovalTurnHarness h = gatedInvoiceAgent();

        String reply = h.chat(GOAL);

        assertAll(
                () ->
                        assertEquals(
                                0,
                                sendInvoice.executions(),
                                "a gated call must not run before approval"),
                () ->
                        assertEquals(
                                1,
                                h.approvalRequests().size(),
                                "exactly one approval request is recorded"),
                () -> assertEquals(ApprovalStatus.PENDING, h.approvalRequests().get(0).getStatus()),
                () -> assertEquals("mcp_tool." + TOOL, h.approvalRequests().get(0).getActionType()),
                () ->
                        assertTrue(
                                reply.contains("waiting for your approval"),
                                "the user is told it is waiting: " + reply),
                () ->
                        assertTrue(
                                h.llm().requests().get(0).offeredTools().contains(TOOL),
                                "the model was offered the tool"));
    }

    @Test
    void approvingRunsTheToolExactlyOnceThroughTheRealExecutor() {
        ApprovalTurnHarness h = gatedInvoiceAgent();
        h.chat(GOAL);

        ApprovalRequest approved = h.approvePending();

        assertAll(
                () ->
                        assertEquals(
                                1,
                                sendInvoice.executions(),
                                "the approved call runs once, with the recorded arguments"),
                () ->
                        assertEquals(
                                List.of("{\"to\":\"billing@acme.test\"}"),
                                sendInvoice.argumentsSeen()),
                () -> assertEquals(ApprovalStatus.APPROVED, approved.getStatus()),
                () ->
                        assertTrue(
                                approved.getExecutionResult() != null
                                        && approved.getExecutionResult().contains(CONFIRMATION),
                                "the executor persisted the tool output on the request"));
    }

    // ------------------------------------------------------------------ the contract (RED today)

    /**
     * The headline end-to-end contract. Every assertion is reported (assertAll), so a failure shows
     * exactly which parts of the contract are broken.
     *
     * <p>Nothing is sent by the client after the approval: the server continues the ORIGINAL task
     * on its own, restating the goal and handing the model the approved tool's output.
     */
    @Test
    void approvedToolResultReachesTheModelAndTheAgentFinishesTheTask() {
        ApprovalTurnHarness h = gatedInvoiceAgent();

        // Turn 1: the model calls the gated tool, the turn parks.
        h.chat(GOAL);
        assertEquals(0, sendInvoice.executions(), "precondition: the gated call has not run");
        ApprovalRequest parked = h.pendingApproval();
        int modelCallsBeforeApproval = h.llm().modelCalls();

        // The human approves through the real approval service. No client message follows.
        h.approvePending();
        assertEquals(1, sendInvoice.executions(), "precondition: approval ran the tool");
        String finalReply = h.awaitContinuation();

        ScriptedLlm.Request nextRequest = h.llm().requests().get(modelCallsBeforeApproval);
        long approvalRequestsForTheTool =
                h.approvalRequests().stream()
                        .filter(r -> r.getActionType().equals("mcp_tool." + TOOL))
                        .count();

        assertAll(
                () ->
                        assertEquals(
                                1,
                                sendInvoice.executions(),
                                "(a) the approved tool must have executed exactly once in total"),
                () ->
                        assertTrue(
                                nextRequest.mentions(CONFIRMATION),
                                "(b) the approved tool's output ("
                                        + CONFIRMATION
                                        + ") must be visible to the model in the first request of the continuation."
                                        + " The model was sent (system messages omitted):\n"
                                        + nextRequest.conversation()),
                () ->
                        assertTrue(
                                nextRequest.userTexts().stream()
                                        .anyMatch(
                                                t ->
                                                        t.startsWith(
                                                                        ApprovalTurnHarness
                                                                                .CONTINUATION_MARKER)
                                                                && t.contains(GOAL)
                                                                && t.contains(CONFIRMATION)),
                                "(c) the continuation message itself restates the original goal and carries the full"
                                        + " result; the model's user messages were: "
                                        + nextRequest.userTexts()),
                () ->
                        assertTrue(
                                finalReply.contains(CONFIRMATION),
                                "(d) the agent must complete the task with a final answer containing the confirmation"
                                        + " number; the user got: \""
                                        + finalReply
                                        + "\""),
                () ->
                        assertEquals(
                                1L,
                                approvalRequestsForTheTool,
                                "(e) no duplicate side effect: the model must not have to ask for the same tool again; it"
                                        + " did, so "
                                        + approvalRequestsForTheTool
                                        + " approval requests exist for it"),
                () ->
                        assertFalse(
                                h.approvalRequests().stream()
                                        .anyMatch(r -> r.getStatus() == ApprovalStatus.PENDING),
                                "(e) no approval request may be left PENDING by a task that was already approved (the first"
                                        + " request was "
                                        + parked.getId()
                                        + ")"));
    }

    /**
     * The only copy of a long tool result the model used to get was a 500-character note. The
     * result now arrives whole (up to {@code app.approvals.continuation.max-result-chars}).
     */
    @Test
    void aLongToolResultReachesTheModelBeyondTheOldFiveHundredCharacterNote() {
        String tail = "TAIL-" + CONFIRMATION;
        String longOutput = "BEGIN " + "lorem ipsum ".repeat(300) + tail;
        assertTrue(longOutput.length() > 3_000);
        sendInvoice = RecordingTool.returning(TOOL, "Sends an invoice by email.", longOutput);
        harness =
                ApprovalTurnHarness.builder()
                        .tool(sendInvoice)
                        .gateTool(TOOL)
                        .responder(
                                request ->
                                        request.mentions(tail)
                                                ? ScriptedLlm.text("Done, the receipt ends in " + tail)
                                                : request.hasToolResult()
                                                        ? ScriptedLlm.text("Waiting for approval.")
                                                        : ScriptedLlm.callTool(
                                                                "c" + request.number(),
                                                                TOOL,
                                                                "{\"to\":\"billing@acme.test\"}"))
                        .build();
        harness.chat(GOAL);
        int before = harness.llm().modelCalls();

        harness.approvePending();
        String reply = harness.awaitContinuation();

        assertAll(
                () -> assertTrue(reply.contains(tail), "the model never saw the end of the result: " + reply),
                () ->
                        assertTrue(
                                harness.llm().requests().get(before).mentions(tail),
                                "the first request of the continuation lacked the tail of the tool result"),
                () -> assertEquals(1, sendInvoice.executions()));
    }

    /** A denial continues the turn with a clear "denied" result so the agent can adapt. */
    @Test
    void aDeniedApprovalContinuesTheTurnWithAClearDeniedResult() {
        sendInvoice = RecordingTool.returning(TOOL, "Sends an invoice by email.", TOOL_OUTPUT);
        harness =
                ApprovalTurnHarness.builder()
                        .tool(sendInvoice)
                        .gateTool(TOOL)
                        .responder(
                                request ->
                                        request.mentions("DENIED")
                                                ? ScriptedLlm.text(
                                                        "Understood: I did not send the invoice. I will leave it for you to"
                                                                + " send.")
                                                : request.hasToolResult()
                                                        ? ScriptedLlm.text("Waiting for approval.")
                                                        : ScriptedLlm.callTool(
                                                                "c" + request.number(),
                                                                TOOL,
                                                                "{\"to\":\"billing@acme.test\"}"))
                        .build();
        harness.chat(GOAL);
        int before = harness.llm().modelCalls();

        ApprovalRequest denied = harness.rejectPending("Not before the audit is finished");
        String reply = harness.awaitContinuation();

        ScriptedLlm.Request next = harness.llm().requests().get(before);
        assertAll(
                () -> assertEquals(ApprovalStatus.REJECTED, denied.getStatus()),
                () -> assertEquals(0, sendInvoice.executions(), "a denied call must never run"),
                () ->
                        assertTrue(
                                next.mentions("Not before the audit is finished"),
                                "the denial reason reaches the model: " + next.conversation()),
                () -> assertTrue(next.mentions(GOAL), "the goal is restated"),
                () ->
                        assertTrue(
                                reply.contains("did not send the invoice"),
                                "the agent adapted instead of hanging: " + reply),
                () ->
                        assertEquals(
                                1,
                                harness.approvalRequests().size(),
                                "the agent must not ask for the denied action again"));
    }

    /**
     * Approving twice must neither run the tool nor start a continuation a second time: the second
     * decision is refused by the gate, and nothing downstream of it fires.
     */
    @Test
    void approvingTwiceRunsTheToolAndTheContinuationOnce() throws Exception {
        ApprovalTurnHarness h = gatedInvoiceAgent();
        h.chat(GOAL);
        ApprovalRequest parked = h.pendingApproval();

        h.approvePending();
        h.awaitContinuation();
        int modelCallsAfterFirstContinuation = h.llm().modelCalls();
        long continuationTurns = continuationTurnsStored(h);

        assertThrows(
                ApprovalRequestAlreadyDecidedException.class,
                () -> h.approvalService().approve(parked.getId(), ApprovalTurnHarness.APPROVER));
        assertThrows(
                ApprovalRequestAlreadyDecidedException.class,
                () ->
                        h.approvalService()
                                .reject(parked.getId(), ApprovalTurnHarness.APPROVER, "changed my mind"));
        Thread.sleep(400);

        assertAll(
                () -> assertEquals(1, sendInvoice.executions(), "the tool ran once"),
                () ->
                        assertEquals(
                                modelCallsAfterFirstContinuation,
                                h.llm().modelCalls(),
                                "no second continuation turn reached the model"),
                () -> assertEquals(1L, continuationTurns),
                () -> assertEquals(1L, continuationTurnsStored(h)));
    }

    /**
     * The parked turn used its whole tool budget; the continuation is a new turn with a new one, so
     * it can make the calls the rest of the task needs.
     */
    @Test
    void theContinuationTurnHasAFreshToolStepBudget() {
        RecordingTool lookup =
                RecordingTool.returning("lookup_rate", "Looks up an exchange rate.", "rate 1.07");
        sendInvoice = RecordingTool.returning(TOOL, "Sends an invoice by email.", TOOL_OUTPUT);
        harness =
                ApprovalTurnHarness.builder()
                        .tool(lookup)
                        .tool(sendInvoice)
                        .gateTool(TOOL)
                        .maxToolCallsPerTurn(2)
                        .responder(
                                request -> {
                                    boolean continuation = request.mentions(CONFIRMATION);
                                    long lookups =
                                            request.toolResults().stream()
                                                    .filter(r -> r.contains("rate 1.07"))
                                                    .count();
                                    if (continuation) {
                                        return lookups < 2
                                                ? ScriptedLlm.callTool(
                                                        "l" + request.number(),
                                                        "lookup_rate",
                                                        "{\"to\":\"eur" + lookups + "\"}")
                                                : ScriptedLlm.text(FINAL_ANSWER);
                                    }
                                    if (request.toolResults().stream()
                                            .anyMatch(r -> r.contains("approval"))) {
                                        return ScriptedLlm.text("Waiting for your approval.");
                                    }
                                    return lookups < 1
                                            ? ScriptedLlm.callTool(
                                                    "l" + request.number(), "lookup_rate", "{\"to\":\"usd\"}")
                                            : ScriptedLlm.callTool(
                                                    "s" + request.number(),
                                                    TOOL,
                                                    "{\"to\":\"billing@acme.test\"}");
                                })
                        .build();
        harness.chat(GOAL);
        harness.approvePending();

        String reply = harness.awaitContinuation();

        assertAll(
                () -> assertEquals(FINAL_ANSWER, reply),
                () ->
                        assertEquals(
                                3,
                                lookup.executions(),
                                "one lookup in the parked turn and two in the continuation: the budget of 2 steps was"
                                        + " not shared"));
    }

    // ------------------------------------------------------------------- which tools are offered

    private static RecordingTool plainTool(String name, String description, String output) {
        return RecordingTool.returning(name, description, output);
    }

    /**
     * The continuation message names none of the words of the task, so tool selection against it
     * (the old "Approved. Carry on" turn) trimmed the catalog to a handful of tools and dropped the
     * one the NEXT step needs. Selection runs on the original goal instead.
     */
    @Test
    void theToolTheNextStepNeedsIsOfferedBecauseSelectionRunsOnTheOriginalGoal() {
        String goal =
                "Send invoice INV-7731 to billing@acme.test, then archive the invoice record and tell me the"
                        + " confirmation number.";
        RecordingTool archive = plainTool("archive_invoice", "Archives an invoice record.", "archived INV-7731");
        sendInvoice = plainTool(TOOL, "Sends an invoice by email.", TOOL_OUTPUT);
        harness =
                ApprovalTurnHarness.builder()
                        .tool(sendInvoice)
                        .tool(plainTool("weather_forecast", "Gets a weather forecast for a city.", "sunny"))
                        .tool(plainTool("translate_text", "Translates text between languages.", "hola"))
                        .tool(plainTool("create_meeting", "Creates a calendar meeting.", "booked"))
                        .tool(plainTool("resize_image", "Resizes an image file.", "resized"))
                        .tool(archive)
                        .gateTool(TOOL)
                        .responder(
                                request -> {
                                    boolean inContinuation = request.mentions(CONFIRMATION);
                                    if (!inContinuation) {
                                        return request.hasToolResult()
                                                ? ScriptedLlm.text("Waiting for your approval.")
                                                : ScriptedLlm.callTool(
                                                        "s" + request.number(), TOOL, "{\"to\":\"billing@acme.test\"}");
                                    }
                                    boolean archived =
                                            request.toolResults().stream().anyMatch(r -> r.contains("archived INV-7731"));
                                    if (archived) {
                                        return ScriptedLlm.text(FINAL_ANSWER + " The record is archived.");
                                    }
                                    return request.offeredTools().contains("archive_invoice")
                                            ? ScriptedLlm.callTool("a" + request.number(), "archive_invoice", "{}")
                                            : ScriptedLlm.text("I cannot archive the record: that tool is not available.");
                                })
                        .build();
        harness.chat(goal);
        harness.approvePending();

        String reply = harness.awaitContinuation();

        List<String> selectedFor = harness.toolSelectionMessages();
        assertAll(
                () -> assertEquals(1, archive.executions(), "the next step's tool was used: " + reply),
                () -> assertTrue(reply.contains("archived"), reply),
                () -> assertEquals(1, sendInvoice.executions()),
                () -> assertEquals(2, selectedFor.size(), "one tool resolution per turn: " + selectedFor),
                () ->
                        assertEquals(
                                goal,
                                selectedFor.get(1),
                                "the continuation selects tools for the person's original goal, not for the"
                                        + " platform-written message that carries the tool output"));
    }

    /**
     * A tool the conversation used earlier stays on offer for the continuation even when the goal
     * does not match it: the model's next step may be to use it again.
     */
    @Test
    void aToolTheConversationAlreadyUsedStaysOfferedWhateverTheGoalMatches() {
        RecordingTool lookup = plainTool("lookup_customer", "Finds a customer account by name.", "customer-record ACME");
        sendInvoice = plainTool(TOOL, "Sends an invoice by email.", TOOL_OUTPUT);
        harness =
                ApprovalTurnHarness.builder()
                        .tool(sendInvoice)
                        .tool(plainTool("weather_forecast", "Gets a weather forecast for a city.", "sunny"))
                        .tool(plainTool("translate_text", "Translates text between languages.", "hola"))
                        .tool(plainTool("create_meeting", "Creates a calendar meeting.", "booked"))
                        .tool(plainTool("resize_image", "Resizes an image file.", "resized"))
                        .tool(lookup)
                        .gateTool(TOOL)
                        .responder(
                                request -> {
                                    List<String> users = request.userTexts();
                                    String last = users.get(users.size() - 1);
                                    boolean customerSeen =
                                            request.toolResults().stream().anyMatch(r -> r.contains("customer-record"));
                                    if (last.startsWith(ApprovalTurnHarness.CONTINUATION_MARKER)) {
                                        if (customerSeen) {
                                            return ScriptedLlm.text("Verified the customer; " + FINAL_ANSWER);
                                        }
                                        return request.offeredTools().contains("lookup_customer")
                                                ? ScriptedLlm.callTool("v" + request.number(), "lookup_customer", "{\"to\":\"acme\"}")
                                                : ScriptedLlm.text("I cannot verify the customer: no lookup tool.");
                                    }
                                    if (last.startsWith("Look up")) {
                                        return request.hasToolResult()
                                                ? ScriptedLlm.text("Found the ACME account.")
                                                : ScriptedLlm.callTool("l" + request.number(), "lookup_customer", "{\"to\":\"acme\"}");
                                    }
                                    return request.hasToolResult()
                                            ? ScriptedLlm.text("Waiting for your approval.")
                                            : ScriptedLlm.callTool("s" + request.number(), TOOL, "{\"to\":\"billing@acme.test\"}");
                                })
                        .build();
        harness.chat("Look up the account for ACME");
        // No word of this goal appears in any tool: selection has nothing to match and keeps the
        // first few tools in their stable order, so lookup_customer survives only as a tool the
        // conversation already used.
        harness.chat("Please take care of ACME's billing for me.");
        harness.approvePending();

        String reply = harness.awaitContinuation();

        assertAll(
                () -> assertTrue(reply.startsWith("Verified the customer"), reply),
                () -> assertEquals(2, lookup.executions(), "once earlier in the conversation, once in the continuation"));
    }

    /**
     * Pins the defect behind (b) at its source, independent of how a continuation is triggered: the
     * note the executor appends for the approved action must be in the history the conversation's
     * NEXT turn reads (the scoped key), not under the raw client session id.
     */
    @Test
    void approvalOutcomeNoteIsWrittenToTheHistoryTheNextTurnReads() {
        ApprovalTurnHarness h = gatedInvoiceAgent();
        h.chat(GOAL);

        h.approvePending();

        List<ChatTurn> nextTurnSees = h.historyTheNextTurnWillSee();
        boolean noteInReadHistory =
                nextTurnSees.stream()
                        .anyMatch(
                                t ->
                                        t.content() != null
                                                && t.content()
                                                        .contains(
                                                                "[approved action '" + TOOL + "'"));
        assertTrue(
                noteInReadHistory,
                "the '[approved action ...]' note must be stored under the scoped session key "
                        + h.scopedMemoryKey()
                        + " that the next turn reads. Turns found there: "
                        + describe(nextTurnSees)
                        + "; turns under the raw session id '"
                        + h.sessionId()
                        + "': "
                        + describe(h.historyUnderRawSessionId())
                        + "; Redis list keys: "
                        + h.redis().listKeys());
    }

    private static long continuationTurnsStored(ApprovalTurnHarness h) {
        return h.historyTheNextTurnWillSee().stream()
                .filter(
                        t ->
                                t.content() != null
                                        && t.content().startsWith(ApprovalTurnHarness.CONTINUATION_MARKER))
                .count();
    }

    private static String describe(List<ChatTurn> turns) {
        StringBuilder out = new StringBuilder("[");
        for (ChatTurn t : turns) {
            out.append(t.role()).append(": ").append(t.content()).append(" | ");
        }
        return out.append(']').toString();
    }
}
