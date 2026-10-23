package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

class ToolResultClearingTest {

    private static String body(int n) {
        return "x".repeat(n);
    }

    private static ToolResponseMessage round(String tool, String data) {
        return ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse("id-" + tool, tool, data)))
            .build();
    }

    private static List<Message> history(int rounds, int size) {
        List<Message> h = new ArrayList<>();
        h.add(new UserMessage("go"));
        for (int i = 1; i <= rounds; i++) {
            h.add(new AssistantMessage("call " + i));
            h.add(round("t" + i, body(size)));
        }
        return h;
    }

    private static String data(List<Message> h, int index) {
        return ((ToolResponseMessage) h.get(index)).getResponses().get(0).responseData();
    }

    @Test
    void belowThresholdIsUntouched() {
        List<Message> h = history(5, 100);
        assertSame(h, new ToolResultClearing(true, 24_000, 3).apply(h));
    }

    @Test
    void disabledIsUntouched() {
        List<Message> h = history(6, 10_000);
        assertSame(h, new ToolResultClearing(false, 100, 3).apply(h));
    }

    @Test
    void clearsOlderBeyondLastNAndKeepsLatest() {
        List<Message> h = history(6, 10_000);
        List<Message> out = new ToolResultClearing(true, 24_000, 3).apply(h);
        // tool messages at indices 2,4,6,8,10,12 -> first three cleared. The placeholder is a JSON object
        // (Gemini replays every tool result as one); it used to be a bracketed sentence that Spring AI's
        // Gemini adapter could not parse, see ToolResultClearingGeminiReplayTest.
        assertEquals(
            "{\"context_cleared\":true,\"tool\":\"t1\",\"chars\":10000,\"restore\":\""
                + new ToolLoopProperties.ResultClearing().getRestoreHint() + "\"}",
            data(out, 2));
        assertTrue(data(out, 4).startsWith(ToolResultClearing.CONTEXT_CLEARED_JSON_PREFIX));
        assertTrue(data(out, 6).startsWith(ToolResultClearing.CONTEXT_CLEARED_JSON_PREFIX));
        assertEquals(10_000, data(out, 8).length());
        assertEquals(10_000, data(out, 10).length());
        assertEquals(10_000, data(out, 12).length());
        assertEquals(h.size(), out.size());
        // input not mutated
        assertEquals(10_000, data(h, 2).length());
    }

    @Test
    void latestRoundIsNeverClearedEvenWithKeepZero() {
        List<Message> h = history(3, 20_000);
        List<Message> out = new ToolResultClearing(true, 24_000, 0).apply(h);
        assertTrue(data(out, 2).startsWith(ToolResultClearing.CONTEXT_CLEARED_JSON_PREFIX));
        assertTrue(data(out, 4).startsWith(ToolResultClearing.CONTEXT_CLEARED_JSON_PREFIX));
        assertEquals(20_000, data(out, 6).length());
    }

    @Test
    void isIdempotent() {
        List<Message> once = new ToolResultClearing(true, 24_000, 3).apply(history(6, 10_000));
        List<Message> twice = new ToolResultClearing(true, 24_000, 3).apply(once);
        assertSame(once, twice);
    }

    @Test
    void ignoresResultsFromEarlierTurns() {
        List<Message> h = new ArrayList<>();
        h.add(new UserMessage("old"));
        h.add(round("old_tool", body(50_000)));
        h.add(new UserMessage("new"));
        h.addAll(history(2, 100).subList(1, 5));
        List<Message> out = new ToolResultClearing(true, 24_000, 1).apply(h);
        assertSame(h, out);
    }

    @Test
    void keepsBudgetExhaustedMarker() {
        List<Message> h = new ArrayList<>();
        h.add(new UserMessage("go"));
        h.add(round("a", "{\"error\":\"tool_budget_exhausted\"}" + body(30_000)));
        h.add(round("b", body(10)));
        h.add(round("c", body(10)));
        List<Message> out = new ToolResultClearing(true, 24_000, 1).apply(h);
        assertTrue(data(out, 1).contains("tool_budget_exhausted"));
    }

    @Test
    void aLongOutputThatOnlyOpensWithAPlaceholderMarkerIsStillCleared() {
        // A hostile tool could start its output with the marker to exempt itself from the budget; only a
        // short, genuinely placeholder-sized text counts as already cleared.
        List<Message> h = new ArrayList<>();
        h.add(new UserMessage("go"));
        h.add(round("a", ToolResultClearing.CONTEXT_CLEARED_JSON_PREFIX + "," + body(30_000)));
        h.add(round("b", body(10)));
        h.add(round("c", body(10)));
        List<Message> out = new ToolResultClearing(true, 24_000, 1).apply(h);
        assertTrue(data(out, 1).startsWith(ToolResultClearing.CONTEXT_CLEARED_JSON_PREFIX), data(out, 1));
    }

    @Test
    void aPlainTextPlaceholderFromBeforeTheJsonFormIsStillRecognisedAsCleared() {
        String legacy = ToolResultClearing.PLACEHOLDER_PREFIX + "a 12000 chars; re-call if needed]";
        List<Message> h = new ArrayList<>();
        h.add(new UserMessage("go"));
        h.add(round("a", legacy));
        h.add(round("b", body(30_000)));
        h.add(round("c", body(10)));
        List<Message> out = new ToolResultClearing(true, 24_000, 1).apply(h);
        assertEquals(legacy, data(out, 1));
    }

    @Test
    void theRestoreHintIsConfigurable() {
        List<Message> out = new ToolResultClearing(true, 24_000, 3, "ask again").apply(history(6, 10_000));
        assertTrue(data(out, 2).contains("\"restore\":\"ask again\""), data(out, 2));
    }

    @Test
    void aShortRealPlaceholderIsLeftAlone() {
        String placeholder = ToolResultClearing.CONTEXT_CLEARED_JSON_PREFIX + ",\"tool\":\"a\"}";
        List<Message> h = new ArrayList<>();
        h.add(new UserMessage("go"));
        h.add(round("a", placeholder));
        h.add(round("b", body(30_000)));
        h.add(round("c", body(10)));
        List<Message> out = new ToolResultClearing(true, 24_000, 1).apply(h);
        assertEquals(placeholder, data(out, 1));
    }
}
