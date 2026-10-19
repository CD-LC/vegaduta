package com.agenticai.core.tenancy;

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
 * The platform-admin "delete tenant completely" control's durable audit
 * trail - see migration {@code V188__platform_tenant_deletion_log.sql} for
 * why this is its own control-plane table rather than the normal per-tenant
 * {@code audit_log_entry} (that table lives inside the schema this feature
 * drops).
 *
 * <p>A row is written {@code INITIATED} BEFORE any deletion step runs - see
 * {@link TenantDataErasureService#eraseTenantById}, which aborts before
 * touching any data if this write itself fails. It is later updated in
 * place to {@code COMPLETED} or {@code FAILED}, never a second row - one row
 * per deletion attempt is the whole point of the audit trail.
 */
@Entity
@Table(name = "platform_tenant_deletion_log", schema = "control_plane")
public class PlatformTenantDeletionLog {

    public enum Status {
        INITIATED,
        COMPLETED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "tenant_name", nullable = false, length = 255)
    private String tenantName;

    @Column(name = "actor_id", length = 255)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_detail", columnDefinition = "TEXT")
    private String failureDetail;

    @Column(name = "steps_completed", columnDefinition = "TEXT")
    private String stepsCompleted;

    protected PlatformTenantDeletionLog() {
        // JPA
    }

    public PlatformTenantDeletionLog(UUID tenantId, String tenantName, String actorId) {
        this.tenantId = tenantId;
        this.tenantName = tenantName;
        this.actorId = actorId;
        this.status = Status.INITIATED;
        this.initiatedAt = Instant.now();
    }

    public void markCompleted(String stepsCompleted) {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
        this.stepsCompleted = stepsCompleted;
    }

    public void markFailed(String failureDetail, String stepsCompleted) {
        this.status = Status.FAILED;
        this.completedAt = Instant.now();
        this.failureDetail = failureDetail;
        this.stepsCompleted = stepsCompleted;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getTenantName() {
        return tenantName;
    }

    public String getActorId() {
        return actorId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getInitiatedAt() {
        return initiatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getFailureDetail() {
        return failureDetail;
    }

    public String getStepsCompleted() {
        return stepsCompleted;
    }
}
