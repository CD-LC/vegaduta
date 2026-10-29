package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link FollowUpProperties} loads the short-follow-up rules from {@code
 * /chat/follow-up.properties}. These pin the shipped defaults and the {@link
 * FollowUpProperties#isShortFollowUp} boundary, which is what decides whether a turn is judged on
 * the conversation rather than on the message.
 */
class FollowUpPropertiesTest {

    private final FollowUpProperties props = new FollowUpProperties();

    @Test
    void defaultsComeFromTheClasspathResource() {
        assertTrue(props.isStickyToolsEnabled());
        assertEquals(40, props.getShortMessageChars());
        assertEquals(Duration.ofHours(2), props.getStickyToolsWindow());
        assertEquals(12, props.getMaxStickyTools());
        assertTrue(props.isKeepPrimaryModel());
    }

    @Test
    void aMessageAtTheLimitIsAFollowUpAndOnePastItIsNot() {
        assertTrue(props.isShortFollowUp("yes"));
        assertTrue(props.isShortFollowUp("x".repeat(40)));
        assertFalse(props.isShortFollowUp("x".repeat(41)));
        // Trimming happens before the count: surrounding whitespace is free.
        assertTrue(props.isShortFollowUp("   " + "x".repeat(40) + "   "));
    }

    @Test
    void blankAndNullMessagesAreNeverFollowUps() {
        assertFalse(props.isShortFollowUp(null));
        assertFalse(props.isShortFollowUp(""));
        assertFalse(props.isShortFollowUp("   "));
    }

    @Test
    void aZeroLimitTurnsTheRuleOffEntirely() {
        props.setShortMessageChars(0);
        assertFalse(props.isShortFollowUp("yes"));
        // The setter clamps negative values to the same off state.
        props.setShortMessageChars(-3);
        assertEquals(0, props.getShortMessageChars());
        assertFalse(props.isShortFollowUp("yes"));
    }

    @Test
    void theCharacterLimitCountsCodePointsNotUtf16Units() {
        // One emoji is one code point but two UTF-16 chars; the rule must not count it twice.
        assertTrue(props.isShortFollowUp("👍".repeat(40)));
        assertFalse(props.isShortFollowUp("👍".repeat(41)));
    }

    @Test
    void remainingSettersRoundTripAndClamp() {
        props.setStickyToolsEnabled(false);
        props.setStickyToolsWindow(Duration.ofMinutes(30));
        props.setMaxStickyTools(3);
        props.setKeepPrimaryModel(false);

        assertFalse(props.isStickyToolsEnabled());
        assertEquals(Duration.ofMinutes(30), props.getStickyToolsWindow());
        assertEquals(3, props.getMaxStickyTools());
        assertFalse(props.isKeepPrimaryModel());

        props.setMaxStickyTools(-1);
        assertEquals(0, props.getMaxStickyTools());
    }
}
