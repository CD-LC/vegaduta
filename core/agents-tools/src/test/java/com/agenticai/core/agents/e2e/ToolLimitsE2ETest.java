package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.guardrails.ToolBudgetExhausted;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The tool-call budget and the size/shape of tool results, run through the real turn pipeline and the
 * real guard chain with a scripted model (the same harness as the approval tests): what the MODEL is
 * actually shown, which is where these defects hid.
 */
class ToolLimitsE2ETest {

    private static final String GOAL = "Collect the build facts.";
    private static final String LOOKUP = "lookup";

    private ApprovalTurnHarness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    private static boolean sawBudgetNotice(ScriptedLlm.Request request) {
        return request.toolResults().stream().anyMatch(ToolBudgetExhausted::isBudgetExhausted);
    }

    /** Calls LOOKUP until a tool result says the budget is gone, then writes the final answer. */
    private static ScriptedLlm.Responder callsUntilToldToStop(String finalAnswer) {
        return request ->
                request.toolResults().stream().anyMatch(r -> r.contains("tool_budget_exhausted"))
                        ? ScriptedLlm.text(finalAnswer)
                        : ScriptedLlm.callTool("c" + request.number(), LOOKUP, "{\"n\":" + request.number() + "}");
    }

    // ------------------------------------------------------------------ the step ceiling

    @Test
    void aTurnPastItsToolCallCeilingIsToldSoAsAToolResultAndTheModelCanStillAnswer() {
        RecordingTool lookup = RecordingTool.returning(LOOKUP, "Looks things up", "{\"hit\":true}");
        harness =
                ApprovalTurnHarness.builder()
                        .tool(lookup)
                        .maxToolCallsPerTurn(2)
                        .responder(callsUntilToldToStop("Here is what I found so far."))
                        .build();

        String reply = harness.chat(GOAL);

        assertTrue(reply.contains("Here is what I found so far."), reply);
        assertEquals(2, lookup.executions(), "only the two calls inside the ceiling ran");
        ScriptedLlm.Request told =
                harness.llm().requests().stream()
                        .filter(ToolLimitsE2ETest::sawBudgetNotice)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "the model was never shown the budget notice: "
                                                        + harness.llm().lastRequest().conversation()));
        String notice =
                told.toolResults().stream()
                        .filter(ToolBudgetExhausted::isBudgetExhausted)
                        .findFirst()
                        .orElseThrow();
        assertTrue(notice.contains("\"error\":\"tool_budget_exhausted\""), notice);
        assertTrue(notice.contains("Do not call any more tools"), notice);
        assertFalse(told.transcript().contains("This tool call failed"), "not the generic failure text");
    }

    @Test
    void theBufferedDepthsGetTheSameBudgetNoticeAndFinishTheReply() {
        RecordingTool lookup = RecordingTool.returning(LOOKUP, "Looks things up", "{\"hit\":true}");
        harness =
                ApprovalTurnHarness.builder()
                        .tool(lookup)
                        .maxToolCallsPerTurn(2)
                        .depth(com.agenticai.core.agents.core.ReasoningDepth.CAREFUL)
                        .responder(callsUntilToldToStop("Buffered answer from what I have."))
                        .build();

        String reply = harness.chat(GOAL);

        assertTrue(reply.contains("Buffered answer from what I have."), reply);
        assertEquals(2, lookup.executions());
        assertTrue(harness.llm().requests().stream().anyMatch(ToolLimitsE2ETest::sawBudgetNotice));
    }

    @Test
    void aModelThatKeepsCallingAfterTheNoticeIsStoppedWithTheStepLimitError() {
        RecordingTool lookup = RecordingTool.returning(LOOKUP, "Looks things up", "{\"hit\":true}");
        harness =
                ApprovalTurnHarness.builder()
                        .tool(lookup)
                        .maxToolCallsPerTurn(1)
                        .stepLimits(limits -> limits.setGraceCalls(2))
                        .responder(
                                request ->
                                        ScriptedLlm.callTool(
                                                "c" + request.number(), LOOKUP, "{\"n\":" + request.number() + "}"))
                        .build();

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> harness.chat(GOAL));

        Throwable cause = thrown;
        boolean sawStepLimit = false;
        while (cause != null) {
            sawStepLimit |= cause instanceof ToolStepLimitExceededException;
            cause = cause.getCause();
        }
        assertTrue(sawStepLimit, "expected the step-limit error to surface, got: " + thrown);
        assertEquals(1, lookup.executions());
        long notices = harness.llm().requests().stream().filter(ToolLimitsE2ETest::sawBudgetNotice).count();
        assertTrue(notices >= 1, "the model was told before it was stopped");
    }

    // ----------------------------------------------------- rounds versus calls, both configured

    @Test
    void theHiddenTwentyFiveRoundCapNoLongerBeatsAConfiguredCallCeiling() {
        RecordingTool lookup = RecordingTool.returning(LOOKUP, "Looks things up", "{\"hit\":true}");
        harness =
                ApprovalTurnHarness.builder()
                        .tool(lookup)
                        .maxToolCallsPerTurn(40)
                        .responder(
                                request ->
                                        request.toolResults().size() < 30
                                                ? ScriptedLlm.callTool(
                                                        "c" + request.number(),
                                                        LOOKUP,
                                                        "{\"n\":" + request.number() + "}")
                                                : ScriptedLlm.text("All 30 lookups are done."))
                        .build();

        String reply = harness.chat(GOAL);

        assertEquals(30, lookup.executions(), "30 rounds are inside a 40-call ceiling; 25 used to end it");
        assertTrue(reply.contains("All 30 lookups are done."), reply);
        assertTrue(harness.llm().requests().stream().noneMatch(ToolLimitsE2ETest::sawBudgetNotice));
    }

    @Test
    void anExplicitRoundCapStillEndsTheLoopAndTheModelGetsTheSameStructuredResult() {
        RecordingTool lookup = RecordingTool.returning(LOOKUP, "Looks things up", "{\"hit\":true}");
        harness =
                ApprovalTurnHarness.builder()
                        .tool(lookup)
                        .maxToolCallsPerTurn(40)
                        .toolLoop(loop -> loop.setMaxRoundsPerInvocation(3))
                        .responder(callsUntilToldToStop("Stopped after three rounds."))
                        .build();

        String reply = harness.chat(GOAL);

        assertEquals(3, lookup.executions());
        assertTrue(reply.contains("Stopped after three rounds."), reply);
        assertTrue(harness.llm().requests().stream().anyMatch(ToolLimitsE2ETest::sawBudgetNotice));
    }

    // ------------------------------------------------------------------- what a result looks like

    @Test
    void aResultOverTheCapReachesTheModelTruncatedWithAMarkerNotWithheld() {
        String huge = "row-data ".repeat(3_500); // 31,500 characters
        RecordingTool report = RecordingTool.returning("read_report", "Reads the report", huge);
        harness =
                ApprovalTurnHarness.builder()
                        .tool(report)
                        .responder(
                                request ->
                                        request.hasToolResult()
                                                ? ScriptedLlm.text("The report starts with row-data.")
                                                : ScriptedLlm.callTool("c1", "read_report", "{}"))
                        .build();

        String reply = harness.chat(GOAL);

        assertTrue(reply.contains("The report starts with row-data."), reply);
        String shown = harness.llm().lastRequest().toolResults().get(0);
        assertFalse(shown.contains("failed validation"), shown);
        assertTrue(shown.startsWith("{\"result\":\"row-data row-data"), shown.substring(0, 80));
        assertTrue(shown.contains("of 31500 characters"), "the marker states the original length");
        int cap = new ToolOutputProperties().getMaxChars();
        assertTrue(shown.length() < cap + 200, "bounded near the cap, got " + shown.length());
    }

    @Test
    void aPerToolLimitFromConfigurationReachesTheModel() {
        RecordingTool report = RecordingTool.returning("read_report", "Reads the report", "z".repeat(10_000));
        harness =
                ApprovalTurnHarness.builder()
                        .tool(report)
                        .toolOutput(limits -> limits.getPerTool().put("read_report", 1_000))
                        .responder(
                                request ->
                                        request.hasToolResult()
                                                ? ScriptedLlm.text("Read it.")
                                                : ScriptedLlm.callTool("c1", "read_report", "{}"))
                        .build();

        harness.chat(GOAL);

        String shown = harness.llm().lastRequest().toolResults().get(0);
        assertTrue(shown.length() < 1_200, "got " + shown.length());
        assertTrue(shown.contains("of 10000 characters"), shown);
    }

    @Test
    void plainTextThatStartsWithABracketReachesTheModelInsteadOfBeingWithheld() {
        String log = "[INFO] 3 files changed\n[INFO] build done";
        RecordingTool build = RecordingTool.returning("run_build", "Runs the build", log);
        harness =
                ApprovalTurnHarness.builder()
                        .tool(build)
                        .responder(
                                request ->
                                        request.hasToolResult()
                                                ? ScriptedLlm.text("The build finished.")
                                                : ScriptedLlm.callTool("c1", "run_build", "{}"))
                        .build();

        harness.chat(GOAL);

        List<String> results = harness.llm().lastRequest().toolResults();
        assertEquals(1, results.size());
        assertTrue(results.get(0).contains("[INFO] 3 files changed"), results.get(0));
        assertFalse(results.get(0).contains("failed validation"), results.get(0));
        assertFalse(results.get(0).contains("was not returned to the model"), results.get(0));
    }
}
