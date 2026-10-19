package com.agenticai.core.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Master Roadmap 2026 item #13 (A/B testing & feature flags, §B) - an
 * observability/audit record of "this tenant got this variant of this
 * flag," NOT the source of truth for the bucketing decision itself. {@link
 * FeatureFlagService#evaluate} computes the variant deterministically
 * (stable hash of {@code flagKey + tenantId} against {@link
 * FeatureFlag#getRolloutPercentage()}) every time it's called, so stickiness
 * across restarts/requests comes from the hash being deterministic, not
 * from reading this table back - this row is written best-effort, once per
 * (flag, tenant) pair, purely so a platform admin can see who's in which
 * bucket and when they were first assigned, without re-deriving it from
 * hashes by hand.
 */
@Entity
@Table(
    name = "experiment_assignment",
    schema = "control_plane",
    uniqueConstraints = @UniqueConstraint(columnNames = {"flag_key", "tenant_id"})
)
public class ExperimentAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "flag_key", nullable = false, length = 120)
    private String flagKey;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "variant", nullable = false, length = 32)
    private String variant;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    protected ExperimentAssignment() {
        // JPA
    }

    public ExperimentAssignment(String flagKey, String tenantId, String variant) {
        this.flagKey = flagKey;
        this.tenantId = tenantId;
        this.variant = variant;
        this.assignedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getFlagKey() {
        return flagKey;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getVariant() {
        return variant;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }
}
