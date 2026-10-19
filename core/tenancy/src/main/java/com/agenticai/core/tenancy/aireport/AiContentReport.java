package com.agenticai.core.tenancy.aireport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One user report of offensive / harmful AI output (Google Play AI-Generated
 * Content policy - see the app-store compliance requirements (described inline)).
 *
 * <p>Control-plane table ({@code V369__ai_content_report.sql}) so the platform
 * admin sees reports from every tenant, mapped the same way as {@code
 * DevicePairingCode} / {@code PlatformSetting}: explicit {@code schema =
 * "control_plane"}, so the tenant search_path never matters. Enum-valued
 * columns are stored as their names in plain strings and validated in
 * {@link AiContentReportService}.
 */
@Entity
@Table(name = "ai_content_report", schema = "control_plane")
public class AiContentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** TenantContext tenant id (= tenant schema name); null for a tenant-less caller. */
    @Column(name = "tenant_id", length = 63)
    private String tenantId;

    /** Keycloak subject; nulled when that user deletes their account. */
    @Column(name = "reporter_id", length = 255)
    private String reporterId;

    @Column(nullable = false, length = 16)
    private String surface;

    @Column(nullable = false, length = 32)
    private String reason;

    @Column(length = 1000)
    private String comment;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "agent_id", length = 128)
    private String agentId;

    @Column(name = "session_id", length = 255)
    private String sessionId;

    @Column(name = "message_ref", length = 255)
    private String messageRef;

    @Column(name = "on_device", nullable = false)
    private boolean onDevice;

    @Column(name = "app_platform", length = 16)
    private String appPlatform;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "review_note", length = 2000)
    private String reviewNote;

    @Column(name = "reviewed_by", length = 255)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiContentReport() {
        // JPA
    }

    public AiContentReport(
        String tenantId,
        String reporterId,
        String surface,
        String reason,
        String comment,
        String content,
        String prompt,
        String agentId,
        String sessionId,
        String messageRef,
        boolean onDevice,
        String appPlatform,
        String appVersion
    ) {
        this.tenantId = tenantId;
        this.reporterId = reporterId;
        this.surface = surface;
        this.reason = reason;
        this.comment = comment;
        this.content = content;
        this.prompt = prompt;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.messageRef = messageRef;
        this.onDevice = onDevice;
        this.appPlatform = appPlatform;
        this.appVersion = appVersion;
        this.status = AiContentReportStatus.OPEN.name();
        this.createdAt = Instant.now();
    }

    public void review(String status, String reviewNote, String reviewedBy) {
        this.status = status;
        this.reviewNote = reviewNote;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getReporterId() { return reporterId; }
    public String getSurface() { return surface; }
    public String getReason() { return reason; }
    public String getComment() { return comment; }
    public String getContent() { return content; }
    public String getPrompt() { return prompt; }
    public String getAgentId() { return agentId; }
    public String getSessionId() { return sessionId; }
    public String getMessageRef() { return messageRef; }
    public boolean isOnDevice() { return onDevice; }
    public String getAppPlatform() { return appPlatform; }
    public String getAppVersion() { return appVersion; }
    public String getStatus() { return status; }
    public String getReviewNote() { return reviewNote; }
    public String getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
