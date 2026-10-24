package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.guardrails.ToolBudgetExhausted;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The product controls that used to be deployment-only (agent.steps.*, chat.toolLedger.*, chat.followUp.*) reach the runtime
 * they claim to steer. The real turn pipeline, guard chain, session memory over an in-memory Redis and a real settings catalog
 * on the harness's own property objects; only the LLM is scripted. Every test pairs the override with the control, the same
 * turn with no override, which must do what the platform property says.
 */
class ProductControlsTurnE2ETest {

    private static final String GOAL = "Collect the build facts.";
    private static final String LOOKUP = "lookup";
    private static final String REPORT = "Create the report file";
    private static final String ASK = "What was the id of that file?";

    private final List<ApprovalTurnHarness> open = new ArrayList<>();

    @AfterEach
    void close() {
        open.forEach(ApprovalTurnHarness::close);
    }

    private ApprovalTurnHarness built(ApprovalTurnHarness.Builder builder) {
        ApprovalTurnHarness harness = builder.build();
        open.add(harness);
        return harness;
    }

    private static boolean sawBudgetNotice(ScriptedLlm.Request request) {
        return request.toolResults().stream().anyMatch(ToolBudgetExhausted::isBudgetExhausted);
    }

    private static ScriptedLlm.Responder callsUntilToldToStop() {
        return request ->
                request.toolResults().stream().anyMatch(ToolBudgetExhausted::isBudgetExhausted)
                        ? ScriptedLlm.text("Here is what I found so far.")
                        : ScriptedLlm.callTool("c" + request.number(), LOOKUP, "{\"n\":" + request.number() + "}");
    }

    private static ScriptedLlm.Responder neverStopsCalling() {
        return request -> ScriptedLlm.callTool("c" + request.number(), LOOKUP, "{\"n\":" + request.number() + "}");
    }

    private static RecordingTool lookup() {
        return RecordingTool.returning(LOOKUP, "Looks things up", "{\"hit\":true}");
    }

    private static boolean stoppedByTheStepLimit(Runnable turn) {
        RuntimeException thrown = assertThrows(RuntimeException.class, turn::run);
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof ToolStepLimitExceededException) {
                return true;
            }
        }
        return false;
    }

    // ============================================================================ agent.steps.*

    @Test
    void aTenantThatAllowsNoGraceStopsTheTurnAtTheFirstCallOverTheCeiling() {
        RecordingTool control = lookup();
        ApprovalTurnHarness platform = built(ApprovalTurnHarness.builder().settingsCatalog().tool(control).maxToolCallsPerTurn(1)
                .responder(callsUntilToldToStop()));
        String reply = platform.chat(GOAL);

        RecordingTool strict = lookup();
        ApprovalTurnHarness tenant = built(ApprovalTurnHarness.builder().settingsCatalog().tool(strict).maxToolCallsPerTurn(1)
                .responder(callsUntilToldToStop()));
        tenant.tenantSetting("agent.steps.graceCalls", 0);

        assertAll(
                () -> assertTrue(reply.contains("Here is what I found so far."), "control: the model is told, then answers: " + reply),
                () -> assertEquals(1, control.executions()),
                () -> assertTrue(stoppedByTheStepLimit(() -> tenant.chat(GOAL)), "no grace: the call over the ceiling stops the turn"),
                () -> assertEquals(1, strict.executions()));
    }

    @Test
    void anAgentsOwnGraceBeatsItsTenantsOnTheStreamedAndTheBufferedPath() {
        for (com.agenticai.core.agents.core.ReasoningDepth depth : new com.agenticai.core.agents.core.ReasoningDepth[] {
            com.agenticai.core.agents.core.ReasoningDepth.FAST, com.agenticai.core.agents.core.ReasoningDepth.CAREFUL}) {
            RecordingTool tool = lookup();
            ApprovalTurnHarness harness = built(ApprovalTurnHarness.builder().settingsCatalog().depth(depth).tool(tool)
                    .maxToolCallsPerTurn(1).responder(callsUntilToldToStop()));
            harness.tenantSetting("agent.steps.graceCalls", 0);
            harness.agentSetting("agent.steps.graceCalls", 4);

            String reply = harness.chat(GOAL);

            assertTrue(reply.contains("Here is what I found so far."), depth + ": " + reply);
            assertEquals(1, tool.executions(), depth.name());
        }
    }

    @Test
    void aTenantThatRaisesTheGraceLetsAModelKeepCallingLongerBeforeItIsStopped() {
        RecordingTool platformTool = lookup();
        ApprovalTurnHarness platform = built(ApprovalTurnHarness.builder().settingsCatalog().tool(platformTool).maxToolCallsPerTurn(1)
                .stepLimits(limits -> limits.setGraceCalls(1)).responder(neverStopsCalling()));
        assertTrue(stoppedByTheStepLimit(() -> platform.chat(GOAL)));
        long platformNotices = platform.llm().requests().stream().filter(ProductControlsTurnE2ETest::sawBudgetNotice).count();

        RecordingTool tenantTool = lookup();
        ApprovalTurnHarness tenant = built(ApprovalTurnHarness.builder().settingsCatalog().tool(tenantTool).maxToolCallsPerTurn(1)
                .stepLimits(limits -> limits.setGraceCalls(1)).responder(neverStopsCalling()));
        tenant.tenantSetting("agent.steps.graceCalls", 5);
        assertTrue(stoppedByTheStepLimit(() -> tenant.chat(GOAL)));
        long tenantNotices = tenant.llm().requests().stream().filter(ProductControlsTurnE2ETest::sawBudgetNotice).count();

        assertAll(
                () -> assertTrue(tenantNotices > platformNotices, "told " + tenantNotices + " times against " + platformNotices),
                () -> assertEquals(1, tenantTool.executions(), "the tool still ran only inside the ceiling"));
    }

    @Test
    void aTenantsAutonomousMultiplierScalesTheCeilingOfItsFullyAutonomousAgents() {
        RecordingTool control = lookup();
        ApprovalTurnHarness platform = built(ApprovalTurnHarness.builder().settingsCatalog().autonomy(AgentAutonomyTier.FULLY_AUTONOMOUS)
                .tool(control).maxToolCallsPerTurn(3).responder(callsUntilToldToStop()));
        platform.chat(GOAL);

        RecordingTool boosted = lookup();
        ApprovalTurnHarness tenant = built(ApprovalTurnHarness.builder().settingsCatalog().autonomy(AgentAutonomyTier.FULLY_AUTONOMOUS)
                .tool(boosted).maxToolCallsPerTurn(3).responder(callsUntilToldToStop()));
        tenant.tenantSetting("agent.steps.autonomousMultiplier", 4);
        tenant.chat(GOAL);

        RecordingTool semi = lookup();
        ApprovalTurnHarness untouched = built(ApprovalTurnHarness.builder().settingsCatalog().autonomy(AgentAutonomyTier.SEMI_AUTONOMOUS)
                .tool(semi).maxToolCallsPerTurn(3).responder(callsUntilToldToStop()));
        untouched.tenantSetting("agent.steps.autonomousMultiplier", 4);
        untouched.chat(GOAL);

        assertAll(
                () -> assertEquals(6, control.executions(), "control: the platform's multiplier of two"),
                () -> assertEquals(12, boosted.executions(), "the tenant's multiplier of four"),
                () -> assertEquals(3, semi.executions(), "a semi-autonomous agent is not scaled"));
    }

    // ============================================================================ chat.toolLedger.*

    private static RecordingTool createFile() {
        return RecordingTool.returning(
                "create_file", "Creates a file in the workspace", "{\"fileId\":\"file-42\",\"path\":\"/work/report.md\"}");
    }

    private static ScriptedLlm.Responder fileTask() {
        return request -> {
            if (request.hasToolResult()) {
                return ScriptedLlm.text("I created the report.");
            }
            String last = request.userTexts().get(request.userTexts().size() - 1);
            if (last.equals(REPORT)) {
                return ScriptedLlm.callTool("c1", "create_file", "{\"to\":\"report.md\"}");
            }
            return request.mentions("file-42")
                    ? ScriptedLlm.text("The file id is file-42.")
                    : ScriptedLlm.text("I do not know which file you mean.");
        };
    }

    @Test
    void aTenantThatSwitchesTheRecordOffGetsTextOnlyHistoryAndTheControlStillKeepsIt() {
        ApprovalTurnHarness control = built(ApprovalTurnHarness.builder().settingsCatalog().tool(createFile()).responder(fileTask()));
        control.chat(REPORT);
        String controlAnswer = control.chat(ASK);

        ApprovalTurnHarness tenant = built(ApprovalTurnHarness.builder().settingsCatalog().tool(createFile()).responder(fileTask()));
        tenant.tenantSetting("chat.toolLedger.enabled", false);
        tenant.chat(REPORT);
        String tenantAnswer = tenant.chat(ASK);

        assertAll(
                () -> assertEquals("The file id is file-42.", controlAnswer),
                () -> assertNotNull(control.storedTranscript().get(1).tools()),
                () -> assertEquals("I do not know which file you mean.", tenantAnswer),
                () -> assertNull(tenant.storedTranscript().get(1).tools(), "nothing was recorded for this tenant"));
    }

    @Test
    void aTenantThatStopsTheReplayStillStoresTheRecordButTheModelNoLongerSeesIt() {
        ApprovalTurnHarness tenant = built(ApprovalTurnHarness.builder().settingsCatalog().tool(createFile()).responder(fileTask()));
        tenant.tenantSetting("chat.toolLedger.replayEnabled", false);

        tenant.chat(REPORT);
        tenant.chat(ASK);

        assertAll(
                () -> assertNotNull(tenant.storedTranscript().get(1).tools()),
                () -> assertFalse(tenant.llm().lastRequest().mentions("file-42")));
    }

    @Test
    void aTenantCanLimitHowManyOfAnAnswersToolCallsAreListed() {
        RecordingTool tool = RecordingTool.returning("create_file", "Creates a file", "{\"fileId\":\"f\"}");
        ScriptedLlm.Responder twoCalls = request ->
                request.toolResults().size() < 2
                        ? ScriptedLlm.callTool("c" + request.number(), "create_file", "{\"n\":" + request.number() + "}")
                        : ScriptedLlm.text("Both files are created.");
        ApprovalTurnHarness control = built(ApprovalTurnHarness.builder().settingsCatalog().tool(tool).responder(twoCalls));
        control.chat(REPORT);

        RecordingTool tool2 = RecordingTool.returning("create_file", "Creates a file", "{\"fileId\":\"f\"}");
        ApprovalTurnHarness tenant = built(ApprovalTurnHarness.builder().settingsCatalog().tool(tool2).responder(twoCalls));
        tenant.tenantSetting("chat.toolLedger.maxEntriesPerTurn", 1);
        tenant.chat(REPORT);

        ChatTurn controlTurn = control.storedTranscript().get(1);
        ChatTurn tenantTurn = tenant.storedTranscript().get(1);
        assertAll(
                () -> assertEquals(2, controlTurn.tools().entries().size()),
                () -> assertEquals(1, tenantTurn.tools().entries().size()),
                () -> assertEquals(1, tenantTurn.tools().omitted(), "the second call is counted, not listed"));
    }

    // ============================================================================ chat.followUp.*

    private static List<RecordingTool> catalog() {
        List<RecordingTool> tools = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            tools.add(RecordingTool.returning("warehouse_" + i, "Handles stock location " + i, "ok"));
        }
        tools.add(RecordingTool.returning("create_invoice", "Creates an invoice for a customer", "{\"invoice\":\"INV-1\"}"));
        return tools;
    }

    private static ScriptedLlm.Responder invoiceTask() {
        return request ->
                request.hasToolResult() || request.userTexts().stream().anyMatch(t -> t.equals("yes"))
                        ? ScriptedLlm.text("Done.")
                        : request.offeredTools().contains("create_invoice")
                                ? ScriptedLlm.callTool("c1", "create_invoice", "{\"to\":\"Acme\"}")
                                : ScriptedLlm.text("I have no tool for that.");
    }

    private ApprovalTurnHarness invoiceAgent(boolean costRouting) {
        ApprovalTurnHarness.Builder builder = ApprovalTurnHarness.builder().settingsCatalog().responder(invoiceTask());
        if (costRouting) {
            builder.costRouting();
        }
        catalog().forEach(builder::tool);
        return built(builder);
    }

    private static List<String> toolsOfferedOnTheTurnThatSaid(ApprovalTurnHarness harness, String message) {
        return harness.llm().requests().stream()
                .filter(r -> !r.userTexts().isEmpty() && r.userTexts().get(r.userTexts().size() - 1).equals(message))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no model call for '" + message + "'"))
                .offeredTools();
    }

    @Test
    void aTenantThatKeepsNoToolsForAFollowUpLosesTheTasksToolsAndAnAgentCanKeepThemAgain() {
        ApprovalTurnHarness control = invoiceAgent(false);
        control.chat("Create an invoice for Acme");
        control.chat("yes");

        ApprovalTurnHarness tenant = invoiceAgent(false);
        tenant.tenantSetting("chat.followUp.maxStickyTools", 0);
        tenant.chat("Create an invoice for Acme");
        tenant.chat("yes");

        ApprovalTurnHarness agent = invoiceAgent(false);
        agent.tenantSetting("chat.followUp.maxStickyTools", 0);
        agent.agentSetting("chat.followUp.maxStickyTools", 3);
        agent.chat("Create an invoice for Acme");
        agent.chat("yes");

        assertAll(
                () -> assertTrue(toolsOfferedOnTheTurnThatSaid(control, "yes").contains("create_invoice"), "control: sticky"),
                () -> assertFalse(toolsOfferedOnTheTurnThatSaid(tenant, "yes").contains("create_invoice")),
                () -> assertTrue(toolsOfferedOnTheTurnThatSaid(agent, "yes").contains("create_invoice"), "the agent's own number wins"));
    }

    @Test
    void aTenantCanLetCostRoutingSendAFollowUpToTheCheapModelEvenInAToolTask() {
        ApprovalTurnHarness control = invoiceAgent(true);
        control.chat("Create an invoice for Acme");
        control.chat("yes");

        ApprovalTurnHarness tenant = invoiceAgent(true);
        tenant.tenantSetting("chat.followUp.keepPrimaryModel", false);
        tenant.chat("Create an invoice for Acme");
        tenant.chat("yes");

        List<String> controlModels = control.modelsRequested();
        List<String> tenantModels = tenant.modelsRequested();
        assertAll(
                () -> assertEquals(ApprovalTurnHarness.PRIMARY_MODEL, controlModels.get(controlModels.size() - 1), "control: stays on the primary model"),
                () -> assertEquals(ApprovalTurnHarness.ECONOMY_MODEL, tenantModels.get(tenantModels.size() - 1)));
    }
}
