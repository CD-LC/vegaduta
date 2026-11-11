package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link GuardrailFailMode} decides what a guardrail does when its own machinery fails - closed
 * (refuse) is the default and only an explicit, case-insensitive {@code "open"} opts out of it. The
 * mode is held in a static because the turn-path classes that consult it are built with {@code
 * new}; every test restores {@link GuardrailFailMode#CLOSED} afterwards so no mode leaks into
 * another test.
 */
class GuardrailFailModeTest {

    @AfterEach
    void resetFailMode() {
        GuardrailFailMode.set(GuardrailFailMode.CLOSED);
    }

    @Test
    void closedIsTheDefault() {
        assertEquals(GuardrailFailMode.CLOSED, GuardrailFailMode.current());
        assertTrue(GuardrailFailMode.isClosed());
    }

    @Test
    void setSwitchesTheModeUntilCleared() {
        GuardrailFailMode.set(GuardrailFailMode.OPEN);
        assertEquals(GuardrailFailMode.OPEN, GuardrailFailMode.current());
        assertFalse(GuardrailFailMode.isClosed());
    }

    @Test
    void setWithNullResetsToClosed() {
        GuardrailFailMode.set(GuardrailFailMode.OPEN);
        GuardrailFailMode.set(null);
        assertEquals(GuardrailFailMode.CLOSED, GuardrailFailMode.current());
    }

    @Test
    void parseRecognisesOnlyOpenCaseInsensitively() {
        assertEquals(GuardrailFailMode.OPEN, GuardrailFailMode.parse("open"));
        assertEquals(GuardrailFailMode.OPEN, GuardrailFailMode.parse("OPEN"));
        assertEquals(GuardrailFailMode.OPEN, GuardrailFailMode.parse("  Open "));
    }

    @Test
    void parseTreatsEverythingElseAsClosed() {
        assertEquals(GuardrailFailMode.CLOSED, GuardrailFailMode.parse(null));
        assertEquals(GuardrailFailMode.CLOSED, GuardrailFailMode.parse(""));
        assertEquals(GuardrailFailMode.CLOSED, GuardrailFailMode.parse("closed"));
        assertEquals(GuardrailFailMode.CLOSED, GuardrailFailMode.parse("yes"));
        assertEquals(GuardrailFailMode.CLOSED, GuardrailFailMode.parse("opensesame"));
    }
}
