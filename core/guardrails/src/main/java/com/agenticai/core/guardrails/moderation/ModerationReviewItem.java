package com.agenticai.core.guardrails.moderation;

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
 * Phase P6. One piece of content held for a human trust-&-safety decision -
 * created by {@link ModerationService} when a pack's signal lands in the
 * review band (between its review and block thresholds), and resolved by a
 * reviewer through {@link ModerationReviewService}.
 *
 * <p><b>Tenant-schema entity</b> (no {@code schema=} on {@code @Table}, like
 * {@code ApprovalRequest} and every other per-tenant table): it lives in the
 * calling tenant's own Postgres schema, so schema-per-tenant isolation keeps
 * one tenant's flagged content out of another's - the review queue inherits
 * the platform's strongest existing isolation boundary for free.
 *
 * <p>Persisted via migration V121 ({@code moderation_review_item}). The
 * pack/layer are stored as their stable string tokens (not the raw enum
 * ordinal) so renaming a Java constant never reinterprets history.
 */
@Entity
@Table(name = "moderation_review_item")
public class ModerationReviewItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Redundant with the schema for isolation, but kept as an explicit column
    // for cross-schema operator tooling and audit correlation.
    @Column(name = "tenant_id", length = 255)
    private String tenantId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "session_id", length = 255)
    private String sessionId;

    /** The flagged content (or a bounded excerpt of it). */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** {@link ModerationPolicyPack#configKey()} that fired. */
    @Column(name = "policy_pack", nullable = false, length = 64)
    private String policyPack;

    /** {@link ModerationLayer} that produced the signal. */
    @Column(name = "deciding_layer", nullable = false, length = 32)
    private String decidingLayer;

    @Column(name = "confidence", nullable = false)
    private int confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ModerationReviewStatus status;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** Free-form label for where in the flow the content sat (user_input, agent_output). */
    @Column(name = "direction", length = 64)
    private String direction;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reviewed_by", length = 255)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    protected ModerationReviewItem() {
        // JPA
    }

    public ModerationReviewItem(
        String tenantId,
        UUID agentId,
        String sessionId,
        String content,
        ModerationPolicyPack policyPack,
        ModerationLayer decidingLayer,
        int confidence,
        String reason,
        String direction
    ) {
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.content = content;
        this.policyPack = policyPack.configKey();
        this.decidingLayer = decidingLayer.name();
        this.confidence = confidence;
        this.reason = reason;
        this.direction = direction;
        this.status = ModerationReviewStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /**
     * Applies a reviewer's terminal decision. Guards against re-deciding an
     * already-resolved item - the service layer surfaces that as a conflict
     * rather than silently overwriting one reviewer's call with another's.
     */
    public void resolve(ModerationReviewStatus decision, String reviewedBy, String notes) {
        this.status = decision;
        this.reviewedBy = reviewedBy;
        this.reviewNotes = notes;
        this.reviewedAt = Instant.now();
    }

    public boolean isPending() {
        return status == ModerationReviewStatus.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getContent() {
        return content;
    }

    public String getPolicyPack() {
        return policyPack;
    }

    public String getDecidingLayer() {
        return decidingLayer;
    }

    public int getConfidence() {
        return confidence;
    }

    public ModerationReviewStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getDirection() {
        return direction;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewNotes() {
        return reviewNotes;
    }
}
