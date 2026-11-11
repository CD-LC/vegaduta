package com.agenticai.core.devices.sentinel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The synced sentinel event row: a random id assigned at construction (the idempotency key is
 * {@code (userId, clientEventId)} instead), {@code receivedAt} stamped by the server, and {@link
 * MobileSentinelEvent#markEscalated} recording who was paged and when.
 */
class MobileSentinelEventTest {

    private static MobileSentinelEvent event() {
        return new MobileSentinelEvent(
                "alice",
                "client-evt-1",
                "hg",
                "Heart Guardian",
                "HEART_GUARDIAN",
                "ALERT",
                "Resting heart rate high",
                "Your resting HR is above baseline.",
                "HEART_RATE",
                "[{\"kind\":\"RESTING_HEART_RATE\",\"value\":88.0}]",
                null,
                "Pixel 9",
                Instant.now().minusSeconds(300));
    }

    @Test
    void aNewEventGetsItsOwnIdAndAServerSideReceivedTimestamp() {
        MobileSentinelEvent event = event();
        MobileSentinelEvent other = event();

        assertThat(event.getId()).isNotNull().isNotEqualTo(other.getId());
        assertThat(event.getReceivedAt()).isNotNull();
        assertThat(event.getUserId()).isEqualTo("alice");
        assertThat(event.getClientEventId()).isEqualTo("client-evt-1");
        assertThat(event.getSentinelId()).isEqualTo("hg");
        assertThat(event.getSentinelName()).isEqualTo("Heart Guardian");
        assertThat(event.getTemplate()).isEqualTo("HEART_GUARDIAN");
        assertThat(event.getSeverity()).isEqualTo("ALERT");
        assertThat(event.getTitle()).isEqualTo("Resting heart rate high");
        assertThat(event.getSummary()).isEqualTo("Your resting HR is above baseline.");
        assertThat(event.getKinds()).isEqualTo("HEART_RATE");
        assertThat(event.getFindings()).contains("RESTING_HEART_RATE");
        assertThat(event.getReadings()).isNull();
        assertThat(event.getDeviceLabel()).isEqualTo("Pixel 9");
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getEscalatedAt()).isNull();
        assertThat(event.getEscalatedTo()).isNull();
    }

    @Test
    void markEscalatedRecordsTheTargetAndInstant() {
        MobileSentinelEvent event = event();
        Instant at = Instant.now();

        event.markEscalated(at, "asha@example.com");

        assertThat(event.getEscalatedAt()).isEqualTo(at);
        assertThat(event.getEscalatedTo()).isEqualTo("asha@example.com");
    }
}
