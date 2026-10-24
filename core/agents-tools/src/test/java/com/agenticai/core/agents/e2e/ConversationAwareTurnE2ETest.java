package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A short follow-up ("yes", "continue", "try again") judged on its own text: it shares no words with any
 * tool, so the agent's tools were cut down to the four that match nothing, and it reads as a trivial
 * question, so cost routing sent it to the economy model - in the middle of a task, on a model that is not
 * the one that started it. Run through the real turn pipeline (real tool selection over a catalog bigger
 * than the per-turn budget, real cost-routing resolver); only the LLM provider is scripted.
 */
class ConversationAwareTurnE2ETest {

    private ApprovalTurnHarness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    /** Twenty tools of which one, last in resolution order, is the only one that matches the task. */
    private static List<RecordingTool> catalog() {
        List<RecordingTool> tools = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            tools.add(RecordingTool.returning("warehouse_" + i, "Handles stock location " + i, "ok"));
        }
        tools.add(RecordingTool.returning("create_invoice", "Creates an invoice for a customer", "{\"invoice\":\"INV-1\"}"));
        return tools;
    }

    private ApprovalTurnHarness.Builder withCatalog() {
        ApprovalTurnHarness.Builder builder = ApprovalTurnHarness.builder();
        catalog().forEach(builder::tool);
        return builder;
    }

    /** Calls create_invoice for the first request that has no result yet; otherwise just answers. */
    private static ScriptedLlm.Responder invoiceTask() {
        return request ->
                request.hasToolResult() || request.userTexts().stream().anyMatch(t -> t.equals("yes"))
                        ? ScriptedLlm.text("Done.")
                        : request.offeredTools().contains("create_invoice")
                                ? ScriptedLlm.callTool("c1", "create_invoice", "{\"to\":\"Acme\"}")
                                : ScriptedLlm.text("I have no tool for that.");
    }

    private List<String> toolsOfferedOnTheTurnThatSaid(String message) {
        return harness.llm().requests().stream()
                .filter(r -> !r.userTexts().isEmpty() && r.userTexts().get(r.userTexts().size() - 1).equals(message))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no model call for '" + message + "': " + harness.llm().requests()))
                .offeredTools();
    }

    @Test
    void aShortFollowUpKeepsTheToolsTheTaskWasUsing() {
        harness = withCatalog().responder(invoiceTask()).build();

        harness.chat("Create an invoice for Acme");
        harness.chat("yes");

        List<String> onTheFirstTurn = toolsOfferedOnTheTurnThatSaid("Create an invoice for Acme");
        List<String> onTheFollowUp = toolsOfferedOnTheTurnThatSaid("yes");
        assertAll(
                () -> assertTrue(onTheFirstTurn.contains("create_invoice"), "the task's tool matched the request: " + onTheFirstTurn),
                () -> assertTrue(onTheFollowUp.contains("create_invoice"), "and is still on offer for 'yes': " + onTheFollowUp),
                () -> assertEquals(1, harness.tool("create_invoice").executions()));
    }

    @Test
    void withStickyToolsSwitchedOffTheFollowUpLosesThemAsItAlwaysDid() {
        harness = withCatalog().followUp(p -> p.setStickyToolsEnabled(false)).responder(invoiceTask()).build();

        harness.chat("Create an invoice for Acme");
        harness.chat("yes");

        assertFalse(
                toolsOfferedOnTheTurnThatSaid("yes").contains("create_invoice"),
                "the control: a message that names nothing selects nothing");
    }

    @Test
    void aMessageWithRealContentIsStillSelectedByItsOwnWords() {
        harness = withCatalog().responder(invoiceTask()).build();

        harness.chat("Create an invoice for Acme");
        harness.chat("Now please look up the stock location 7 in the warehouse and report it");

        List<String> offered = toolsOfferedOnTheTurnThatSaid("Now please look up the stock location 7 in the warehouse and report it");
        assertFalse(offered.contains("create_invoice"), "no follow-up, so no stickiness: " + offered);
    }

    @Test
    void aFollowUpInTheMiddleOfAToolTaskStaysOnThePrimaryModel() {
        harness = withCatalog().costRouting().responder(invoiceTask()).build();

        harness.chat("Create an invoice for Acme");
        harness.chat("yes");

        List<String> models = harness.modelsRequested();
        assertAll(
                () -> assertEquals(ApprovalTurnHarness.ECONOMY_MODEL, models.get(0), "a short first message is cheap work: " + models),
                () -> assertEquals(ApprovalTurnHarness.PRIMARY_MODEL, models.get(models.size() - 1), "but 'yes' after a tool task is not: " + models));
    }

    @Test
    void aFollowUpWithNoToolWorkBehindItIsStillRoutedByCost() {
        harness = withCatalog().costRouting().responder(request -> ScriptedLlm.text("Hello.")).build();

        harness.chat("hi");
        harness.chat("yes");

        assertEquals(
                List.of(ApprovalTurnHarness.ECONOMY_MODEL, ApprovalTurnHarness.ECONOMY_MODEL),
                harness.modelsRequested());
    }

    @Test
    void keepingThePrimaryModelCanBeSwitchedOff() {
        harness = withCatalog().costRouting().followUp(p -> p.setKeepPrimaryModel(false)).responder(invoiceTask()).build();

        harness.chat("Create an invoice for Acme");
        harness.chat("yes");

        List<String> models = harness.modelsRequested();
        assertEquals(ApprovalTurnHarness.ECONOMY_MODEL, models.get(models.size() - 1));
    }
}
