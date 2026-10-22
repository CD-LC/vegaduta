package com.agenticai.core.agents.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.context.ContextCompactionEngine.Params;
import com.agenticai.core.agents.context.ContextCompactionEngine.Result;
import com.agenticai.core.agents.context.ContextCompactionEngine.Summarizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The engine over real Spring AI messages: clearing with restorable placeholders, compaction
 * through a summariser, and the guarantees around them (never touch what the model has not seen,
 * system messages, pinned items; never mutate the input; idempotent; summariser failure keeps every
 * turn).
 *
 * <p>Token maths: the estimate is chars / 4 for ASCII plus 4 per message, so a 8_000-char body is
 * ~2_004 tokens.
 */
class ContextCompactionEngineTest {

    private static final ObjectMapper JSON = com.agenticai.core.json.JsonMappers.plain();
    private final ContextCompactionEngine engine = new ContextCompactionEngine();

    /**
     * window 10_000, clear above 6_000, compact above 8_500, keep 2 tool results, keep 2 recent
     * messages.
     */
    private static final Params PARAMS = new Params(10_000, 0.6, 0.85, 2, 2, 100, true);

    /** A fresh mutable metadata map flagging a message as pinned. */
    private static Map<String, Object> pinned() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ContextCompactionEngine.PIN_METADATA_KEY, true);
        return metadata;
    }

    private static Summarizer failing() {
        return (existing, turns) -> Optional.empty();
    }

    private static AssistantMessage call(String id, String tool, String args) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", tool, args)))
                .build();
    }

    private static ToolResponseMessage result(String id, String tool, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, tool, data)))
                .build();
    }

    private static String data(Message toolMessage) {
        return ((ToolResponseMessage) toolMessage).getResponses().get(0).responseData();
    }

    /** system, user, then {@code rounds} x (assistant tool call, 8_000-char tool result). */
    private static List<Message> toolRun(int rounds) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("be helpful"));
        messages.add(new UserMessage("research this"));
        for (int i = 1; i <= rounds; i++) {
            messages.add(call("c" + i, "search", "{\"q\":\"topic " + i + "\"}"));
            messages.add(result("c" + i, "search", "x".repeat(8_000)));
        }
        return messages;
    }

    // ---------------------------------------------------------------- clearing

    @Test
    void belowTheThresholdTheVeryListComesBack() {
        List<Message> messages = toolRun(2); // ~4_000 tokens

        Result result = engine.apply(messages, 0, PARAMS, failing());

        assertSame(messages, result.messages());
        assertFalse(result.changed());
    }

    @Test
    void aWindowOfZeroLeavesEverythingAlone() {
        List<Message> messages = toolRun(6);

        Result result =
                engine.apply(messages, 0, new Params(0, 0.6, 0.85, 2, 2, 100, true), failing());

        assertSame(messages, result.messages());
    }

    @Test
    void oldToolResultsBecomeRestorablePlaceholdersAndTheNewestTwoStay() {
        List<Message> messages = toolRun(5); // ~10_000 tokens > 6_000

        Result result = engine.apply(messages, 0, PARAMS, failing());

        assertEquals(3, result.clearedToolResults());
        assertEquals(0, result.compactedMessages());
        assertTrue(result.tokensAfter() < result.tokensBefore());
        List<Message> out = result.messages();
        assertEquals(
                messages.size(),
                out.size(),
                "clearing keeps every message, tool-call pairing included");
        for (int round = 1; round <= 3; round++) {
            assertTrue(
                    data(out.get(1 + 2 * round)).startsWith("{\"context_cleared\":true"),
                    "round " + round);
        }
        assertEquals("x".repeat(8_000), data(out.get(9)), "second newest result stays");
        assertEquals("x".repeat(8_000), data(out.get(11)), "newest result stays");
    }

    @Test
    void thePlaceholderIsAJsonObjectCarryingToolArgsDigestStatusSizeAndRestorePointer()
            throws Exception {
        List<Message> messages = toolRun(5);

        String placeholder = data(engine.apply(messages, 0, PARAMS, failing()).messages().get(3));

        JsonNode node = JSON.readTree(placeholder);
        assertTrue(node.path("context_cleared").asBoolean());
        assertEquals("search", node.path("tool").asString());
        assertEquals(
                ToolResultRestoreIds.digest("{\"q\":\"topic 1\"}"), node.path("args").asString());
        assertEquals("ok", node.path("status").asString());
        assertEquals(8_000, node.path("chars").asInt());
        String stepId = ToolResultRestoreIds.stepId("search", "{\"q\":\"topic 1\"}");
        assertTrue(node.path("restore").asString().contains("restore_tool_result"));
        assertTrue(node.path("restore").asString().contains(stepId));
    }

    @Test
    void withoutTheRestoreToolThePointerSaysToRunTheToolAgain() throws Exception {
        Params noRestore = new Params(10_000, 0.6, 0.85, 2, 2, 100, false);

        String placeholder =
                data(engine.apply(toolRun(5), 0, noRestore, failing()).messages().get(3));

        String restore = JSON.readTree(placeholder).path("restore").asString();
        assertFalse(restore.contains("restore_tool_result"));
        assertTrue(restore.contains("run the tool again"));
    }

    @Test
    void aFailureLookingResultIsMarkedAsAnErrorStatus() throws Exception {
        List<Message> messages = toolRun(5);
        messages.set(3, result("c1", "search", "Error: upstream timed out " + "x".repeat(8_000)));

        String placeholder = data(engine.apply(messages, 0, PARAMS, failing()).messages().get(3));

        assertEquals("error", JSON.readTree(placeholder).path("status").asString());
    }

    @Test
    void theInputListAndItsMessagesAreNeverMutated() {
        List<Message> messages = toolRun(5);
        List<Message> snapshot = new ArrayList<>(messages);
        String firstResult = data(messages.get(3));

        engine.apply(messages, 0, PARAMS, failing());

        assertEquals(snapshot, messages);
        assertEquals(firstResult, data(messages.get(3)));
    }

    @Test
    void systemMessagesTheLatestUserAndTheUnseenLatestResultAreSameInstances() {
        List<Message> messages = toolRun(5);

        List<Message> out = engine.apply(messages, 0, PARAMS, failing()).messages();

        assertSame(messages.get(0), out.get(0));
        assertSame(messages.get(1), out.get(1));
        assertSame(messages.get(messages.size() - 1), out.get(out.size() - 1));
    }

    @Test
    void aHugeResultTheModelHasNotSeenYetIsNeverClearedEvenWhenItAloneBustsTheBudget() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("read it"));
        messages.add(call("c1", "fetch_web_page", "{}"));
        messages.add(result("c1", "fetch_web_page", "y".repeat(200_000)));

        Result result = engine.apply(messages, 0, PARAMS, failing());

        assertSame(messages, result.messages());
    }

    @Test
    void pinnedToolResultsAreNeverCleared() {
        List<Message> messages = toolRun(5);
        messages.set(
                3,
                ToolResponseMessage.builder()
                        .responses(
                                List.of(
                                        new ToolResponseMessage.ToolResponse(
                                                "c1", "search", "x".repeat(8_000))))
                        .metadata(pinned())
                        .build());

        List<Message> out = engine.apply(messages, 0, PARAMS, failing()).messages();

        assertSame(messages.get(3), out.get(3));
        assertTrue(data(out.get(5)).startsWith("{\"context_cleared\":true"));
    }

    @Test
    void placeholdersFromTheCharacterThresholdClearingCountAsAlreadyCleared() {
        List<Message> messages = toolRun(5);
        String legacy =
                "[tool result cleared to save context: search 8000 chars; re-call if needed]";
        messages.set(3, result("c1", "search", legacy));

        List<Message> out = engine.apply(messages, 0, PARAMS, failing()).messages();

        assertEquals(legacy, data(out.get(3)), "never rewritten with the digest placeholder");
    }

    @Test
    void runningTwiceYieldsTheSameView() {
        List<Message> messages = toolRun(6);

        Result first = engine.apply(messages, 0, PARAMS, failing());
        Result second = engine.apply(first.messages(), 0, PARAMS, failing());

        assertTrue(first.changed());
        assertFalse(second.changed());
        assertSame(first.messages(), second.messages());
    }

    @Test
    void aToolMessageWithSeveralResponsesClearsEachOneIndependently() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("go"));
        messages.add(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall("a", "function", "t1", "{}"),
                                        new AssistantMessage.ToolCall("b", "function", "t2", "{}")))
                        .build());
        messages.add(
                ToolResponseMessage.builder()
                        .responses(
                                List.of(
                                        new ToolResponseMessage.ToolResponse(
                                                "a", "t1", "x".repeat(8_000)),
                                        new ToolResponseMessage.ToolResponse(
                                                "b", "t2", "x".repeat(8_000))))
                        .build());
        for (int i = 3; i <= 5; i++) {
            messages.add(call("c" + i, "t" + i, "{}"));
            messages.add(result("c" + i, "t" + i, "x".repeat(8_000)));
        }

        ToolResponseMessage first =
                (ToolResponseMessage)
                        engine.apply(messages, 0, PARAMS, failing()).messages().get(2);

        assertEquals(2, first.getResponses().size());
        assertEquals("a", first.getResponses().get(0).id());
        assertTrue(
                first.getResponses().get(0).responseData().startsWith("{\"context_cleared\":true"));
        assertTrue(
                first.getResponses().get(1).responseData().startsWith("{\"context_cleared\":true"));
    }

    // -------------------------------------------------------------- compaction

    /**
     * A memory-block system message followed by {@code exchanges} user/assistant pairs of ~754
     * tokens each message.
     */
    private static List<Message> longChat(int exchanges) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("memory block"));
        for (int i = 1; i <= exchanges; i++) {
            messages.add(new UserMessage("u" + i + " " + "q".repeat(3_000)));
            messages.add(new AssistantMessage("a" + i + " " + "r".repeat(3_000)));
        }
        return messages;
    }

    @Test
    void theOldestTurnsAreReplacedByOneSummaryMessage() {
        List<Message> messages = longChat(8); // 1 + 16 messages, ~12_000 tokens > 8_500
        AtomicReference<String> existing = new AtomicReference<>("untouched");
        AtomicReference<List<Message>> seenTurns = new AtomicReference<>();
        Summarizer summarizer =
                (prior, turns) -> {
                    existing.set(prior);
                    seenTurns.set(turns);
                    return Optional.of("- goal: research X\n- file: notes.md");
                };

        Result result = engine.apply(messages, 0, PARAMS, summarizer);

        assertEquals(10, result.compactedMessages());
        assertNull(existing.get(), "no earlier summary was rolled in");
        assertEquals(10, seenTurns.get().size());
        assertSame(
                messages.get(1),
                seenTurns.get().get(0),
                "the summariser sees the oldest turn first");
        List<Message> out = result.messages();
        assertEquals(8, out.size());
        assertSame(messages.get(0), out.get(0), "the memory block stays first");
        assertTrue(out.get(1) instanceof SystemMessage);
        assertTrue(out.get(1).getText().startsWith(ContextCompactionEngine.SUMMARY_PREFIX));
        assertTrue(out.get(1).getText().contains("notes.md"));
        assertSame(messages.get(11), out.get(2), "what is kept opens with a user turn");
        assertTrue(out.get(2) instanceof UserMessage);
        assertTrue(result.tokensAfter() < result.tokensBefore());
    }

    @Test
    void compactionNeverMutatesTheInputOrTheStoredTurnsItWasGiven() {
        List<Message> messages = longChat(8);
        List<Message> snapshot = new ArrayList<>(messages);

        engine.apply(messages, 0, PARAMS, (prior, turns) -> Optional.of("summary"));

        assertEquals(snapshot, messages);
        assertEquals(17, messages.size());
    }

    @Test
    void aSummariserThatCannotProduceASummaryKeepsEveryTurn() {
        List<Message> messages = longChat(8);

        Result result = engine.apply(messages, 0, PARAMS, failing());

        assertSame(messages, result.messages());
        assertFalse(result.changed());
    }

    @Test
    void aSummariserThatThrowsKeepsEveryTurn() {
        List<Message> messages = longChat(8);

        Result result =
                engine.apply(
                        messages,
                        0,
                        PARAMS,
                        (prior, turns) -> {
                            throw new IllegalStateException("provider down");
                        });

        assertSame(messages, result.messages());
    }

    @Test
    void aBlankSummaryIsTreatedAsAFailure() {
        List<Message> messages = longChat(8);

        Result result = engine.apply(messages, 0, PARAMS, (prior, turns) -> Optional.of("   "));

        assertSame(messages, result.messages());
    }

    @Test
    void noSummariserMeansClearingOnlyAndNeverAnException() {
        List<Message> messages = longChat(8);

        Result result = engine.apply(messages, 0, PARAMS, null);

        assertSame(messages, result.messages());
    }

    @Test
    void anEarlierSummaryIsRolledIntoTheNextOne() {
        List<Message> messages = longChat(8);
        messages.add(
                1,
                new SystemMessage(
                        ContextCompactionEngine.SUMMARY_PREFIX + "earlier: user wants X"));
        AtomicReference<String> existing = new AtomicReference<>();

        Result result =
                engine.apply(
                        messages,
                        0,
                        PARAMS,
                        (prior, turns) -> {
                            existing.set(prior);
                            return Optional.of("merged summary");
                        });

        assertEquals("earlier: user wants X", existing.get());
        long summaries =
                result.messages().stream()
                        .filter(
                                m ->
                                        m instanceof SystemMessage
                                                && m.getText()
                                                        .startsWith(
                                                                ContextCompactionEngine
                                                                        .SUMMARY_PREFIX))
                        .count();
        assertEquals(1, summaries, "one summary, never a growing stack of them");
    }

    @Test
    void aPinnedTurnSurvivesCompactionInPlace() {
        List<Message> messages = longChat(8);
        Message pinned =
                UserMessage.builder().text("u1 " + "q".repeat(3_000)).metadata(pinned()).build();
        messages.set(1, pinned);

        List<Message> out =
                engine.apply(
                                messages,
                                0,
                                PARAMS,
                                (prior, turns) -> {
                                    assertFalse(
                                            turns.contains(pinned),
                                            "a pinned turn is never handed to the summariser");
                                    return Optional.of("summary");
                                })
                        .messages();

        assertTrue(out.contains(pinned));
    }

    @Test
    void theLatestUserMessageAndTheRecentTailAreNeverSummarisedAway() {
        List<Message> messages = longChat(8);
        Message latestUser = messages.get(15);
        Message latestReply = messages.get(16);

        List<Message> out =
                engine.apply(messages, 0, PARAMS, (prior, turns) -> Optional.of("summary"))
                        .messages();

        assertSame(latestUser, out.get(out.size() - 2));
        assertSame(latestReply, out.get(out.size() - 1));
    }

    @Test
    void summaryTextIsCappedToItsTokenBudget() {
        List<Message> messages = longChat(8);

        List<Message> out =
                engine.apply(messages, 0, PARAMS, (prior, turns) -> Optional.of("w".repeat(40_000)))
                        .messages();

        String text = out.get(1).getText();
        assertTrue(text.length() < 1_000, "capped to ~100 tokens, was " + text.length() + " chars");
    }

    @Test
    void compactionTwiceYieldsTheSameView() {
        List<Message> messages = longChat(8);
        AtomicInteger calls = new AtomicInteger();
        Summarizer summarizer =
                (prior, turns) -> {
                    calls.incrementAndGet();
                    return Optional.of("a short summary");
                };

        Result first = engine.apply(messages, 0, PARAMS, summarizer);
        Result second = engine.apply(first.messages(), 0, PARAMS, summarizer);

        assertTrue(first.changed());
        assertFalse(second.changed());
        assertSame(first.messages(), second.messages());
        assertEquals(1, calls.get(), "the second pass never even asks the summariser");
    }

    @Test
    void clearingAndCompactionCanBothApplyInOnePass() {
        // 6 big tool rounds after a long chat: clearing alone cannot get under the line
        List<Message> messages = longChat(4);
        messages.add(new UserMessage("now research " + "z".repeat(2_000)));
        for (int i = 1; i <= 4; i++) {
            messages.add(call("c" + i, "search", "{\"i\":" + i + "}"));
            messages.add(result("c" + i, "search", "x".repeat(8_000)));
        }

        Result result = engine.apply(messages, 0, PARAMS, (prior, turns) -> Optional.of("summary"));

        assertTrue(result.clearedToolResults() > 0);
        assertTrue(result.compactedMessages() > 0);
        assertTrue(result.tokensAfter() < result.tokensBefore());
        assertNotSame(messages, result.messages());
    }

    @Test
    void fixedOverheadPushesAnOtherwiseQuietPromptOverTheLine() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("go"));
        for (int i = 1; i <= 3; i++) {
            messages.add(call("c" + i, "search", "{}"));
            messages.add(
                    result(
                            "c" + i,
                            "search",
                            "x".repeat(6_000))); // ~1_500 tokens each, ~4_600 in all
        }

        assertFalse(engine.apply(messages, 0, PARAMS, failing()).changed());
        assertTrue(engine.apply(messages, 2_000, PARAMS, failing()).changed());
    }

    @Test
    void tokenEstimateIsConsistentBetweenItemsAndTheWholeList() {
        List<Message> messages = toolRun(3);

        int sum = 0;
        for (Message m : messages) {
            sum += ContextCompactionEngine.messageTokens(m);
        }

        assertEquals(sum, ContextCompactionEngine.estimateAll(messages));
        assertEquals(sum, engine.apply(messages, 0, PARAMS, failing()).tokensBefore());
    }

    // ------------------------------------------- providers that give calls and responses a blank
    // id

    @Test
    void blankToolCallIdsStillPairEachPlaceholderWithItsOwnArguments() throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("be helpful"));
        messages.add(new UserMessage("research this"));
        for (int i = 1; i <= 5; i++) {
            messages.add(call("", "search", "{\"q\":\"topic " + i + "\"}"));
            messages.add(result("", "search", "x".repeat(8_000)));
        }

        List<Message> out = engine.apply(messages, 0, PARAMS, failing()).messages();

        for (int round = 1; round <= 3; round++) {
            JsonNode node = JSON.readTree(data(out.get(1 + 2 * round)));
            assertEquals(
                    ToolResultRestoreIds.digest("{\"q\":\"topic " + round + "\"}"),
                    node.path("args").asString(),
                    "round " + round + " must keep its own arguments, not the last call's");
            assertTrue(
                    node.path("restore")
                            .asString()
                            .contains(
                                    ToolResultRestoreIds.stepId(
                                            "search", "{\"q\":\"topic " + round + "\"}")),
                    "round " + round);
        }
    }

    @Test
    void blankIdsInOneMessageWithTwoCallsOfTheSameToolPairByPosition() throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("go"));
        messages.add(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "", "function", "search", "{\"q\":\"first\"}"),
                                        new AssistantMessage.ToolCall(
                                                "", "function", "search", "{\"q\":\"second\"}")))
                        .build());
        messages.add(
                ToolResponseMessage.builder()
                        .responses(
                                List.of(
                                        new ToolResponseMessage.ToolResponse(
                                                "", "search", "x".repeat(8_000)),
                                        new ToolResponseMessage.ToolResponse(
                                                "", "search", "x".repeat(8_000))))
                        .build());
        for (int i = 3; i <= 5; i++) {
            messages.add(call("", "t" + i, "{}"));
            messages.add(result("", "t" + i, "x".repeat(8_000)));
        }

        ToolResponseMessage first =
                (ToolResponseMessage)
                        engine.apply(messages, 0, PARAMS, failing()).messages().get(2);

        assertEquals(
                ToolResultRestoreIds.digest("{\"q\":\"first\"}"),
                JSON.readTree(first.getResponses().get(0).responseData()).path("args").asString());
        assertEquals(
                ToolResultRestoreIds.digest("{\"q\":\"second\"}"),
                JSON.readTree(first.getResponses().get(1).responseData()).path("args").asString());
    }

    // ------------------------------------------------------------- hostile markers

    @Test
    void aLongToolOutputThatOnlyOpensWithTheClearedMarkerIsStillCleared() {
        List<Message> messages = toolRun(5);
        messages.set(
                3,
                result(
                        "c1",
                        "search",
                        "{\"context_cleared\":true,\"note\":\"" + "x".repeat(8_000)));

        List<Message> out = engine.apply(messages, 0, PARAMS, failing()).messages();

        assertTrue(data(out.get(3)).length() < 1_000, "replaced by a real placeholder");
        assertTrue(data(out.get(3)).contains("\"restore\""));
    }
}
