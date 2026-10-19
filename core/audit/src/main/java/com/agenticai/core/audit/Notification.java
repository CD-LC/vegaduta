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
 * Notification bell - a tenant-facing, human-readable signal ("your workflow
 * failed", "a guardrail blocked a tool call", "your plan's usage is close to
 * its cap"), as opposed to {@link PlatformIssue} which is the admin-facing
 * deduplicated-by-fingerprint failure record. The two are related, not the
 * same thing: a single {@link PlatformIssue} row that keeps recurring
 * (bumping {@code occurrenceCount}) can still be worth telling a human about
 * exactly once via a {@code Notification}, which is what {@link
 * #relatedIssueId} ties back to.
 *
 * <p>{@link #recipientUserId} null means broadcast: every user with {@code
 * ROLE_tenant-admin} in this tenant's schema sees it (same "who counts as an
 * admin for this tenant" rule {@code ApprovalController#isTenantAdmin} and
 * {@code SecurityConfig}'s {@code /api/admin/**} matcher already use).
 * Non-null targets one specific end user by their Keycloak subject - same
 * VARCHAR(255)-string-not-FK convention as {@link AuditLogEntry#getActorId()}
 * and {@link PlatformIssue#getAcknowledgedBy()}.
 *
 * <p>No {@code tenantId} column: like {@link PlatformIssue}, rows live in the
 * tenant's own Postgres schema, so isolation comes from the schema itself,
 * not a discriminator column.
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "recipient_user_id", length = 255)
    private String recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationCategory category;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationSeverity severity;

    // Column named is_read, not read, to sidestep the SQL-reserved-adjacent bareword.
    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Deep link, e.g. "/issues/{issueId}" or "/workflows/{id}/runs/{runId}".
    @Column(length = 512)
    private String link;

    // Ties this notification back to the platform_issue row DiagnosticService may have also
    // created - no formal FK constraint, matching platform_issue's own no-FK-targets convention.
    @Column(name = "related_issue_id")
    private UUID relatedIssueId;

    protected Notification() {
        // JPA
    }

    public Notification(
        String recipientUserId, NotificationCategory category, String title, String body,
        NotificationSeverity severity, String link, UUID relatedIssueId
    ) {
        this.recipientUserId = recipientUserId;
        this.category = category;
        this.title = title;
        this.body = body;
        this.severity = severity;
        this.read = false;
        this.createdAt = Instant.now();
        this.link = link;
        this.relatedIssueId = relatedIssueId;
    }

    public void markRead() {
        this.read = true;
    }

    public UUID getId() { return id; }
    public String getRecipientUserId() { return recipientUserId; }
    public NotificationCategory getCategory() { return category; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public NotificationSeverity getSeverity() { return severity; }
    public boolean isRead() { return read; }
    public Instant getCreatedAt() { return createdAt; }
    public String getLink() { return link; }
    public UUID getRelatedIssueId() { return relatedIssueId; }
}
