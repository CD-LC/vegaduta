package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.turn.ChatParityProperties.FastBufferTrigger;
import com.agenticai.core.agents.turn.ContinuationProperties;
import com.agenticai.core.guardrails.impl.ProfanityGuardrail;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The same agent behaves the same whether its depth streams the reply (FAST) or buffers it (CAREFUL): run
 * through the real turn pipeline with the same scripted model, the two depths offer the same tools in the same
 * order, continue a stopped reply the same way without ever editing the person's message, and store the same
 * history. What genuinely differs (the FAST turn that is buffered, and the output guardrails that audit a
 * streamed reply but withhold a buffered one) is a setting, {@code app.chat.parity.*}, not an accident.
 */
class StreamBufferedParityE2ETest {

    private final List<ApprovalTurnHarness> open = new ArrayList<>();

    @AfterEach
    void close() {
        open.forEach(ApprovalTurnHarness::close);
    }

    private ApprovalTurnHarness build(ApprovalTurnHarness.Builder builder) {
        ApprovalTurnHarness harness = builder.build();
        open.add(harness);
        return harness;
    }

    private static List<RecordingTool> tools() {
        // Declared out of alphabetical order on purpose: a prompt must not depend on resolution order.
        return List.of(
                RecordingTool.returning("zeta_lookup", "Looks up a zeta record", "{\"zeta\":1}"),
                RecordingTool.returning("alpha_search", "Searches the alpha index", "{\"alpha\":1}"),
                RecordingTool.returning("middle_report", "Builds the middle report", "{\"middle\":1}"));
    }

    private ApprovalTurnHarness.Builder withTools(ReasoningDepth depth, ScriptedLlm.Responder responder) {
        ApprovalTurnHarness.Builder builder = ApprovalTurnHarness.builder().depth(depth).responder(responder);
        tools().forEach(builder::tool);
        return builder;
    }

    // --------------------------------------------------------------------------------- tool order

    @Test
    void bothDepthsOfferTheSameToolsInTheSameOrder() {
        ApprovalTurnHarness fast = build(withTools(ReasoningDepth.FAST, request -> ScriptedLlm.text("ok")));
        ApprovalTurnHarness careful = build(withTools(ReasoningDepth.CAREFUL, request -> ScriptedLlm.text("ok")));

        fast.chat("zeta lookup then alpha search then the middle report");
        careful.chat("zeta lookup then alpha search then the middle report");

        List<String> streamed = fast.llm().requests().get(0).offeredTools();
        List<String> buffered = careful.llm().requests().get(0).offeredTools();
        assertAll(
                () -> assertEquals(3, streamed.size(), streamed.toString()),
                () -> assertEquals(streamed, buffered, "one tool order, so a provider's prompt cache prefix is the same on both paths"),
                () -> assertEquals(streamed.stream().sorted().toList(), streamed, "and it is the stable (name) order"));
    }

    // ---------------------------------------------------- continuation and the person's message

    /** Announces a step and stops; when nudged, makes the call; then reports. */
    private static ScriptedLlm.Responder announcesThenActs() {
        return request -> {
            if (request.hasToolResult()) {
                return ScriptedLlm.text("The report is done.");
            }
            boolean nudged = request.userTexts().size() > 1 && request.userTexts().get(request.userTexts().size() - 1).contains("stopped before");
            return nudged
                    ? ScriptedLlm.callTool("c1", "middle_report", "{\"to\":\"x\"}")
                    : ScriptedLlm.text("Now I will build the middle report:");
        };
    }

    private ApprovalTurnHarness announcing(ReasoningDepth depth) {
        return build(
                withTools(depth, announcesThenActs())
                        .continuationPolicy(p -> p.setHeuristicScope(ContinuationProperties.Scope.ALL_MODELS)));
    }

    @Test
    void aReplyThatStopsAtAnAnnouncedStepIsContinuedTheSameWayOnBothDepths() {
        ApprovalTurnHarness fast = announcing(ReasoningDepth.FAST);
        ApprovalTurnHarness careful = announcing(ReasoningDepth.CAREFUL);

        String streamedReply = fast.chat("Build the middle report");
        String bufferedReply = careful.chat("Build the middle report");

        assertAll(
                () -> assertEquals(fast.llm().modelCalls(), careful.llm().modelCalls(), "same number of model calls"),
                () -> assertEquals(1, fast.tool("middle_report").executions()),
                () -> assertEquals(1, careful.tool("middle_report").executions()),
                () -> assertTrue(streamedReply.contains("The report is done."), streamedReply),
                () -> assertTrue(bufferedReply.contains("The report is done."), bufferedReply),
                () -> assertEquals(streamedReply, bufferedReply, "the person reads the same reply"));
    }

    @Test
    void neitherDepthEditsThePersonsMessageToNudgeTheModel() {
        String message = "Build the middle report";
        for (ReasoningDepth depth : List.of(ReasoningDepth.FAST, ReasoningDepth.CAREFUL)) {
            ApprovalTurnHarness harness = announcing(depth);

            harness.chat(message);

            ChatTurn storedUser = harness.storedTranscript().get(0);
            assertAll(
                    depth.name(),
                    () -> assertEquals(message, storedUser.content(), depth + ": the stored user turn is what the person typed"),
                    () -> assertEquals(message, harness.llm().requests().get(0).userTexts().get(0)),
                    () -> assertFalse(
                            harness.llm().requests().stream().flatMap(r -> r.userTexts().stream()).anyMatch(t -> t.startsWith(message) && !t.equals(message)),
                            depth + ": no request carries the message with something appended: " + harness.llm().requests()));
        }
    }

    @Test
    void bothDepthsStoreTheSameHistoryShape() {
        ApprovalTurnHarness fast = announcing(ReasoningDepth.FAST);
        ApprovalTurnHarness careful = announcing(ReasoningDepth.CAREFUL);

        fast.chat("Build the middle report");
        careful.chat("Build the middle report");

        List<ChatTurn> streamed = fast.storedTranscript();
        List<ChatTurn> buffered = careful.storedTranscript();
        assertAll(
                () -> assertEquals(streamed.stream().map(ChatTurn::role).toList(), buffered.stream().map(ChatTurn::role).toList()),
                () -> assertEquals(streamed.get(1).content(), buffered.get(1).content()),
                () -> assertEquals(
                        streamed.get(1).tools().entries().stream().map(e -> e.tool() + ":" + e.status()).toList(),
                        buffered.get(1).tools().entries().stream().map(e -> e.tool() + ":" + e.status()).toList(),
                        "and the same record of what the tools did"));
    }

    // ------------------------------------------------------------------ what buffers a FAST turn

    private ApprovalTurnHarness withGenerationTool(Set<FastBufferTrigger> triggers) {
        return build(
                ApprovalTurnHarness.builder()
                        .tool(RecordingTool.returning("generate_image", "Generates an image", "{\"url\":\"https://x.test/i.png\"}"))
                        .parity(p -> p.setFastBufferingTriggers(triggers))
                        .responder(request -> ScriptedLlm.text("Here is a longer plain answer with no link in it.")));
    }

    @Test
    void aFastTurnThatOffersAGenerationToolIsBufferedByDefault() {
        ApprovalTurnHarness harness =
                withGenerationTool(EnumSet.of(FastBufferTrigger.GENERATION_TOOLS, FastBufferTrigger.MCP_TOOLS));

        List<String> chunks = harness.chatChunks("Hello there");

        assertEquals(1, chunks.size(), "held and sent as one piece: " + chunks);
    }

    @Test
    void withNoTriggersAFastTurnIsAlwaysStreamedEvenWithAGenerationTool() {
        ApprovalTurnHarness harness = withGenerationTool(EnumSet.noneOf(FastBufferTrigger.class));

        List<String> chunks = harness.chatChunks("Hello there");

        assertTrue(chunks.size() > 1, "streamed token by token: " + chunks);
    }

    @Test
    void aTriggerThatDoesNotApplyLeavesTheTurnStreamed() {
        ApprovalTurnHarness harness = withGenerationTool(EnumSet.of(FastBufferTrigger.MCP_TOOLS));

        List<String> chunks = harness.chatChunks("Hello there");

        assertTrue(chunks.size() > 1, "no MCP tool is offered, so MCP_TOOLS does not apply: " + chunks);
    }

    // ------------------------------------------------------------------ output guardrails

    private static final String PROFANE = "That is a damn shame, honestly.";

    private ApprovalTurnHarness profaneAgent(ReasoningDepth depth, Set<FastBufferTrigger> triggers) {
        return build(
                ApprovalTurnHarness.builder()
                        .depth(depth)
                        .tool(RecordingTool.returning("alpha_search", "Searches", "{}"))
                        .contentGuardrails(Set.of("profanity"), List.of(new ProfanityGuardrail()))
                        .parity(p -> p.setFastBufferingTriggers(triggers))
                        .responder(request -> ScriptedLlm.text(PROFANE)));
    }

    @Test
    void aStreamedReplyIsAuditedNotWithheldByDefaultBecauseTheTokensAlreadyLeft() {
        ApprovalTurnHarness fast = profaneAgent(ReasoningDepth.FAST, EnumSet.noneOf(FastBufferTrigger.class));

        String reply = fast.chat("How was it?");

        assertEquals(PROFANE, reply, "documented trade-off: FAST detects and audits, it cannot recall what was streamed");
    }

    @Test
    void aBufferedDepthWithholdsAndStoresTheReplacement() {
        ApprovalTurnHarness careful = profaneAgent(ReasoningDepth.CAREFUL, EnumSet.noneOf(FastBufferTrigger.class));

        String reply = careful.chat("How was it?");

        assertAll(
                () -> assertTrue(reply.contains("withheld"), reply),
                () -> assertFalse(reply.contains("damn"), reply),
                () -> assertFalse(careful.storedTranscript().get(1).content().contains("damn")));
    }

    @Test
    void withTheOutputGuardrailTriggerAFastTurnWithholdsLikeTheBufferedDepthAndStoresTheSameThing() {
        ApprovalTurnHarness careful = profaneAgent(ReasoningDepth.CAREFUL, EnumSet.noneOf(FastBufferTrigger.class));
        ApprovalTurnHarness fast = profaneAgent(ReasoningDepth.FAST, EnumSet.of(FastBufferTrigger.OUTPUT_GUARDRAILS));

        String bufferedReply = careful.chat("How was it?");
        List<String> streamedChunks = fast.chatChunks("How was it?");

        assertAll(
                () -> assertEquals(1, streamedChunks.size(), "held, checked, then sent: " + streamedChunks),
                () -> assertEquals(bufferedReply, String.join("", streamedChunks), "the person reads the same withheld notice"),
                () -> assertFalse(String.join("", streamedChunks).contains("damn")),
                () -> assertEquals(
                        careful.storedTranscript().get(1).content(),
                        fast.storedTranscript().get(1).content(),
                        "and the notice, not the violating text, is what the model is shown next turn"));
    }

    @Test
    void theOutputGuardrailTriggerLeavesACleanReplyUntouched() {
        ApprovalTurnHarness fast = build(
                ApprovalTurnHarness.builder()
                        .tool(RecordingTool.returning("alpha_search", "Searches", "{}"))
                        .contentGuardrails(Set.of("profanity"), List.of(new ProfanityGuardrail()))
                        .parity(p -> p.setFastBufferingTriggers(EnumSet.of(FastBufferTrigger.OUTPUT_GUARDRAILS)))
                        .responder(request -> ScriptedLlm.text("A perfectly polite answer.")));

        assertEquals("A perfectly polite answer.", fast.chat("How was it?"));
    }
}
