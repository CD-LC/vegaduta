package com.agenticai.core.rl.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link TurnFeedbackRecordedEvent} carries the bare facts of a stored thumbs/rating/comment to
 * whatever listener consumes them (the preferences-from-behavior loop) - on the request thread,
 * with the source object intact for {@code ApplicationEvent} listeners that filter on it.
 */
class TurnFeedbackRecordedEventTest {

    @Test
    void theEventCarriesEveryFactItWasPublishedWith() {
        Object source = new Object();
        UUID agentId = UUID.randomUUID();

        TurnFeedbackRecordedEvent event =
                new TurnFeedbackRecordedEvent(
                        source, "alice@example.com", agentId, "sess-9", "person's own words");

        assertSame(source, event.getSource());
        assertEquals("alice@example.com", event.getSubject());
        assertEquals(agentId, event.getAgentId());
        assertEquals("sess-9", event.getSessionId());
        assertEquals("person's own words", event.getComment());
    }
}
