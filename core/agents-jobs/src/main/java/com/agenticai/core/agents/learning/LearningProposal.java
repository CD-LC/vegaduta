package com.agenticai.core.agents.learning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One thing the assistant proposes to learn (migration V612). Nothing here takes effect until approved. */
@Entity
@Table(name = "learning_proposals")
public class LearningProposal {

    @Id private UUID id;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "agent_id")
    private UUID agentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private LearningProposalKind kind;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "body_hash", nullable = false, length = 64)
    private String bodyHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false, columnDefinition = "jsonb")
    private String evidence = "{}";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private LearningProposalStatus status = LearningProposalStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected LearningProposal() {}

    LearningProposal(
            UUID id,
            String subject,
            UUID agentId,
            LearningProposalKind kind,
            String title,
            String body,
            String bodyHash,
            String payload,
            String evidence,
            Instant createdAt) {
        this.id = id;
        this.subject = subject;
        this.agentId = agentId;
        this.kind = kind;
        this.title = title;
        this.body = body;
        this.bodyHash = bodyHash;
        this.payload = payload;
        this.evidence = evidence;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public LearningProposalKind getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getBodyHash() {
        return bodyHash;
    }

    /** JSON object text; kind specific data (see {@link LearningProposalKind}). */
    public String getPayload() {
        return payload;
    }

    /** JSON object text: session id, short quote, signal. */
    public String getEvidence() {
        return evidence;
    }

    public LearningProposalStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    void decide(LearningProposalStatus newStatus, String finalBody, Instant at) {
        this.status = newStatus;
        this.decidedAt = at;
        if (finalBody != null) {
            this.body = finalBody;
        }
    }
}
