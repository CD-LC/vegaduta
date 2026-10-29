package com.agenticai.core.eventbus.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link WebhookDelivery}'s lifecycle: a row starts PENDING, each {@code recordFailure} bumps the
 * attempt counter and either schedules the next try or - once the caller's cap is reached - goes
 * DEAD so the relay never touches it again. {@code markDelivered} records the 2xx evidence.
 */
class WebhookDeliveryTest {

    private static WebhookDelivery pending() {
        return new WebhookDelivery(
                UUID.randomUUID(), "workflow.run.completed", "run-9", "{\"k\":1}");
    }

    @Test
    void aNewDeliveryIsPendingAndAttemptableImmediately() {
        Instant before = Instant.now();

        WebhookDelivery delivery = pending();

        assertEquals(WebhookDelivery.STATUS_PENDING, delivery.getStatus());
        assertEquals(0, delivery.getAttemptCount());
        assertNotNull(delivery.getNextAttemptAt());
        assertTrue(!delivery.getNextAttemptAt().isBefore(before));
        assertEquals(delivery.getNextAttemptAt(), delivery.getCreatedAt());
        assertNull(delivery.getResponseCode());
        assertNull(delivery.getDeliveredAt());
        assertNull(delivery.getDurationMs());
        assertEquals("workflow.run.completed", delivery.getEventType());
        assertEquals("run-9", delivery.getRunId());
        assertEquals("{\"k\":1}", delivery.getPayloadJson());
    }

    @Test
    void markDeliveredRecordsTheResponseEvidence() {
        WebhookDelivery delivery = pending();

        delivery.markDelivered(204, 137L);

        assertEquals(WebhookDelivery.STATUS_DELIVERED, delivery.getStatus());
        assertEquals(204, delivery.getResponseCode());
        assertEquals(137, delivery.getDurationMs());
        assertNotNull(delivery.getDeliveredAt());
    }

    @Test
    void aFailureBelowTheCapKeepsItPendingAndSchedulesTheRetry() {
        WebhookDelivery delivery = pending();
        Instant backoff = Instant.now().plusSeconds(30);

        delivery.recordFailure(500, "HTTP 500", backoff, 3);

        assertEquals(1, delivery.getAttemptCount());
        assertEquals(WebhookDelivery.STATUS_PENDING, delivery.getStatus());
        assertEquals(backoff, delivery.getNextAttemptAt());
        assertEquals(500, delivery.getResponseCode());
        assertEquals("HTTP 500", delivery.getLastError());
    }

    @Test
    void reachingTheAttemptCapMarksTheRowDead() {
        WebhookDelivery delivery = pending();
        Instant next = Instant.now().plusSeconds(30);

        delivery.recordFailure(503, "first", next, 2);
        delivery.recordFailure(503, "second", next.plusSeconds(60), 2);

        assertEquals(2, delivery.getAttemptCount());
        assertEquals(WebhookDelivery.STATUS_DEAD, delivery.getStatus());
        // A DEAD row keeps the STALE nextAttemptAt - the relay selects on status first, so this is
        // never read again; it must not be "rescheduled" by accident.
        assertEquals("second", delivery.getLastError());
    }

    @Test
    void anOverlongErrorMessageIsTruncatedToTheColumnBudget() {
        WebhookDelivery delivery = pending();

        delivery.recordFailure(null, "x".repeat(5_000), Instant.now(), 5);

        assertEquals(2_000, delivery.getLastError().length());
        assertNull(delivery.getResponseCode());
    }

    @Test
    void aNullErrorMessageStaysNull() {
        WebhookDelivery delivery = pending();

        delivery.recordFailure(null, null, Instant.now(), 5);

        assertNull(delivery.getLastError());
        assertEquals(1, delivery.getAttemptCount());
    }
}
