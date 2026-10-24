package com.agenticai.core.agents.limits;

import com.agenticai.core.agents.runtime.RuntimeLimits;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The tenant's runtime-limit defaults for all its agents (migration V671); at most one row, id 1. */
@Entity
@Table(name = "tenant_runtime_limits")
public class TenantRuntimeLimitsRow {

    static final short ONLY_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    private short id = ONLY_ID;

    @Embedded private RuntimeLimits limits;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    protected TenantRuntimeLimitsRow() {}

    TenantRuntimeLimitsRow(RuntimeLimits limits, String updatedBy) {
        apply(limits, updatedBy);
    }

    void apply(RuntimeLimits value, String by) {
        this.limits = value == null || value.isEmpty() ? null : value;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    RuntimeLimits limits() {
        return limits == null ? RuntimeLimits.none() : limits;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
