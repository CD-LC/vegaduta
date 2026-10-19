package com.agenticai.core.devices.sentinel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One synced sentinel event - see {@code tenant/V368__mobile_sentinels.sql}.
 * {@code (userId, clientEventId)} is unique: the device's id is the
 * idempotency key. {@code findings}/{@code readings} are JSON text;
 * {@code readings} is null unless the policy allowed raw readings.
 */
@Entity
@Table(name = "mobile_sentinel_event")
public class MobileSentinelEvent {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(name = "client_event_id", nullable = false, length = 64)
    private String clientEventId;

    @Column(name = "sentinel_id", length = 64)
    private String sentinelId;

    @Column(name = "sentinel_name", length = 160)
    private String sentinelName;

    @Column(name = "template", length = 64)
    private String template;

    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /** Comma-separated {@link SentinelKind} names. */
    @Column(name = "kinds", columnDefinition = "TEXT")
    private String kinds;

    @Column(name = "findings", columnDefinition = "TEXT")
    private String findings;

    @Column(name = "readings", columnDefinition = "TEXT")
    private String readings;

    @Column(name = "device_label", length = 120)
    private String deviceLabel;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "escalated_to", length = 255)
    private String escalatedTo;

    protected MobileSentinelEvent() {
    }

    public MobileSentinelEvent(
        String userId, String clientEventId, String sentinelId, String sentinelName, String template,
        String severity, String title, String summary, String kinds, String findings, String readings,
        String deviceLabel, Instant occurredAt
    ) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.clientEventId = clientEventId;
        this.sentinelId = sentinelId;
        this.sentinelName = sentinelName;
        this.template = template;
        this.severity = severity;
        this.title = title;
        this.summary = summary;
        this.kinds = kinds;
        this.findings = findings;
        this.readings = readings;
        this.deviceLabel = deviceLabel;
        this.occurredAt = occurredAt;
        this.receivedAt = Instant.now();
    }

    public void markEscalated(Instant at, String to) {
        this.escalatedAt = at;
        this.escalatedTo = to;
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getClientEventId() {
        return clientEventId;
    }

    public String getSentinelId() {
        return sentinelId;
    }

    public String getSentinelName() {
        return sentinelName;
    }

    public String getTemplate() {
        return template;
    }

    public String getSeverity() {
        return severity;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getKinds() {
        return kinds;
    }

    public String getFindings() {
        return findings;
    }

    public String getReadings() {
        return readings;
    }

    public String getDeviceLabel() {
        return deviceLabel;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public String getEscalatedTo() {
        return escalatedTo;
    }
}
