package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ApprovalParkProperties} ships the model-facing "waiting on a human" texts from {@code
 * /guardrails/approval-park.properties} (queue limit 5, goal capture 4000 chars). {@link
 * ApprovalParkProperties#render} fills {@code {tool}}, {@code {requestId}} and {@code
 * {parkedTool}}; the numeric setters clamp to non-negative so a typo can't make the queue
 * unbounded or negative.
 */
class ApprovalParkPropertiesTest {

    @Test
    void classpathDefaultsAreLoaded() {
        ApprovalParkProperties props = new ApprovalParkProperties();
        assertEquals(5, props.getMaxQueuedCalls());
        assertEquals(4000, props.getMaxGoalChars());
        for (String text :
                new String[] {
                    props.getAwaitingMessage(),
                    props.getQueuedMessage(),
                    props.getQueueFullMessage(),
                    props.getNotQueuedMessage(),
                    props.getRecordFailedMessage(),
                }) {
            assertFalse(text == null || text.isBlank(), "every shipped text is non-empty");
        }
        assertTrue(props.getAwaitingMessage().contains("{tool}"));
        assertTrue(props.getAwaitingMessage().contains("{requestId}"));
        assertTrue(props.getQueuedMessage().contains("{parkedTool}"));
        assertFalse(props.getGoalCutMarker() == null || props.getGoalCutMarker().isBlank());
    }

    @Test
    void renderFillsEveryPlaceholder() {
        String rendered = ApprovalParkProperties.render(
            "call {tool} waits on {requestId} behind {parkedTool}", "send_email", "req-9", "deploy");
        assertEquals("call send_email waits on req-9 behind deploy", rendered);
    }

    @Test
    void renderLeavesTextWithoutPlaceholdersAlone() {
        assertEquals("no placeholders", ApprovalParkProperties.render("no placeholders", "t", "r", "p"));
    }

    @Test
    void renderHandlesNullTemplateAndNullValues() {
        assertEquals("", ApprovalParkProperties.render(null, "t", "r", "p"));
        assertEquals("x null y", ApprovalParkProperties.render("x {tool} y", null, "r", "p"));
    }

    @Test
    void limitsClampToNonNegative() {
        ApprovalParkProperties props = new ApprovalParkProperties();
        props.setMaxQueuedCalls(-3);
        props.setMaxGoalChars(-1);
        assertEquals(0, props.getMaxQueuedCalls());
        assertEquals(0, props.getMaxGoalChars());
        props.setMaxQueuedCalls(7);
        props.setMaxGoalChars(42);
        assertEquals(7, props.getMaxQueuedCalls());
        assertEquals(42, props.getMaxGoalChars());
    }

    @Test
    void textSettersRoundTrip() {
        ApprovalParkProperties props = new ApprovalParkProperties();
        props.setAwaitingMessage("a");
        props.setQueuedMessage("q");
        props.setQueueFullMessage("f");
        props.setNotQueuedMessage("n");
        props.setRecordFailedMessage("r");
        props.setGoalCutMarker("m");
        assertEquals("a", props.getAwaitingMessage());
        assertEquals("q", props.getQueuedMessage());
        assertEquals("f", props.getQueueFullMessage());
        assertEquals("n", props.getNotQueuedMessage());
        assertEquals("r", props.getRecordFailedMessage());
        assertEquals("m", props.getGoalCutMarker());
    }
}
