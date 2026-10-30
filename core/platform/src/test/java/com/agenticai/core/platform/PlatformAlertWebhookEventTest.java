package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.platform.PlatformAlertWebhookEvent.AlertStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Alertmanager re-delivers a firing alert on every {@code repeat_interval}; the row must bump its
 * occurrence count and move {@code lastSeenAt} rather than let one incident pile up thousands of
 * near-duplicate rows.
 */
class PlatformAlertWebhookEventTest {

    private static final Instant START = Instant.parse("2026-07-01T10:00:00Z");
    private static final Instant END = Instant.parse("2026-07-01T10:30:00Z");

    private static PlatformAlertWebhookEvent firing() {
        return new PlatformAlertWebhookEvent(
                "fp-abc",
                "RedisDown",
                "critical",
                AlertStatus.FIRING,
                "redis is unreachable",
                "the primary cache stopped answering",
                "{\"severity\":\"critical\"}",
                "{\"runbook\":\"x\"}",
                "http://prom:9090/graph",
                START,
                null);
    }

    @Test
    void aNewEventStartsAsASingleSighting() {
        PlatformAlertWebhookEvent event = firing();

        assertEquals("fp-abc", event.getFingerprint());
        assertEquals("RedisDown", event.getAlertName());
        assertEquals("critical", event.getSeverity());
        assertEquals(AlertStatus.FIRING, event.getStatus());
        assertEquals("redis is unreachable", event.getSummary());
        assertEquals("the primary cache stopped answering", event.getDescription());
        assertEquals("{\"severity\":\"critical\"}", event.getLabelsJson());
        assertEquals("{\"runbook\":\"x\"}", event.getAnnotationsJson());
        assertEquals("http://prom:9090/graph", event.getGeneratorUrl());
        assertEquals(START, event.getStartsAt());
        assertNull(event.getEndsAt(), "a still-firing alert has no end");
        assertEquals(1, event.getOccurrenceCount());
        assertEquals(event.getFirstSeenAt(), event.getLastSeenAt());
    }

    @Test
    void redeliveryBumpsTheCountAndRefreshesTheLatestState() throws Exception {
        PlatformAlertWebhookEvent event = firing();
        Instant firstSeen = event.getFirstSeenAt();

        event.recordRedelivery(AlertStatus.FIRING, "still down", "desc2", "{}", "{}", null);

        assertEquals(2, event.getOccurrenceCount());
        assertEquals(AlertStatus.FIRING, event.getStatus());
        assertEquals("still down", event.getSummary());
        assertEquals(firstSeen, event.getFirstSeenAt(), "first sighting never moves");
        assertTrue(!event.getLastSeenAt().isBefore(firstSeen));

        event.recordRedelivery(AlertStatus.RESOLVED, "recovered", "desc3", "{}", "{}", END);

        assertEquals(3, event.getOccurrenceCount());
        assertEquals(AlertStatus.RESOLVED, event.getStatus());
        assertEquals(END, event.getEndsAt(), "the resolved delivery stamps the end");
    }
}
