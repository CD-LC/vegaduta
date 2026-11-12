package com.agenticai.core.agents.learning.consolidation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A "forgotten" marker for one memory row (migration V683): hash and metadata only, never the text.
 * Writing a tombstone is the ONLY state change the consolidation apply path can make - it marks a
 * fact forgotten without deleting the row (deletion, restore windows and recall-side filtering are
 * a later work package), and it can never set a row TRUSTED or ACTIVE.
 */
@Entity
@Table(name = "memory_tombstone")
public class MemoryTombstone {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "subject_key", nullable = false, length = 255)
    private String subjectKey;

    /** The tier the forgotten row lived in: {@code agent} or {@code shared}. */
    @Column(name = "memory_kind", nullable = false, length = 16)
    private String memoryKind;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "original_id")
    private UUID originalId;

    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    /** {@code DECAY}, {@code QUARANTINE_TTL} or {@code MERGED_INTO:<keepId>}. */
    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    @Column(name = "actor", nullable = false, length = 64)
    private String actor;

    @Column(name = "score_at_delete", precision = 4, scale = 3)
    private BigDecimal scoreAtDelete;

    @Column(name = "trust_at_delete", length = 16)
    private String trustAtDelete;

    @Column(name = "deleted_at", nullable = false)
    private Instant deletedAt;

    @Column(name = "restorable_until")
    private Instant restorableUntil;

    protected MemoryTombstone() {}

    MemoryTombstone(
            UUID id,
            String subjectKey,
            String memoryKind,
            UUID agentId,
            UUID originalId,
            String contentSha256,
            String reason,
            String actor,
            BigDecimal scoreAtDelete,
            String trustAtDelete,
            Instant deletedAt) {
        this.id = id;
        this.subjectKey = subjectKey;
        this.memoryKind = memoryKind;
        this.agentId = agentId;
        this.originalId = originalId;
        this.contentSha256 = contentSha256;
        this.reason = reason;
        this.actor = actor;
        this.scoreAtDelete = scoreAtDelete;
        this.trustAtDelete = trustAtDelete;
        this.deletedAt = deletedAt;
    }

    public UUID getId() {
        return id;
    }
}
