package com.agenticai.core.agents.capability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Generic per-agent capability flag row (migration V330) - the storage
 * backing {@link StoredAgentCapability}. One row per (agent, capability
 * key); a missing row reads as disabled.
 *
 * <p>Why a table instead of more {@code agent} columns: the four pre-existing
 * capability flags ({@code sarvam_enabled}, {@code e2b_enabled}, {@code
 * xai_server_tools_enabled}, {@code code_workspace_enabled}) each needed
 * their own Flyway migration, endpoint and workbench section - the coupling
 * this package exists to remove. New capabilities plug in with only an {@link
 * AgentCapability} bean; no schema change, no endpoint, no UI code. The four
 * legacy flags deliberately keep their columns (they are round-tripped by
 * {@code AgentConfigSnapshotCodec}/{@code TemplateSnapshotService} and copied
 * by {@code AgentService#duplicate}); their capability beans are
 * column-backed rather than row-backed.
 */
@Entity
@Table(name = "agent_capability")
@IdClass(AgentCapabilityStateId.class)
public class AgentCapabilityState {

    @Id
    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Id
    @Column(name = "capability_key", nullable = false, length = 64)
    private String capabilityKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AgentCapabilityState() {
    }

    public AgentCapabilityState(UUID agentId, String capabilityKey, boolean enabled) {
        this.agentId = agentId;
        this.capabilityKey = capabilityKey;
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getCapabilityKey() {
        return capabilityKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
