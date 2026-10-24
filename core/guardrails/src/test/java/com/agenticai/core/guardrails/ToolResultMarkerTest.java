package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ToolResultMarkerTest {

    @Test
    void aRegisteredTextIsAPlatformAnswerOfTheRegisteredKind_whateverItSays() {
        String text = "Waiting for a person to say yes " + System.nanoTime();

        assertFalse(ToolResultMarker.isPlatformAnswer(text));
        assertSame(text, ToolResultMarker.mark(text, ToolResultMarker.Kind.PARKED));

        assertTrue(ToolResultMarker.isPlatformAnswer(text));
        assertEquals(Optional.of(ToolResultMarker.Kind.PARKED), ToolResultMarker.kindOf(text));
        // An equal text built elsewhere is the same answer: the registry is by text, not by instance, so a copy made
        // by a wrapper on the way out is still recognised.
        assertEquals(Optional.of(ToolResultMarker.Kind.PARKED), ToolResultMarker.kindOf(new String(text)));
    }

    @Test
    void anUnregisteredPlatformWordingIsStillRecognisedAndClassified() {
        assertTrue(ToolResultMarker.isPlatformAnswer("This tool call ('t') was not executed: blocked"));
        assertEquals(
                Optional.of(ToolResultMarker.Kind.BLOCKED),
                ToolResultMarker.classify("This tool call ('t') was not executed: blocked"));
        assertEquals(
                Optional.of(ToolResultMarker.Kind.PARKED),
                ToolResultMarker.classify("This action ('t') requires human approval before it can run."));
        assertEquals(
                Optional.of(ToolResultMarker.Kind.WITHHELD),
                ToolResultMarker.classify("Tool output from 't' was withheld by a content-safety check"));
        assertEquals(
                Optional.of(ToolResultMarker.Kind.BUDGET_EXHAUSTED),
                ToolResultMarker.classify(ToolBudgetExhausted.result("Do not call more tools.")));
        assertTrue(ToolResultMarker.isPlatformAnswer(ToolCallOutputValidator.VALIDATION_FAILURE_PREFIX + "x"));
    }

    @Test
    void aToolsOutputAndNullAreNotPlatformAnswers() {
        assertFalse(ToolResultMarker.isPlatformAnswer("{\"status\":\"sent\"}"));
        assertFalse(ToolResultMarker.isPlatformAnswer(null));
        assertTrue(ToolResultMarker.classify("{\"status\":\"sent\"}").isEmpty());
        assertTrue(ToolResultMarker.kindOf(null).isEmpty());
        assertEquals(null, ToolResultMarker.mark(null, ToolResultMarker.Kind.BLOCKED));
    }

    private static void assertSame(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertSame(expected, actual);
    }
}
