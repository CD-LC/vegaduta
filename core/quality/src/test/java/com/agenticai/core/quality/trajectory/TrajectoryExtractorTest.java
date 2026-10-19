package com.agenticai.core.quality.trajectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.quality.dataset.AgentTurnReplayer.ReplayedToolCall;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrajectoryExtractorTest {

    @Test
    void replayedCallsKeepOrderToolArgsAndOk() {
        List<TrajectoryToolCall> steps = TrajectoryExtractor.fromReplayed(List.of(
            new ReplayedToolCall("search", "{\"q\":\"refund\",\"n\":3}", "{\"hits\":[]}"),
            new ReplayedToolCall("send_email", "{\"to\":\"a@b.c\"}", "sent")));

        assertEquals(2, steps.size());
        assertEquals("search", steps.get(0).tool());
        assertEquals("refund", steps.get(0).args().get("q").asText());
        assertEquals(3, steps.get(0).args().get("n").asInt());
        assertTrue(steps.get(0).ok());
        assertEquals("send_email", steps.get(1).tool());
    }

    @Test
    void callsThatDidNotRunAreNotOk() {
        for (String refused : new String[] {
            "This tool call ('x') was not executed: its arguments broke this agent's argument policy",
            "This tool call failed: boom",
            "This action ('x') requires human approval before it can run and was NOT executed.",
            "This tool ('x') has been temporarily disabled after repeated failures",
            "Tool output from 'x' was withheld by a content-safety check",
            "[TOOL_OUTPUT_VALIDATION_FAILED] Tool output from 'x' failed validation"
        }) {
            List<TrajectoryToolCall> steps = TrajectoryExtractor.fromReplayed(
                List.of(new ReplayedToolCall("x", "{}", refused)));
            assertFalse(steps.get(0).ok(), refused);
        }
        assertTrue(TrajectoryExtractor.fromReplayed(List.of(new ReplayedToolCall("x", "{}", "This tool call was great")))
            .get(0).ok());
        assertTrue(TrajectoryExtractor.fromReplayed(List.of(new ReplayedToolCall("x", "{}", null))).get(0).ok());
    }

    @Test
    void oddInputsDegradeInsteadOfThrowing() {
        List<TrajectoryToolCall> steps = TrajectoryExtractor.fromReplayed(List.of(
            new ReplayedToolCall("a", null, "ok"),
            new ReplayedToolCall("b", "  ", "ok"),
            new ReplayedToolCall("c", "{not json", "ok"),
            new ReplayedToolCall(null, "{}", "ok")));
        assertTrue(steps.get(0).args().isObject());
        assertTrue(steps.get(1).args().isObject());
        assertTrue(steps.get(2).args().isMissingNode());
        assertEquals("", steps.get(3).tool());
        assertTrue(TrajectoryExtractor.fromReplayed(null).isEmpty());
        assertTrue(TrajectoryExtractor.fromRaw(null).isEmpty());
        assertTrue(TrajectoryExtractor.fromReplayed(List.of()).isEmpty());
    }

    @Test
    void theStoredToolCallsDocumentIsReadBack() {
        String stored = "[{\"toolName\":\"search\",\"input\":\"{\\\"q\\\":\\\"x\\\"}\",\"output\":\"hits\"},"
            + "{\"toolName\":\"send\",\"input\":\"{}\",\"output\":\"This tool call ('send') was not executed: nope\"}]";
        List<TrajectoryToolCall> steps = TrajectoryExtractor.fromToolCallsJson(stored);
        assertEquals(2, steps.size());
        assertEquals("x", steps.get(0).args().get("q").asText());
        assertTrue(steps.get(0).ok());
        assertFalse(steps.get(1).ok());
    }

    @Test
    void unreadableStoredDocumentsAreAnEmptyTrajectory() {
        assertTrue(TrajectoryExtractor.fromToolCallsJson(null).isEmpty());
        assertTrue(TrajectoryExtractor.fromToolCallsJson("").isEmpty());
        assertTrue(TrajectoryExtractor.fromToolCallsJson("not json").isEmpty());
        assertTrue(TrajectoryExtractor.fromToolCallsJson("{\"toolName\":\"a\"}").isEmpty());
    }

    @Test
    void aRunawayCallListIsCapped() {
        List<TrajectoryExtractor.RawCall> many = new ArrayList<>();
        for (int i = 0; i < TrajectoryExtractor.MAX_STEPS + 25; i++) {
            many.add(new TrajectoryExtractor.RawCall("t", "{}", "ok"));
        }
        assertEquals(TrajectoryExtractor.MAX_STEPS, TrajectoryExtractor.fromRaw(many).size());
    }
}
