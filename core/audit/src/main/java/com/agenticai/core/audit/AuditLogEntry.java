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
 * One record of a state-changing action - who (or what) did it, what kind of
 * action, what it acted on, and what happened. This is an append-only log:
 * nothing in this package ever updates or deletes a row, only inserts -
 * that's the actual compliance property a real audit trail needs.
 *
 * <p>{@code actorId} is nullable for now: Phase 0 Stage 3 (OAuth2 Resource
 * Server, not yet built) is what will populate it from an authenticated
 * user's token. Until then every entry is recorded with a null actor rather
 * than a fake placeholder value - an honest "unknown" beats a misleading
 * "system".
 */
@Entity
@Table(name = "audit_log_entry")
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "actor_id", length = 255)
    private String actorId;

    @Column(name = "action_type", nullable = false, length = 128)
    private String actionType;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id", length = 128)
    private String targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuditOutcome outcome;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLogEntry() {
        // JPA
    }

    public AuditLogEntry(
        String actorId,
        String actionType,
        String targetType,
        String targetId,
        AuditOutcome outcome,
        String detail
    ) {
        this.actorId = actorId;
        this.actionType = actionType;
        this.targetType = targetType;
        this.targetId = targetId;
        this.outcome = outcome;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getActorId() {
        return actorId;
    }

    public String getActionType() {
        return actionType;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
