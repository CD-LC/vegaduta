package com.agenticai.core.audit;

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
 * Admin Issues portal (#425/#426) - a deduplicated failure record. Rows are
 * unique on {@link #fingerprint} (see {@link PlatformIssueService}): a
 * repeating failure bumps {@link #occurrenceCount}/{@link #lastSeenAt} on
 * the same row instead of creating a new one every time, so the portal
 * shows distinct problems rather than a raw error firehose.
 */
@Entity
@Table(name = "platform_issue")
public class PlatformIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 128, unique = true)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IssueSource source;

    @Column(name = "source_id", length = 128)
    private String sourceId;

    // #974 - see IssueCategory's javadoc: non-null for TENANT_REPORTED rows (tenant-chosen at
    // submission time) and for system-detected rows AiDiagnosticAnalysisService has analyzed
    // (attachDiagnosis, below) - an un-analyzed system row still leaves this null.
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private IssueCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IssueSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IssueStatus status;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "acknowledged_by", length = 255)
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected PlatformIssue() { }

    public PlatformIssue(String fingerprint, IssueSource source, String sourceId, IssueSeverity severity, String title, String message) {
        this(fingerprint, source, sourceId, null, severity, title, message);
    }

    /** #974 - the {@link IssueCategory}-carrying constructor, used only by {@code PlatformIssueService#createTicket}. */
    public PlatformIssue(
        String fingerprint, IssueSource source, String sourceId, IssueCategory category,
        IssueSeverity severity, String title, String message
    ) {
        this.fingerprint = fingerprint;
        this.source = source;
        this.sourceId = sourceId;
        this.category = category;
        this.severity = severity;
        this.status = IssueStatus.OPEN;
        this.title = title;
        this.message = message;
        this.occurrenceCount = 1;
        Instant now = Instant.now();
        this.firstSeenAt = now;
        this.lastSeenAt = now;
    }

    /** {@code AiDiagnosticAnalysisService}'s (core/app) async enrichment - never overwrites a category a human
     * already set (defensive; system rows never have one yet in practice), and appends rather than
     * replaces the message so the original raw error text a human might need for triage is never lost. */
    public void attachDiagnosis(IssueCategory category, String diagnosis) {
        if (this.category == null) {
            this.category = category;
        }
        if (diagnosis != null && !diagnosis.isBlank()) {
            this.message = (this.message == null ? "" : this.message) + "\n\n--- AI diagnosis ---\n" + diagnosis;
        }
    }

    /** Bumps the row for a repeat occurrence of the same fingerprint - also reopens a RESOLVED issue that recurred. */
    public void recordRecurrence(String message) {
        this.occurrenceCount++;
        this.lastSeenAt = Instant.now();
        this.message = message;
        if (this.status == IssueStatus.RESOLVED) {
            this.status = IssueStatus.OPEN;
            this.resolvedAt = null;
        }
    }

    public void acknowledge(String actorId) {
        this.status = IssueStatus.ACKNOWLEDGED;
        this.acknowledgedBy = actorId;
        this.acknowledgedAt = Instant.now();
    }

    public void resolve() {
        this.status = IssueStatus.RESOLVED;
        this.resolvedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getFingerprint() { return fingerprint; }
    public IssueSource getSource() { return source; }
    public String getSourceId() { return sourceId; }
    public IssueCategory getCategory() { return category; }
    public IssueSeverity getSeverity() { return severity; }
    public IssueStatus getStatus() { return status; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public int getOccurrenceCount() { return occurrenceCount; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public String getAcknowledgedBy() { return acknowledgedBy; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
