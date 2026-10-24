package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.limits.AgentRuntimeProperties;
import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.agents.runtime.RuntimeSetting;
import com.agenticai.core.agents.turn.ContinuationProperties;
import com.agenticai.core.guardrails.ToolBudgetExhausted;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * An agent's (or a tenant's) runtime limits reach every path that enforces one, run through the real turn
 * pipeline and the real guard chain with a scripted model: the platform ceiling is 15 tool calls, a 240 s
 * buffered turn, 20,000 characters of tool output and 8 continuation passes, and each test changes ONE layer
 * and watches what the model is actually shown / what actually runs.
 */
class AgentRuntimeLimitsE2ETest {

    private static final String GOAL = "Collect the build facts.";
    private static final String LOOKUP = "lookup";

    private ApprovalTurnHarness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    private static RuntimeLimits steps(int n) {
        return RuntimeLimits.none().with(RuntimeSetting.MAX_TOOL_STEPS, n);
    }

    private static RuntimeLimits secondsFor(RuntimeSetting setting, int n) {
        return RuntimeLimits.none().with(setting, n);
    }

    private static boolean sawBudgetNotice(ScriptedLlm.Request request) {
        return request.toolResults().stream().anyMatch(ToolBudgetExhausted::isBudgetExhausted);
    }

    /** Calls LOOKUP until a tool result says the budget is gone, then writes the final answer. */
    private static ScriptedLlm.Responder callsUntilToldToStop() {
        return request ->
                request.toolResults().stream().anyMatch(ToolBudgetExhausted::isBudgetExhausted)
                        ? ScriptedLlm.text("Here is what I found so far.")
                        : ScriptedLlm.callTool("c" + request.number(), LOOKUP, "{\"n\":" + request.number() + "}");
    }

    private static RecordingTool lookup() {
        return RecordingTool.returning(LOOKUP, "Looks things up", "{\"hit\":true}");
    }

    // ------------------------------------------------------------------------ the tool-step ceiling

    @Test
    void anAgentCeilingBelowThePlatformsStopsAFastTurnAtTheAgentsNumber() {
        RecordingTool lookup = lookup();
        harness = ApprovalTurnHarness.builder().tool(lookup).maxToolCallsPerTurn(15)
                .agentLimits(steps(3)).responder(callsUntilToldToStop()).build();

        String reply = harness.chat(GOAL);

        assertTrue(reply.contains("Here is what I found so far."), reply);
        assertEquals(3, lookup.executions(), "the platform allows 15; this agent's own ceiling is 3");
        assertTrue(harness.llm().requests().stream().anyMatch(AgentRuntimeLimitsE2ETest::sawBudgetNotice));
    }

    @Test
    void theBufferedDepthsHonourTheAgentsCeilingToo() {
        RecordingTool lookup = lookup();
        harness = ApprovalTurnHarness.builder().tool(lookup).maxToolCallsPerTurn(15)
                .depth(ReasoningDepth.CAREFUL).agentLimits(steps(3)).responder(callsUntilToldToStop()).build();

        String reply = harness.chat(GOAL);

        assertTrue(reply.contains("Here is what I found so far."), reply);
        assertEquals(3, lookup.executions());
    }

    @Test
    void anAgentCeilingAboveThePlatformsIsNotStoppedByThePlatformsCallLimitOrItsRoundBackstop() {
        RecordingTool lookup = lookup();
        harness = ApprovalTurnHarness.builder().tool(lookup).maxToolCallsPerTurn(15)
                .agentLimits(steps(100))
                .responder(request -> request.toolResults().size() < 60
                        ? ScriptedLlm.callTool("c" + request.number(), LOOKUP, "{\"n\":" + request.number() + "}")
                        : ScriptedLlm.text("All 60 lookups are done."))
                .build();

        String reply = harness.chat(GOAL);

        assertEquals(60, lookup.executions(), "the platform ceiling is 15 (30 autonomous) and its round backstop 41");
        assertTrue(reply.contains("All 60 lookups are done."), reply);
        assertTrue(harness.llm().requests().stream().noneMatch(AgentRuntimeLimitsE2ETest::sawBudgetNotice));
    }

    @Test
    void theTenantsDefaultAppliesToAnAgentThatSetsNoneAndTheAgentsOwnBeatsIt() {
        RecordingTool tenantOnly = lookup();
        harness = ApprovalTurnHarness.builder().tool(tenantOnly).maxToolCallsPerTurn(15)
                .tenantLimits(steps(4)).responder(callsUntilToldToStop()).build();
        harness.chat(GOAL);
        assertEquals(4, tenantOnly.executions());
        harness.close();

        RecordingTool both = lookup();
        harness = ApprovalTurnHarness.builder().tool(both).maxToolCallsPerTurn(15)
                .tenantLimits(steps(4)).agentLimits(steps(6)).responder(callsUntilToldToStop()).build();
        harness.chat(GOAL);
        assertEquals(6, both.executions());
    }

    @Test
    void anAgentWithNoOverrideRunsUnderThePlatformCeilingExactlyAsBefore() {
        RecordingTool lookup = lookup();
        harness = ApprovalTurnHarness.builder().tool(lookup).maxToolCallsPerTurn(15)
                .responder(callsUntilToldToStop()).build();

        harness.chat(GOAL);

        assertEquals(15, lookup.executions());
    }

    // -------------------------------------------------------------- a run's own cap (an agent job)

    @Test
    void aJobsRemainingToolBudgetStopsTheTurnInsideItAndCannotRaiseTheAgentsCeiling() {
        RecordingTool capped = lookup();
        harness = ApprovalTurnHarness.builder().tool(capped).maxToolCallsPerTurn(15)
                .responder(callsUntilToldToStop()).build();
        harness.chatWithCaps(GOAL, RuntimeLimits.capToolSteps(2), null);
        assertEquals(2, capped.executions(), "the job has 2 calls left; the agent alone would allow 15");
        harness.close();

        RecordingTool notRaised = lookup();
        harness = ApprovalTurnHarness.builder().tool(notRaised).maxToolCallsPerTurn(15)
                .responder(callsUntilToldToStop()).build();
        harness.chatWithCaps(GOAL, RuntimeLimits.capToolSteps(100), null);
        assertEquals(15, notRaised.executions(), "a job asking for 100 does not get more than the agent's 15");
    }

    // ----------------------------------------------------------------------------- tool output cap

    @Test
    void anAgentsToolOutputCapIsWhatTheModelIsShown() {
        String huge = "row-data ".repeat(3_500); // 31,500 characters
        RecordingTool report = RecordingTool.returning("read_report", "Reads the report", huge);
        harness = ApprovalTurnHarness.builder().tool(report)
                .agentLimits(secondsFor(RuntimeSetting.TOOL_OUTPUT_MAX_CHARS, 1_000))
                .responder(request -> request.hasToolResult()
                        ? ScriptedLlm.text("Read it.")
                        : ScriptedLlm.callTool("c1", "read_report", "{}"))
                .build();

        harness.chat(GOAL);

        String shown = harness.llm().lastRequest().toolResults().get(0);
        // The cap is on the tool's text; the result envelope adds a few characters (a platform-capped result is
        // 20,014 long for the 20,000 cap).
        assertTrue(shown.length() < 1_100, "the platform cap is 20,000; this agent's is 1,000, got " + shown.length());
        assertTrue(shown.contains("of 31500 characters"), "and the marker still says what was cut: " + shown);
    }

    // ---------------------------------------------------------------------------------- tool timeout

    @Test
    void aToolThatOutlivesTheAgentsToolTimeoutIsStoppedAndTheModelIsTold() {
        RecordingTool slow = new RecordingTool("slow_export", "Exports everything", args -> {
            sleep(Duration.ofSeconds(8));
            return "{\"exported\":true}";
        });
        harness = ApprovalTurnHarness.builder().tool(slow)
                .runtimeProperties(p -> p.getBounds().setToolTimeoutSeconds(new AgentRuntimeProperties.Range(1, 1800)))
                .agentLimits(secondsFor(RuntimeSetting.TOOL_TIMEOUT_SECONDS, 1))
                .responder(request -> request.hasToolResult()
                        ? ScriptedLlm.text("The export did not finish.")
                        : ScriptedLlm.callTool("c1", "slow_export", "{}"))
                .build();

        long started = System.nanoTime();
        String reply = harness.chat(GOAL);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        String shown = harness.llm().lastRequest().toolResults().get(0);
        assertTrue(shown.contains("did not finish within 1 seconds"), shown);
        assertTrue(reply.contains("The export did not finish."), reply);
        assertTrue(took.compareTo(Duration.ofSeconds(5)) < 0, "stopped at about 1 s, not after the tool's 8 s: " + took);
    }

    @Test
    void withNoToolTimeoutASlowToolIsWaitedFor() {
        RecordingTool slowish = new RecordingTool("slow_export", "Exports everything", args -> {
            sleep(Duration.ofMillis(1_500));
            return "{\"exported\":true}";
        });
        harness = ApprovalTurnHarness.builder().tool(slowish)
                .responder(request -> request.hasToolResult()
                        ? ScriptedLlm.text("Exported.")
                        : ScriptedLlm.callTool("c1", "slow_export", "{}"))
                .build();

        assertTrue(harness.chat(GOAL).contains("Exported."));
        assertTrue(harness.llm().lastRequest().toolResults().get(0).contains("exported"));
    }

    // ---------------------------------------------------------------------------------- turn timeout

    @Test
    void anAgentsTurnTimeoutStopsABufferedTurnAndReportsTheStepsThatRan() {
        RecordingTool lookup = lookup();
        harness = ApprovalTurnHarness.builder().tool(lookup).depth(ReasoningDepth.CAREFUL).realErrorTranslation()
                .runtimeProperties(p -> p.getBounds().setTurnTimeoutSeconds(new AgentRuntimeProperties.Range(1, 3600)))
                .agentLimits(secondsFor(RuntimeSetting.TURN_TIMEOUT_SECONDS, 1))
                .responder(request -> {
                    if (request.hasToolResult()) {
                        sleep(Duration.ofSeconds(4));
                        return ScriptedLlm.text("Too late.");
                    }
                    return ScriptedLlm.callTool("c1", LOOKUP, "{}");
                })
                .build();

        String reply = harness.chat(GOAL);

        assertTrue(reply.contains("1-second limit"), reply);
        assertFalse(reply.contains("Too late."), reply);
        assertEquals(1, lookup.executions());
    }

    @Test
    void anAgentsTurnTimeoutAlsoStopsAFastStream() {
        harness = ApprovalTurnHarness.builder()
                .runtimeProperties(p -> p.getBounds().setTurnTimeoutSeconds(new AgentRuntimeProperties.Range(1, 3600)))
                .agentLimits(secondsFor(RuntimeSetting.TURN_TIMEOUT_SECONDS, 1))
                .responder(request -> {
                    sleep(Duration.ofSeconds(4));
                    return ScriptedLlm.text("Too late.");
                })
                .build();

        // (No wall-clock assertion: the scripted model sleeps on the subscribing thread, so the test thread is
        // held for its 4 s inside subscribe() whatever the turn does. What matters is that the person gets the
        // limit notice and never the late answer.)
        String reply = harness.chat(GOAL);

        assertTrue(reply.contains("1-second limit"), reply);
        assertFalse(reply.contains("Too late."), reply);
    }

    @Test
    void aFastStreamWithNoOverrideAndNoDeadlineHasNoOverallLimitAsBefore() {
        harness = ApprovalTurnHarness.builder()
                .responder(request -> {
                    sleep(Duration.ofMillis(1_500));
                    return ScriptedLlm.text("Slow but fine.");
                })
                .build();

        assertTrue(harness.chat(GOAL).contains("Slow but fine."));
    }

    @Test
    void aJobsDeadlineEndsAFastTurnInsideTheTurn() {
        harness = ApprovalTurnHarness.builder()
                .responder(request -> {
                    sleep(Duration.ofSeconds(4));
                    return ScriptedLlm.text("Too late.");
                })
                .build();

        String reply = harness.chatWithCaps(GOAL, null, Duration.ofSeconds(1));

        assertTrue(reply.contains("1-second limit"), reply);
        assertFalse(reply.contains("Too late."), reply);
    }

    // ------------------------------------------------------------------------- continuation passes

    @Test
    void anAgentsContinuationPassesDecideHowOftenAStoppedReplyIsRePrompted() {
        ScriptedLlm.Responder announcesThenFinishes = request -> {
            boolean nudged = request.userTexts().stream().anyMatch(t -> t.contains("stopped before"));
            if (nudged) {
                return ScriptedLlm.text("Ran it. All done.");
            }
            if (request.hasToolResult()) {
                return ScriptedLlm.text("The file is written. Now let me run it:");
            }
            return ScriptedLlm.callTool("c1", "write_file", "{\"to\":\"pom.xml\"}");
        };
        RecordingTool none = RecordingTool.returning("write_file", "Writes a file", "wrote pom.xml");
        harness = ApprovalTurnHarness.builder()
                .continuationPolicy(p -> p.setHeuristicScope(ContinuationProperties.Scope.ALL_MODELS))
                .tool(none).agentLimits(secondsFor(RuntimeSetting.CONTINUATION_PASSES, 0))
                .responder(announcesThenFinishes).build();

        String off = harness.chat("Create a pom.xml and build it");
        assertEquals(2, harness.llm().modelCalls(), "no continuation pass for an agent set to 0");
        assertTrue(off.contains("Now let me run it:"), off);
        harness.close();

        RecordingTool control = RecordingTool.returning("write_file", "Writes a file", "wrote pom.xml");
        harness = ApprovalTurnHarness.builder()
                .continuationPolicy(p -> p.setHeuristicScope(ContinuationProperties.Scope.ALL_MODELS))
                .tool(control).responder(announcesThenFinishes).build();
        String on = harness.chat("Create a pom.xml and build it");
        assertEquals(3, harness.llm().modelCalls(), "the platform's passes still apply to an agent that sets none");
        assertTrue(on.contains("All done."), on);
    }

    // ------------------------------------------------------------- the turn after an approval

    @Test
    void theTurnThePlatformStartsAfterAnApprovalRunsUnderTheAgentsCeilingToo() {
        RecordingTool sendInvoice = RecordingTool.returning("send_invoice", "Sends an invoice", "sent (confirmation CONF-9042)");
        RecordingTool lookup = lookup();
        harness = ApprovalTurnHarness.builder().tool(sendInvoice).tool(lookup).gateTool("send_invoice")
                .maxToolCallsPerTurn(15).agentLimits(steps(2))
                .responder(request -> {
                    if (request.toolResults().stream().anyMatch(ToolBudgetExhausted::isBudgetExhausted)) {
                        return ScriptedLlm.text("Invoice sent; checked what I could.");
                    }
                    if (request.mentions("CONF-9042")) {
                        return ScriptedLlm.callTool("c" + request.number(), LOOKUP, "{\"n\":" + request.number() + "}");
                    }
                    if (request.hasToolResult()) {
                        return ScriptedLlm.text("Waiting for your approval.");
                    }
                    return ScriptedLlm.callTool("call-" + request.number(), "send_invoice", "{\"to\":\"billing@acme.test\"}");
                })
                .build();

        harness.chat("Send invoice INV-7731 and verify it.");
        harness.approvePending();
        String continued = harness.awaitContinuation();

        assertTrue(continued.contains("Invoice sent; checked what I could."), continued);
        assertEquals(1, sendInvoice.executions());
        assertEquals(2, lookup.executions(), "the continuation turn is limited to the agent's 2 calls, not the platform's 15");
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
