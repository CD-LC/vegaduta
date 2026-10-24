package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.tools.TurnLifecycleStepEvents;
import com.agenticai.core.agents.turn.ApprovalContinuation;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

/**
 * The turn-lifecycle step events a client that asked for step events receives (finish, retry, failover,
 * continuation, approval_required), run through the real turn pipeline on the streamed (FAST) and the buffered
 * (CAREFUL) depths; only the LLM provider is scripted.
 *
 * <p>What these pin: a client used to learn that a reply was cut off, retried, moved to another model or waiting for
 * a person only by matching the English notice or marker the platform appends to the reply text, so rewording the
 * notice (or another locale) took the "Continue" button and the chips away. Every one of these is now a field of an
 * event of its own.
 */
class TurnLifecycleStepEventsE2ETest {

    private static final String QUESTION = "Walk me through setting up billing.";
    private static final String INVOICE = "send_invoice";

    private ApprovalTurnHarness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    private static ScriptedLlm.Responder replies(ScriptedLlm.Reply... replies) {
        return request -> {
            if (request.number() > replies.length) {
                throw new IllegalStateException(
                        "scripted model called " + request.number() + " times but has " + replies.length + " replies");
            }
            return replies[request.number() - 1];
        };
    }

    private ApprovalTurnHarness.Builder base(ScriptedLlm.Responder responder, ReasoningDepth depth) {
        return ApprovalTurnHarness.builder().responder(responder).depth(depth);
    }

    private static void assertNoLifecycleEventsBut(StepEventTurn turn, String... allowed) {
        List<String> ok = List.of(allowed);
        for (String type : TurnLifecycleStepEvents.TYPES) {
            if (!ok.contains(type)) {
                assertTrue(turn.of(type).isEmpty(), "no " + type + " event expected, got " + turn.types());
            }
        }
    }

    // ------------------------------------------------------------------------------- finish

    @Test
    void anAnswerEndsWithOneFinishStopBeforeRunFinished_streamed() {
        harness = base(replies(ScriptedLlm.text("Open Settings, then Billing.")), ReasoningDepth.FAST).build();
        assertAnswerEndsWithFinishStop(harness.chatWithStepEvents(QUESTION));
    }

    @Test
    void anAnswerEndsWithOneFinishStopBeforeRunFinished_buffered() {
        harness = base(replies(ScriptedLlm.text("Open Settings, then Billing.")), ReasoningDepth.CAREFUL).build();
        assertAnswerEndsWithFinishStop(harness.chatWithStepEvents(QUESTION));
    }

    private static void assertAnswerEndsWithFinishStop(StepEventTurn turn) {
        JsonNode finish = turn.only("finish");
        assertAll(
                () -> assertNull(turn.failure()),
                () -> assertEquals("Open Settings, then Billing.", turn.reply()),
                () -> assertEquals("stop", finish.path("finishReason").asText()),
                () -> assertTrue(finish.path("detail").isMissingNode(), "a plain answer has no provider wording"),
                () -> assertTrue(turn.indexOf("finish") < turn.indexOf("run_finished"), turn.types().toString()),
                () -> assertNoLifecycleEventsBut(turn, "finish"));
    }

    @Test
    void aReplyThatStaysCutOffEndsWithFinishLength_streamed() {
        assertCutOffForGood(ReasoningDepth.FAST);
    }

    @Test
    void aReplyThatStaysCutOffEndsWithFinishLength_buffered() {
        assertCutOffForGood(ReasoningDepth.CAREFUL);
    }

    /** One continuation allowed and the second pass is cut off too: the reply is reported as cut off. */
    private void assertCutOffForGood(ReasoningDepth depth) {
        harness =
                base(replies(ScriptedLlm.truncated("Step one is to "), ScriptedLlm.truncated("open the settings and ")), depth)
                        .finish(f -> f.setMaxContinuations(1))
                        .build();

        StepEventTurn turn = harness.chatWithStepEvents(QUESTION);

        JsonNode pass = turn.only("continuation");
        assertAll(
                () -> assertEquals("length", turn.only("finish").path("finishReason").asText()),
                () -> assertEquals(1, pass.path("pass").asInt()),
                () -> assertEquals(1, pass.path("maxPasses").asInt()),
                () -> assertEquals("cut_off", pass.path("reason").asText()),
                () -> assertTrue(turn.indexOf("continuation") < turn.indexOf("finish"), turn.types().toString()),
                () -> assertNoLifecycleEventsBut(turn, "finish", "continuation"));
    }

    @Test
    void aSafetyStopEndsWithFinishSafetyAndTheProvidersOwnWord_streamed() {
        assertSafetyStop(ReasoningDepth.FAST);
    }

    @Test
    void aSafetyStopEndsWithFinishSafetyAndTheProvidersOwnWord_buffered() {
        assertSafetyStop(ReasoningDepth.CAREFUL);
    }

    private void assertSafetyStop(ReasoningDepth depth) {
        harness = base(replies(ScriptedLlm.safetyStopped("")), depth).build();

        StepEventTurn turn = harness.chatWithStepEvents(QUESTION);

        JsonNode finish = turn.only("finish");
        assertAll(
                () -> assertEquals("safety", finish.path("finishReason").asText()),
                () -> assertEquals("content_filter", finish.path("detail").asText().toLowerCase(java.util.Locale.ROOT)),
                () -> assertNoLifecycleEventsBut(turn, "finish"));
    }

    @Test
    void theFinishEventDoesNotDependOnTheWordingOfTheNoticeInTheReply() {
        harness =
                base(replies(ScriptedLlm.truncated("Step one is to ")), ReasoningDepth.FAST)
                        .finish(
                                f -> {
                                    f.setMaxContinuations(0);
                                    f.setTruncatedNotice("[ ... ]");
                                })
                        .build();

        StepEventTurn turn = harness.chatWithStepEvents(QUESTION);

        assertAll(
                () -> assertEquals("length", turn.only("finish").path("finishReason").asText()),
                () -> assertFalse(turn.reply().contains("output length limit"), turn.reply()),
                () -> assertTrue(turn.of("continuation").isEmpty(), "no pass was allowed"));
    }

    // ------------------------------------------------------------------------- continuation

    @Test
    void aCutOffReplyThatIsContinuedAnnouncesTheContinuationPassThenFinishStop_streamed() {
        assertContinuedThenStop(ReasoningDepth.FAST);
    }

    @Test
    void aCutOffReplyThatIsContinuedAnnouncesTheContinuationPassThenFinishStop_buffered() {
        assertContinuedThenStop(ReasoningDepth.CAREFUL);
    }

    private void assertContinuedThenStop(ReasoningDepth depth) {
        harness =
                base(replies(ScriptedLlm.truncated("Step one is to open the settings and "), ScriptedLlm.text("choose billing.")), depth)
                        .finish(f -> f.setMaxContinuations(3))
                        .build();

        StepEventTurn turn = harness.chatWithStepEvents(QUESTION);

        JsonNode pass = turn.only("continuation");
        assertAll(
                () -> assertEquals("Step one is to open the settings and choose billing.", turn.reply()),
                () -> assertEquals(1, pass.path("pass").asInt()),
                () -> assertEquals(3, pass.path("maxPasses").asInt()),
                () -> assertEquals("cut_off", pass.path("reason").asText()),
                () -> assertEquals("stop", turn.only("finish").path("finishReason").asText()),
                () -> assertNoLifecycleEventsBut(turn, "finish", "continuation"));
    }

    @Test
    void aReplyThatStopsAtAnAnnouncedStepAnnouncesTheContinuationPass_streamed() {
        assertUnfinishedStepContinued(ReasoningDepth.FAST);
    }

    @Test
    void aReplyThatStopsAtAnAnnouncedStepAnnouncesTheContinuationPass_buffered() {
        assertUnfinishedStepContinued(ReasoningDepth.CAREFUL);
    }

    private void assertUnfinishedStepContinued(ReasoningDepth depth) {
        harness =
                base(
                                request -> {
                                    boolean nudged = request.userTexts().stream().anyMatch(t -> t.contains("stopped before"));
                                    if (nudged && request.toolResults().size() == 1) {
                                        return ScriptedLlm.callTool("c2", "run_it", "{\"to\":\"now\"}");
                                    }
                                    if (nudged) {
                                        return ScriptedLlm.text("Ran it, the build is ok. All done.");
                                    }
                                    if (request.hasToolResult()) {
                                        return ScriptedLlm.text("The file is written. Now let me run it:");
                                    }
                                    return ScriptedLlm.callTool("c1", "write_file", "{\"to\":\"pom.xml\"}");
                                },
                                depth)
                        .continuationPolicy(p -> p.setHeuristicScope(com.agenticai.core.agents.turn.ContinuationProperties.Scope.ALL_MODELS))
                        .maxContinuations(3)
                        .tool(RecordingTool.returning("write_file", "Writes a file into the project", "wrote pom.xml"))
                        .tool(RecordingTool.returning("run_it", "Runs the project", "build ok"))
                        .build();

        StepEventTurn turn = harness.chatWithStepEvents("Create a pom.xml and build it");

        JsonNode pass = turn.only("continuation");
        assertAll(
                () -> assertTrue(turn.reply().contains("All done"), turn.reply()),
                () -> assertEquals(1, pass.path("pass").asInt()),
                () -> assertEquals(3, pass.path("maxPasses").asInt()),
                () -> assertEquals("unfinished_step", pass.path("reason").asText()),
                () -> assertEquals("stop", turn.only("finish").path("finishReason").asText()),
                () -> assertNoLifecycleEventsBut(turn, "finish", "continuation"));
    }

    // ---------------------------------------------------------------------------------- retry

    @Test
    void aMalformedToolCallThatIsAskedForAgainAnnouncesTheRetry_streamed() {
        assertMalformedToolCallRetry(ReasoningDepth.FAST);
    }

    @Test
    void aMalformedToolCallThatIsAskedForAgainAnnouncesTheRetry_buffered() {
        assertMalformedToolCallRetry(ReasoningDepth.CAREFUL);
    }

    private void assertMalformedToolCallRetry(ReasoningDepth depth) {
        harness =
                base(
                                replies(
                                        new ScriptedLlm.Reply("", List.of(), "MALFORMED_FUNCTION_CALL"),
                                        ScriptedLlm.text("Done after the retry.")),
                                depth)
                        .tool(RecordingTool.returning("lookup", "finds a thing", "found it"))
                        .build();

        StepEventTurn turn = harness.chatWithStepEvents(QUESTION);

        JsonNode retry = turn.only("retry");
        assertAll(
                () -> assertEquals("Done after the retry.", turn.reply()),
                () -> assertEquals(2, retry.path("attempt").asInt(), "the second try is about to run"),
                () -> assertEquals(2, retry.path("maxAttempts").asInt(), "the first plus the one retry allowed"),
                () -> assertEquals("malformed_tool_call", retry.path("reason").asText()),
                () -> assertEquals("stop", turn.only("finish").path("finishReason").asText()),
                () -> assertNoLifecycleEventsBut(turn, "finish", "retry"));
    }

    @Test
    void aTransientFailureBeforeTheFirstTokenAnnouncesEachRetryWithTheTryAboutToRun() {
        AtomicInteger calls = new AtomicInteger();
        harness =
                base(
                                request -> {
                                    if (calls.incrementAndGet() < 3) {
                                        throw new TransientAiException("503 - overloaded");
                                    }
                                    return ScriptedLlm.text("Here you go.");
                                },
                                ReasoningDepth.FAST)
                        .realErrorTranslation()
                        .retry(r -> r.setMaxAttempts(3))
                        .build();

        StepEventTurn turn = harness.chatWithStepEvents("Hello");

        List<JsonNode> retries = turn.of("retry");
        assertAll(
                () -> assertEquals("Here you go.", turn.reply()),
                () -> assertEquals(2, retries.size(), turn.types().toString()),
                () -> assertEquals(2, retries.get(0).path("attempt").asInt()),
                () -> assertEquals(3, retries.get(1).path("attempt").asInt()),
                () -> assertEquals(3, retries.get(0).path("maxAttempts").asInt()),
                () -> assertEquals("provider_unavailable", retries.get(0).path("reason").asText()),
                () -> assertNoLifecycleEventsBut(turn, "finish", "retry"));
    }

    @Test
    void aRateLimitIsReportedAsRateLimited() {
        AtomicInteger calls = new AtomicInteger();
        harness =
                base(
                                request -> {
                                    if (calls.incrementAndGet() == 1) {
                                        throw new NonTransientAiException(
                                                "429 - {\"error\":{\"message\":\"Rate limit. Try again in 20ms.\"}}");
                                    }
                                    return ScriptedLlm.text("Here you go.");
                                },
                                ReasoningDepth.FAST)
                        .realErrorTranslation()
                        .retry(r -> r.setMaxAttempts(2))
                        .build();

        StepEventTurn turn = harness.chatWithStepEvents("Hello");

        assertEquals("rate_limited", turn.only("retry").path("reason").asText(), turn.types().toString());
    }

    // -------------------------------------------------------------------------------- failover

    @Test
    void aFailoverToTheFallbackProviderNamesTheModelAndWhy_streamed() {
        assertFailover(ReasoningDepth.FAST);
    }

    @Test
    void aFailoverToTheFallbackProviderNamesTheModelAndWhy_buffered() {
        assertFailover(ReasoningDepth.CAREFUL);
    }

    private void assertFailover(ReasoningDepth depth) {
        AtomicInteger calls = new AtomicInteger();
        harness =
                base(
                                request -> {
                                    if (calls.incrementAndGet() == 1) {
                                        throw new TransientAiException("503 - overloaded");
                                    }
                                    return ScriptedLlm.text("From the fallback.");
                                },
                                depth)
                        .realErrorTranslation()
                        .fallbackProvider()
                        .retry(r -> r.setMaxAttempts(1))
                        .build();

        StepEventTurn turn = harness.chatWithStepEvents("Hello");

        JsonNode failover = turn.only("failover");
        assertAll(
                () -> assertEquals("From the fallback.", turn.reply()),
                () -> assertEquals("fallback-model", failover.path("toModel").asText()),
                () -> assertEquals("provider_unavailable", failover.path("reason").asText()),
                () -> assertNoLifecycleEventsBut(turn, "finish", "failover"));
    }

    // ---------------------------------------------------------------------- approval_required

    @Test
    void aHeldToolCallAnnouncesTheApprovalRequestAfterItsResult_streamed() {
        assertApprovalRequired(ReasoningDepth.FAST);
    }

    @Test
    void aHeldToolCallAnnouncesTheApprovalRequestAfterItsResult_buffered() {
        assertApprovalRequired(ReasoningDepth.CAREFUL);
    }

    private void assertApprovalRequired(ReasoningDepth depth) {
        harness =
                base(
                                request ->
                                        request.hasToolResult()
                                                ? ScriptedLlm.text("I will send the invoice as soon as you approve it.")
                                                : ScriptedLlm.callTool("c1", INVOICE, "{\"to\":\"a\"}"),
                                depth)
                        .tool(RecordingTool.returning(INVOICE, "Sends an invoice", "sent"))
                        .gateTool(INVOICE)
                        .build();

        StepEventTurn turn = harness.chatWithStepEvents("Send invoice INV-7731 to billing@acme.test.");

        JsonNode held = turn.only("approval_required");
        String pending = harness.pendingApproval().getId().toString();
        assertAll(
                () -> assertNull(turn.failure()),
                () -> assertEquals(INVOICE, held.path("tool").asText()),
                () -> assertEquals(pending, held.path("approvalId").asText(), "the id of the request a person decides"),
                () ->
                        assertTrue(
                                turn.indexOf("tool_result") < turn.indexOf("approval_required"),
                                "after the result the model reads, so a wait stays open: " + turn.types()),
                () -> assertEquals(0, harness.tool(INVOICE).executions(), "held, not run"),
                () -> assertEquals("stop", turn.only("finish").path("finishReason").asText()),
                () -> assertNoLifecycleEventsBut(turn, "finish", "approval_required"));
    }

    @Test
    void theTurnAfterAHumanDecisionAnnouncesThatItIsCarryingOn() {
        harness = base(replies(ScriptedLlm.text("The invoice went out.")), ReasoningDepth.FAST).build();
        ApprovalContinuation decided =
                new ApprovalContinuation(
                        java.util.UUID.randomUUID(),
                        ApprovalContinuation.Outcome.COMPLETED,
                        INVOICE,
                        "{\"to\":\"a\"}",
                        "sent",
                        "admin-1",
                        "Send invoice INV-7731.",
                        harness.agentId(),
                        harness.sessionId(),
                        harness.subject(),
                        harness.tenantId(),
                        harness.subject(),
                        null,
                        List.of(),
                        List.of());

        StepEventTurn turn = harness.chatWithStepEvents("<approval_result>done</approval_result>", decided);

        JsonNode carryOn = turn.only("continuation");
        assertAll(
                () -> assertEquals("The invoice went out.", turn.reply()),
                () -> assertEquals("approval", carryOn.path("reason").asText()),
                () -> assertTrue(turn.indexOf("continuation") < turn.indexOf("finish"), turn.types().toString()),
                () -> assertNoLifecycleEventsBut(turn, "finish", "continuation"));
    }

    @Test
    void anOrdinaryTurnAnnouncesNoApprovalContinuation() {
        harness = base(replies(ScriptedLlm.text("Hi.")), ReasoningDepth.FAST).build();

        assertTrue(harness.chatWithStepEvents("Hello").of("continuation").isEmpty());
    }

    @Test
    void aToolThatIsNotHeldAnnouncesNoApprovalRequest() {
        harness =
                base(
                                request ->
                                        request.hasToolResult()
                                                ? ScriptedLlm.text("Found it.")
                                                : ScriptedLlm.callTool("c1", "lookup", "{\"q\":\"a\"}"),
                                ReasoningDepth.FAST)
                        .tool(RecordingTool.returning("lookup", "Finds a thing", "found"))
                        .build();

        StepEventTurn turn = harness.chatWithStepEvents("Find a thing");

        assertAll(
                () -> assertEquals(1, turn.of("tool_result").size()),
                () -> assertTrue(turn.of("approval_required").isEmpty(), turn.types().toString()));
    }
}
