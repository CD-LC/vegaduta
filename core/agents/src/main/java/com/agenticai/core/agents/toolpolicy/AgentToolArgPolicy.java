package com.agenticai.core.agents.toolpolicy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One agent's per-tool argument policies (V442), as the validated JSON document
 * {@code com.agenticai.core.guardrails.toolargs.ToolArgPolicySet} produced. One row per agent; absence of a
 * row means "no policies". TEXT not JSONB: nothing queries into it and the document is always read whole.
 * The row is removed with its agent (ON DELETE CASCADE in V442).
 */
@Entity
@Table(name = "agent_tool_arg_policy")
public class AgentToolArgPolicy {

    @Id
    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "policies_json", nullable = false, columnDefinition = "TEXT")
    private String policiesJson;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentToolArgPolicy() {
        // JPA
    }

    public AgentToolArgPolicy(UUID agentId, String policiesJson, String updatedBy) {
        this.agentId = agentId;
        this.policiesJson = policiesJson;
        this.updatedBy = updatedBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Intention-named update; bumps {@code updatedAt}. */
    public void replace(String policiesJson, String updatedBy) {
        this.policiesJson = policiesJson;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getPoliciesJson() {
        return policiesJson;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
