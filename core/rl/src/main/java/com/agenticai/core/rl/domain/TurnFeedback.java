package com.agenticai.core.rl.domain;

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
 * The human reward signal on one completed agent turn - a thumbs up/down, a
 * 1-5 rating, a free-text comment, and/or a corrected answer. Written by
 * {@code FeedbackService}, which also converts it into a HUMAN-source
 * {@link RewardRecord} on the matching {@link Episode} (matched by
 * agent_id + session_id + turn_ref) when one exists.
 *
 * <p>Schema-per-tenant: no tenant_id column - this row only ever lives inside
 * one tenant's own schema (see {@code V108__turn_feedback.sql}).
 */
@Entity
@Table(name = "turn_feedback")
public class TurnFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "turn_ref", nullable = false, length = 128)
    private String turnRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal", nullable = false, length = 16)
    private FeedbackSignal signal;

    /** Only meaningful for {@link FeedbackSignal#RATING} (1..5); null otherwise. */
    @Column(name = "rating")
    private Short rating;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "edited_answer", columnDefinition = "TEXT")
    private String editedAnswer;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TurnFeedback() {
        // JPA
    }

    public TurnFeedback(
        UUID agentId,
        String sessionId,
        String turnRef,
        FeedbackSignal signal,
        Short rating,
        String comment,
        String editedAnswer,
        String createdBy,
        Instant createdAt
    ) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.turnRef = turnRef;
        this.signal = signal;
        this.rating = rating;
        this.comment = comment;
        this.editedAnswer = editedAnswer;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTurnRef() {
        return turnRef;
    }

    public FeedbackSignal getSignal() {
        return signal;
    }

    public Short getRating() {
        return rating;
    }

    public String getComment() {
        return comment;
    }

    public String getEditedAnswer() {
        return editedAnswer;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
