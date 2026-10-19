package com.agenticai.core.providers.capability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A tenant's policy overrides for one capability - the generic form of what
 * {@code jev_policy} (V334) did for Jev alone: "control how the capability
 * behaves for this tenant," not just whose key funds it. One row per
 * (capability, tenant schema) - schema-per-tenant multi-tenancy makes the
 * tenant scoping structural, same posture {@link ProviderCredential} uses.
 *
 * <p>{@code overrides} is a free-form jsonb map because every capability's
 * knobs differ (the DECISION capability's {@code costRouting}/
 * {@code agentRouting}/{@code simpleThreshold} have nothing in common with a
 * future TTS capability's knobs). The contract is nullable-override: absent
 * keys mean "inherit the platform default," so an empty object and a missing
 * row are the same honest state, and no tenant needs a row at all. Key/type/
 * bounds validation is delegated to {@link CapabilityPolicyValidator} beans -
 * this entity deliberately doesn't know any capability's schema.
 */
@Entity
@Table(
    name = "capability_policy",
    uniqueConstraints = @UniqueConstraint(columnNames = "capability")
)
public class CapabilityPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The {@link Capability#key()} this row's overrides apply to - e.g. {@code "decision"}. */
    @Column(name = "capability", nullable = false, length = 32)
    private String capability;

    /** Raw JSON object of nullable overrides, e.g. {@code {"costRouting":false}} - callers own decoding. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "overrides", nullable = false, columnDefinition = "jsonb")
    private String overrides;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CapabilityPolicy() {
        // JPA
    }

    public CapabilityPolicy(String capability, String overrides) {
        this.capability = capability;
        this.overrides = overrides;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Replace-in-place, mirroring {@code JevPolicy#apply} - the single row per capability is reused across every PUT so history (createdAt) survives. */
    public void apply(String overrides) {
        this.overrides = overrides;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getCapability() {
        return capability;
    }

    public String getOverrides() {
        return overrides;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
