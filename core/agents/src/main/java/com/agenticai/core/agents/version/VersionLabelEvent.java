package com.agenticai.core.agents.version;

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
 * One entry in the append-only history of label moves (migration V342,
 * {@code version_label_event}) - "who moved {@code PRODUCTION} to v7, when,
 * and whether it was a promote or a rollback", for BOTH agents and
 * workflows.
 *
 * <p>The pointers themselves live on the version rows ({@code
 * agent_version.label}, {@code workflow_version.label}) - this table is the
 * audit trail behind them, which is what lets {@code rollback(entityId)}
 * reconstruct "the previous production version" and what C6 insights reads
 * for promote timestamps. A label move that writes no event row is a label
 * move nobody can explain; the services write the two in the same
 * transaction.
 *
 * <p>{@code versionId} is deliberately not a foreign key: the discriminator
 * column means it points at one of two tables, and an audit row must
 * survive anything that ever happens to the version it names. Immutable by
 * Java shape, same convention as {@link AgentVersion} - no mutators, one
 * static factory, written never updated.
 */
@Entity
@Table(name = "version_label_event")
public class VersionLabelEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 16)
    private VersionEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    /** Stored as a plain VARCHAR so agent and workflow label enums need not unify. */
    @Column(name = "label", nullable = false, length = 32)
    private String label;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private LabelEventAction action;

    /** Keycloak subject that caused the move, or null for system/backfill writes. */
    @Column(name = "applied_by", length = 255)
    private String appliedBy;

    /** Optional short context ("forced past eval gate", "rolled back from v9"). */
    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    protected VersionLabelEvent() {
        // JPA
    }

    private VersionLabelEvent(
        VersionEntityType entityType, UUID entityId, String label, UUID versionId,
        LabelEventAction action, String appliedBy, String detail
    ) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.label = label;
        this.versionId = versionId;
        this.action = action;
        this.appliedBy = appliedBy;
        this.detail = detail != null && detail.length() > 500 ? detail.substring(0, 500) : detail;
        this.appliedAt = Instant.now();
    }

    public static VersionLabelEvent of(
        VersionEntityType entityType, UUID entityId, String label, UUID versionId,
        LabelEventAction action, String appliedBy, String detail
    ) {
        return new VersionLabelEvent(entityType, entityId, label, versionId, action, appliedBy, detail);
    }

    public UUID getId() { return id; }
    public VersionEntityType getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public String getLabel() { return label; }
    public UUID getVersionId() { return versionId; }
    public LabelEventAction getAction() { return action; }
    public String getAppliedBy() { return appliedBy; }
    public String getDetail() { return detail; }
    public Instant getAppliedAt() { return appliedAt; }
}
