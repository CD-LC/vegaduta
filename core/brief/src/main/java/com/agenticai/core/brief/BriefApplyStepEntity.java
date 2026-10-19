package com.agenticai.core.brief;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One apply/compensate step ({@code brief_apply_step}). Written by the apply engine (a later
 * layer); this module only defines the mapping so the tables have entities and repositories from
 * day one. Status: PENDING | DONE | FAILED | COMPENSATED.
 */
@Entity
@Table(name = "brief_apply_step")
public class BriefApplyStepEntity {

    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String COMPENSATED = "COMPENSATED";

    @Id
    private UUID id;

    @Column(name = "brief_id", nullable = false)
    private UUID briefId;

    @Column(nullable = false)
    private int version;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(length = 32)
    private String ref;

    @Column(name = "entity_id", length = 64)
    private String entityId;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "compensated_at")
    private Instant compensatedAt;

    protected BriefApplyStepEntity() {
        // JPA
    }

    public BriefApplyStepEntity(UUID briefId, int version, UUID jobId, int seq, String kind, String ref) {
        this.id = UUID.randomUUID();
        this.briefId = briefId;
        this.version = version;
        this.jobId = jobId;
        this.seq = seq;
        this.kind = kind;
        this.ref = ref;
        this.createdAt = Instant.now();
    }

    public void markDone(String entityId) {
        this.entityId = entityId;
        this.status = DONE;
    }

    /** DONE with a note (a skipped / degraded step stores its reason here; it is not an error). */
    public void markDone(String entityId, String note) {
        this.entityId = entityId;
        this.status = DONE;
        this.error = note;
    }

    /** Records the entity the moment the real service returns it, before the step is finished, so a later failure can still undo it. */
    public void recordEntity(String entityId) {
        this.entityId = entityId;
    }

    public void markFailed(String error) {
        this.status = FAILED;
        this.error = error;
    }

    public void markCompensated() {
        this.status = COMPENSATED;
        this.compensatedAt = Instant.now();
    }

    /** The undo itself failed: the step keeps its status and the entity id, and says so. */
    public void noteCompensationFailure(String message) {
        this.error = (this.error == null ? "" : this.error + " | ") + "compensation failed: " + message;
        this.compensatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getBriefId() {
        return briefId;
    }

    public int getVersion() {
        return version;
    }

    public UUID getJobId() {
        return jobId;
    }

    public int getSeq() {
        return seq;
    }

    public String getKind() {
        return kind;
    }

    public String getRef() {
        return ref;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompensatedAt() {
        return compensatedAt;
    }
}
