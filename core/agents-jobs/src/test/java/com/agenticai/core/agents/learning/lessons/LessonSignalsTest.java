package com.agenticai.core.agents.learning.lessons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.tools.ToolCallLog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LessonSignalsTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-10-18T09:00:00Z");

    private static ToolCallLog call(String tool, String input, String output, String error, int sec) {
        Instant at = T0.plusSeconds(sec);
        return new ToolCallLog(AGENT, "s1", tool, input, output, error, at, at.plusMillis(100));
    }

    // ---------------------------------------------------------------- corrections

    @Test
    void recognisesCommonCorrections() {
        for (String m :
                List.of(
                        "No, I meant the Friday meeting",
                        "That's wrong, the invoice is for October",
                        "that is not what I asked for",
                        "Use the work calendar instead",
                        "Wrong. It should be in euros",
                        "You misunderstood me",
                        "Actually, it was the second one",
                        "I said tomorrow, not today",
                        "Please don't use bullet points",
                        "Nope, the other file")) {
            assertTrue(LessonSignals.looksLikeCorrection(m), m);
        }
    }

    @Test
    void ordinaryMessagesAreNotCorrections() {
        for (String m :
                List.of(
                        "Thanks, that works",
                        "No thanks",
                        "What is the weather in Pune today?",
                        "Summarise this document for me",
                        "Not now, maybe later",
                        "Could you book a table for two?",
                        "")) {
            assertFalse(LessonSignals.looksLikeCorrection(m), m);
        }
        assertFalse(LessonSignals.looksLikeCorrection(null));
    }

    @Test
    void aLongMessageIsANewRequestNotACorrection() {
        String longRequest = "No, I meant " + "the quarterly figures and more ".repeat(40);
        assertTrue(longRequest.length() > LessonSignals.MAX_CORRECTION_CHARS);
        assertFalse(LessonSignals.looksLikeCorrection(longRequest));
    }

    // ---------------------------------------------------------------- retried tool calls

    @Test
    void aFailureFixedByDifferentArgumentsIsFound() {
        ToolCallLog bad = call("book_slot", "{\"cal\":\"primary\"}", null, "404 calendar not found", 1);
        ToolCallLog good = call("book_slot", "{\"cal\":\"work\"}", "booked", null, 2);
        var fix = LessonSignals.retriedFix(List.of(call("search", "{}", "ok", null, 0), bad, good));
        assertTrue(fix.isPresent());
        assertEquals("{\"cal\":\"primary\"}", fix.get().failed().getToolInput());
        assertEquals("{\"cal\":\"work\"}", fix.get().fixed().getToolInput());
    }

    @Test
    void theSameArgumentsRetriedIsNotAFix() {
        var calls =
                List.of(
                        call("book_slot", "{\"cal\": \"primary\"}", null, "boom", 1),
                        call("book_slot", "{\"cal\":\"primary\"}", "booked", null, 2));
        assertTrue(LessonSignals.retriedFix(calls).isEmpty());
    }

    @Test
    void aDifferentToolOrAnotherFailureIsNotAFix() {
        assertTrue(
                LessonSignals.retriedFix(
                                List.of(
                                        call("a", "{\"x\":1}", null, "invalid header", 1),
                                        call("b", "{\"x\":2}", "ok", null, 2)))
                        .isEmpty());
        assertTrue(
                LessonSignals.retriedFix(
                                List.of(
                                        call("a", "{\"x\":1}", null, "invalid header", 1),
                                        call("a", "{\"x\":2}", null, "still invalid", 2)))
                        .isEmpty());
    }

    @Test
    void transientFailuresTeachNothing() {
        for (String err :
                List.of("Read timed out", "429 Too Many Requests", "HTTP 503", "connection refused", "rate limit hit")) {
            var calls =
                    List.of(call("fetch", "{\"u\":1}", null, err, 1), call("fetch", "{\"u\":2}", "ok", null, 2));
            assertTrue(LessonSignals.retriedFix(calls).isEmpty(), err);
        }
    }

    @Test
    void aRetryTooFarAwayIsNotLinkedToTheFailure() {
        var calls = new java.util.ArrayList<ToolCallLog>();
        calls.add(call("fetch", "{\"u\":1}", null, "bad parameter", 0));
        for (int i = 0; i < LessonSignals.RETRY_WINDOW; i++) {
            calls.add(call("other", "{}", "ok", null, 1 + i));
        }
        calls.add(call("fetch", "{\"u\":2}", "ok", null, 99));
        assertTrue(LessonSignals.retriedFix(calls).isEmpty());
    }

    @Test
    void noCallsOrOneCallGiveNothing() {
        assertTrue(LessonSignals.retriedFix(null).isEmpty());
        assertTrue(LessonSignals.retriedFix(List.of()).isEmpty());
        assertTrue(LessonSignals.retriedFix(List.of(call("a", "{}", null, "bad", 1))).isEmpty());
    }
}
