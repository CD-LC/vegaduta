package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalStatus;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A run-scoped tool selection ({@code ChatRequest.toolNames}), through the real turn pipeline: the playground's
 * "this run only" tool switches. It can only NARROW the agent's tools for one run; it never adds a tool and never
 * loosens a gate. Same harness as the approval end-to-end tests: real {@code AgentChatService}, real tool guard
 * chain, real approval service and continuation launcher, only the model scripted.
 */
class RunScopedToolsE2ETest {

    private static final String GATED = "send_invoice";
    private static final String OPEN = "lookup_customer";
    private static final String GOAL = "Send invoice INV-7731 to billing@acme.test.";
    private static final String CONFIRMATION = "CONF-9042";

    private ApprovalTurnHarness harness;
    private RecordingTool sendInvoice;
    private RecordingTool lookupCustomer;

    /** Calls the gated tool when it is offered; finishes once it has been shown the tool's output. */
    private static ScriptedLlm.Responder model() {
        return request -> {
            if (request.mentions(CONFIRMATION)) {
                return ScriptedLlm.text("Done. Confirmation " + CONFIRMATION + ".");
            }
            if (request.offeredTools().contains(GATED) && !request.hasToolResult()) {
                return ScriptedLlm.callTool("call-" + request.number(), GATED, "{\"to\":\"billing@acme.test\"}");
            }
            return ScriptedLlm.text("Nothing to call.");
        };
    }

    private ApprovalTurnHarness agent() {
        sendInvoice = RecordingTool.returning(GATED, "Sends an invoice by email.", "sent (confirmation " + CONFIRMATION + ")");
        lookupCustomer = RecordingTool.returning(OPEN, "Looks up a customer record.", "customer found");
        harness =
                ApprovalTurnHarness.builder()
                        .tool(sendInvoice)
                        .tool(lookupCustomer)
                        .gateTool(GATED)
                        .responder(model())
                        .build();
        return harness;
    }

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void withoutASelectionTheRunOffersEveryToolOfTheAgent() {
        ApprovalTurnHarness h = agent();

        h.chatWithTools(GOAL, null);

        List<String> offered = h.llm().requests().get(0).offeredTools();
        assertAll(
                () -> assertTrue(offered.contains(GATED), offered.toString()),
                () -> assertTrue(offered.contains(OPEN), offered.toString()));
    }

    @Test
    void aSubsetRemovesTheOtherToolsFromTheModelsRequest() {
        ApprovalTurnHarness h = agent();

        h.chatWithTools(GOAL, List.of(OPEN));

        List<String> offered = h.llm().requests().get(0).offeredTools();
        assertAll(
                () -> assertTrue(offered.contains(OPEN), "the selected tool is offered: " + offered),
                () -> assertTrue(!offered.contains(GATED), "the tool left out is not offered: " + offered),
                () -> assertEquals(0, sendInvoice.executions()));
    }

    @Test
    void theBufferedDepthsHonourTheSelectionToo() {
        sendInvoice = RecordingTool.returning(GATED, "Sends an invoice by email.", "sent");
        lookupCustomer = RecordingTool.returning(OPEN, "Looks up a customer record.", "customer found");
        harness =
                ApprovalTurnHarness.builder()
                        .tool(sendInvoice)
                        .tool(lookupCustomer)
                        .gateTool(GATED)
                        .depth(com.agenticai.core.agents.core.ReasoningDepth.CAREFUL)
                        .responder(request -> ScriptedLlm.text("ok"))
                        .build();

        harness.chatWithTools(GOAL, List.of(OPEN));

        assertTrue(!harness.llm().requests().isEmpty(), "the buffered turn called the model");
        for (ScriptedLlm.Request request : harness.llm().requests()) {
            assertEquals(List.of(OPEN), request.offeredTools());
        }
    }

    @Test
    void anEmptySelectionLeavesTheRunWithoutAnyTool() {
        ApprovalTurnHarness h = agent();

        h.chatWithTools(GOAL, List.of());

        assertEquals(List.of(), h.llm().requests().get(0).offeredTools());
    }

    @Test
    void anUnknownNameIsRefusedWithAClearErrorAndNoModelCall() {
        ApprovalTurnHarness h = agent();

        Throwable failure = h.chatWithToolsFailure(GOAL, List.of(OPEN, "format_disk"));

        assertNotNull(failure, "an unknown tool name must be refused");
        ResponseStatusException refused = assertInstanceOf(ResponseStatusException.class, failure);
        assertAll(
                () -> assertEquals(HttpStatus.BAD_REQUEST, refused.getStatusCode()),
                () -> assertTrue(refused.getReason().contains("format_disk"), refused.getReason()),
                () -> assertEquals(0, h.llm().modelCalls(), "a refused selection costs no model call"));
    }

    @Test
    void aSelectedToolThatNeedsApprovalStaysGated() {
        ApprovalTurnHarness h = agent();

        h.chatWithTools(GOAL, List.of(GATED));

        ApprovalRequest pending = h.pendingApproval();
        assertAll(
                () -> assertEquals(ApprovalStatus.PENDING, pending.getStatus()),
                () -> assertEquals(0, sendInvoice.executions(), "selecting a tool does not skip its approval"));
    }

    @Test
    void theContinuationAfterTheDecisionKeepsTheRunsToolSet() {
        ApprovalTurnHarness h = agent();
        h.chatWithTools(GOAL, List.of(GATED));
        int requestsBeforeDecision = h.llm().requests().size();

        h.approvePending();
        h.awaitContinuation();

        List<ScriptedLlm.Request> all = h.llm().requests();
        assertTrue(all.size() > requestsBeforeDecision, "the continuation called the model");
        for (ScriptedLlm.Request request : all.subList(requestsBeforeDecision, all.size())) {
            assertEquals(
                    List.of(GATED),
                    request.offeredTools(),
                    "the continuation must offer only what the run was narrowed to");
        }
        assertEquals(1, sendInvoice.executions(), "the approved call ran once");
    }

    @Test
    void aContinuationOfAnUnnarrowedRunStillOffersEveryTool() {
        ApprovalTurnHarness h = agent();
        h.chatWithTools(GOAL, null);
        int requestsBeforeDecision = h.llm().requests().size();

        h.approvePending();
        h.awaitContinuation();

        List<ScriptedLlm.Request> all = h.llm().requests();
        List<String> offered = all.get(requestsBeforeDecision).offeredTools();
        assertAll(
                () -> assertTrue(offered.contains(GATED), offered.toString()),
                () -> assertTrue(offered.contains(OPEN), offered.toString()));
    }
}
