package com.agenticai.core.eventbus.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One attempted POST of a platform event to one subscription (migration
 * V241) - the retryable unit {@link WebhookDeliveryRelay} works through.
 * Statuses: {@code PENDING} (waits for {@code nextAttemptAt}), {@code
 * DELIVERED} (endpoint returned 2xx), {@code DEAD} (hit the attempt cap -
 * kept for the deliveries view, never retried again).
 */
@Entity
@Table(name = "webhook_delivery")
public class WebhookDelivery {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_DEAD = "DEAD";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @Column(name = "run_id", length = 100)
    private String runId;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "duration_ms")
    private Integer durationMs;

    protected WebhookDelivery() {
        // JPA
    }

    public WebhookDelivery(UUID subscriptionId, String eventType, String runId, String payloadJson) {
        this.subscriptionId = subscriptionId;
        this.eventType = eventType;
        this.runId = runId;
        this.payloadJson = payloadJson;
        this.status = STATUS_PENDING;
        this.nextAttemptAt = Instant.now();
        this.createdAt = this.nextAttemptAt;
    }

    public void markDelivered(int responseCode, long durationMs) {
        this.status = STATUS_DELIVERED;
        this.responseCode = responseCode;
        this.deliveredAt = Instant.now();
        this.durationMs = (int) durationMs;
    }

    /**
     * Records a failed attempt: bumps the counter, stores the response/error
     * evidence, and schedules the next try on an exponential backoff
     * (base 30s doubling, capped at 30 minutes) - or marks the row DEAD once
     * the attempt cap is reached.
     */
    public void recordFailure(Integer responseCode, String errorMessage, Instant nextAttemptAt, int maxAttempts) {
        this.attemptCount++;
        this.responseCode = responseCode;
        this.lastError = errorMessage != null && errorMessage.length() > 2000
            ? errorMessage.substring(0, 2000) : errorMessage;
        if (this.attemptCount >= maxAttempts) {
            this.status = STATUS_DEAD;
        } else {
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    public UUID getId() { return id; }
    public UUID getSubscriptionId() { return subscriptionId; }
    public String getEventType() { return eventType; }
    public String getRunId() { return runId; }
    public String getPayloadJson() { return payloadJson; }
    public String getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Integer getResponseCode() { return responseCode; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Integer getDurationMs() { return durationMs; }
}
