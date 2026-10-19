package com.agenticai.core.guardrails.procedures;

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
 * A named, ordered set of natural-language business rules ("the refund SOP",
 * "the escalation SOP") attachable to one or more agents - the layer between
 * a raw system prompt and a full workflow graph. The rules themselves live
 * on {@link ProcedureRule} rows; this entity is the envelope (name,
 * description, lifecycle).
 *
 * <p><b>Tenant-schema entity</b> (no {@code schema=} on {@code @Table}, same
 * convention as {@code GuardrailRule}): it lives in the calling tenant's own
 * Postgres schema via the platform's Hibernate multi-tenancy resolver.
 * {@code tenantId} is redundant with the schema for isolation - kept for
 * cross-schema operator tooling and audit correlation, exactly like {@code
 * GuardrailRule#tenantId}.
 *
 * <p>Persisted via migration V270 ({@code procedures}, {@code
 * db/migration/tenant}).
 */
@Entity
@Table(name = "procedures")
public class Procedure {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", length = 255)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ProcedureStatus status;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Procedure() {
        // JPA
    }

    public Procedure(String tenantId, String name, String description, String createdBy) {
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.status = ProcedureStatus.DRAFT;
        this.createdBy = createdBy;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void applyEdit(String name, String description) {
        this.name = name;
        this.description = description;
        this.updatedAt = Instant.now();
    }

    public void setStatus(ProcedureStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ProcedureStatus getStatus() {
        return status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
