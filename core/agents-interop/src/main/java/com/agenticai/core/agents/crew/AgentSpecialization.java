package com.agenticai.core.agents.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One agent's registration as a callable specialist for a role/capability tag - migration V304's
 * {@code agent_specialist}. See that migration's header for how this SpecialistRegistry primitive
 * differs from the existing {@code agent_collaborator} (V56) whitelist: an agent here is
 * discoverable by ANY other agent in the tenant via {@link #getCapabilityTag()}, not wired to one
 * specific delegator by id.
 *
 * <p>{@link #getCapabilityTag()} is always normalized (trimmed, lower-cased) by {@link
 * SpecialistRegistry} before a row is written or queried, so equality lookups never need a
 * case-insensitive comparison.
 */
@com.agenticai.core.security.TenantConfigEntity
@Entity
@Table(name = "agent_specialist")
public class AgentSpecialization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "capability_tag", nullable = false, length = 100)
    private String capabilityTag;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentSpecialization() {
        // JPA
    }

    public AgentSpecialization(UUID agentId, String capabilityTag, String description) {
        this.agentId = agentId;
        this.capabilityTag = capabilityTag;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getCapabilityTag() {
        return capabilityTag;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
