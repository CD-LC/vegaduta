package com.agenticai.core.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Alerting infrastructure pass (2026-07) - a durable row per distinct
 * Prometheus Alertmanager alert received on {@code
 * PlatformAlertWebhookController}'s {@code POST /webhook/alertmanager}
 * receiver, so a fired alert is visible/queryable even if nobody was staring
 * at Grafana/Alertmanager's own UI when it happened, and so this "does
 * alerting actually reach anyone" pass has a real, checkable artifact instead
 * of just "trust the YAML".
 *
 * <p>Deliberately its OWN {@code control_plane}-schema table, NOT a reused
 * {@code core.audit.PlatformIssue} row despite the surface-level similarity
 * (both are "a failure got recorded somewhere an admin can see it"). {@code
 * PlatformIssue} is schema-PER-TENANT (see its own javadoc/{@code
 * PlatformIssueService}) - every tenant has its own independent table, and
 * the platform-wide "Ecosystem Maintenance" view only ever sees it by
 * sweeping every active tenant's schema in a loop ({@code
 * PlatformObservabilityService#listIssues}). An Alertmanager alert (Redis
 * down, HikariCP pool exhausted, JVM heap pressure) is not about any one
 * tenant - it is a statement about the shared platform infrastructure every
 * tenant's schema sits on top of. Writing it into one arbitrarily-chosen
 * tenant's {@code PlatformIssue} table would misattribute a platform-wide
 * problem to a random tenant AND leak an internal-ops signal into that
 * tenant's own admin-facing Issues portal ({@code GET /api/admin/issues}),
 * which is the wrong audience entirely. This table instead follows the
 * convention this codebase already uses for genuinely platform-wide,
 * not-tenant-scoped data - see {@link PlatformAlertSettings}/{@code
 * com.agenticai.core.tenancy.PlatformSetting}/{@code
 * com.agenticai.core.tenancy.PlatformKillSwitch}, all {@code
 * @Table(schema = "control_plane")} entities living outside the
 * schema-per-tenant routing entirely.
 *
 * <p>Keyed on {@link #fingerprint} - Alertmanager computes and sends a stable
 * fingerprint per distinct alert (same label set), included on every webhook
 * delivery for that alert's whole firing/resolved lifecycle. {@link
 * PlatformAlertWebhookService#record} upserts on it, the same "recurring
 * problem bumps one row instead of piling up a new one per re-delivery"
 * posture {@code PlatformIssueService#record} already uses for tenant-side
 * issues - Alertmanager re-sends a firing alert on every {@code
 * repeat_interval}, and a naive insert-every-time would fill this table with
 * thousands of near-duplicate rows for one ongoing incident.
 */
@Entity
@Table(name = "platform_alert_webhook_event", schema = "control_plane")
public class PlatformAlertWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Alertmanager's own stable per-alert fingerprint - see class javadoc. Unique per distinct alert (label set). */
    @Column(nullable = false, length = 64, unique = true)
    private String fingerprint;

    @Column(length = 255)
    private String alertName;

    /** From the alert's {@code severity} label - "critical"/"warning", whatever the firing rule set (see vegaduta-alerts.yml). Free text, not enum-constrained: a rule file can introduce a new label value without a code change here. */
    @Column(length = 32)
    private String severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AlertStatus status;

    @Column(length = 500)
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Raw JSON of the alert's full {@code labels} map - kept verbatim rather than modeled, since the rule set (and its label shape) evolves independently of this receiver. */
    @Column(name = "labels_json", columnDefinition = "TEXT")
    private String labelsJson;

    /** Raw JSON of the alert's full {@code annotations} map - same rationale as {@link #labelsJson}. */
    @Column(name = "annotations_json", columnDefinition = "TEXT")
    private String annotationsJson;

    @Column(name = "generator_url", length = 1000)
    private String generatorUrl;

    @Column(name = "starts_at")
    private Instant startsAt;

    /** Null while still firing; set the moment a resolved delivery for this fingerprint arrives. */
    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected PlatformAlertWebhookEvent() {
        // JPA
    }

    public PlatformAlertWebhookEvent(
        String fingerprint, String alertName, String severity, AlertStatus status, String summary, String description,
        String labelsJson, String annotationsJson, String generatorUrl, Instant startsAt, Instant endsAt
    ) {
        this.fingerprint = fingerprint;
        this.alertName = alertName;
        this.severity = severity;
        this.status = status;
        this.summary = summary;
        this.description = description;
        this.labelsJson = labelsJson;
        this.annotationsJson = annotationsJson;
        this.generatorUrl = generatorUrl;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.occurrenceCount = 1;
        Instant now = Instant.now();
        this.firstSeenAt = now;
        this.lastSeenAt = now;
    }

    /** Re-delivery of an already-seen fingerprint (Alertmanager's {@code repeat_interval}, or a firing->resolved transition) - bump instead of insert. */
    public void recordRedelivery(
        AlertStatus status, String summary, String description, String labelsJson, String annotationsJson, Instant endsAt
    ) {
        this.status = status;
        this.summary = summary;
        this.description = description;
        this.labelsJson = labelsJson;
        this.annotationsJson = annotationsJson;
        this.endsAt = endsAt;
        this.occurrenceCount++;
        this.lastSeenAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getFingerprint() { return fingerprint; }
    public String getAlertName() { return alertName; }
    public String getSeverity() { return severity; }
    public AlertStatus getStatus() { return status; }
    public String getSummary() { return summary; }
    public String getDescription() { return description; }
    public String getLabelsJson() { return labelsJson; }
    public String getAnnotationsJson() { return annotationsJson; }
    public String getGeneratorUrl() { return generatorUrl; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public int getOccurrenceCount() { return occurrenceCount; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }

    public enum AlertStatus {
        FIRING,
        RESOLVED
    }
}
