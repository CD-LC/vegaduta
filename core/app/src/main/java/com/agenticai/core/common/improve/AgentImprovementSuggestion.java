package com.agenticai.core.common.improve;

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
 * An actionable fix the improvement loop proposes for one agent, distilled
 * from a recurring {@code PlatformIssue} cluster (the issues table's
 * fingerprint dedup + {@code occurrenceCount} IS the failure clustering -
 * this row is the "so fix it" half the audit found missing).
 *
 * <p>The suggestion is inert until a tenant admin accepts it: {@code
 * suggestedText} holds the full replacement artifact (a revised system
 * prompt for {@link ImprovementKind#PROMPT_REVISION}, a natural-language
 * procedure body for {@link ImprovementKind#NEW_PROCEDURE}) so the admin
 * reviews the exact bytes that would be applied before deciding.
 *
 * <p><b>Tenant-schema entity</b> (no {@code schema=} on {@code @Table},
 * same convention as {@code Notification}/{@code Procedure}): lives in the
 * calling tenant's own Postgres schema. {@code tenantId} is redundant with
 * the schema for isolation - kept for cross-schema operator tooling and
 * audit correlation.
 *
 * <p>Persisted via migration V274 ({@code agent_improvement_suggestion},
 * {@code db/migration/tenant}).
 */
@Entity
@Table(name = "agent_improvement_suggestion")
public class AgentImprovementSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", length = 255)
    private String tenantId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** The PlatformIssue cluster this suggestion was distilled from; null for ad-hoc suggestions. */
    @Column(name = "issue_id")
    private UUID issueId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private ImprovementKind kind;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "suggested_text", nullable = false, columnDefinition = "TEXT")
    private String suggestedText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ImprovementStatus status;

    /** What accept() actually did - e.g. "procedure:&lt;id&gt;" or "systemPrompt". */
    @Column(name = "applied_ref", length = 255)
    private String appliedRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by", length = 255)
    private String decidedBy;

    protected AgentImprovementSuggestion() {
        // JPA
    }

    public AgentImprovementSuggestion(
        String tenantId, UUID agentId, UUID issueId, ImprovementKind kind,
        String title, String rationale, String suggestedText
    ) {
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.issueId = issueId;
        this.kind = kind;
        this.title = title;
        this.rationale = rationale;
        this.suggestedText = suggestedText;
        this.status = ImprovementStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void markAccepted(String actorId, String appliedRef) {
        this.status = ImprovementStatus.ACCEPTED;
        this.decidedBy = actorId;
        this.decidedAt = Instant.now();
        this.appliedRef = appliedRef;
    }

    public void markDismissed(String actorId) {
        this.status = ImprovementStatus.DISMISSED;
        this.decidedBy = actorId;
        this.decidedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public UUID getAgentId() { return agentId; }
    public UUID getIssueId() { return issueId; }
    public ImprovementKind getKind() { return kind; }
    public String getTitle() { return title; }
    public String getRationale() { return rationale; }
    public String getSuggestedText() { return suggestedText; }
    public ImprovementStatus getStatus() { return status; }
    public String getAppliedRef() { return appliedRef; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecidedBy() { return decidedBy; }
}
