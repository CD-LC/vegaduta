package com.agenticai.core.quality.gate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The per-agent promote gate (migration V260): "before this agent's
 * configuration may be labelled PRODUCTION, dataset X must have a qualifying
 * PASSED run against exactly that configuration".
 *
 * <p>One row per agent at most (unique {@code agent_id}) - "which dataset must
 * pass" is a single-valued policy, not a list. The row exists only when a
 * tenant configured a gate; its ABSENCE means "no gate" and a promote is
 * unconstrained - opt-in enforcement, matching how the whole eval surface is
 * opt-in.
 *
 * <p>{@code enabled} is a column rather than "delete the row to disable" so a
 * temporary suspension does not lose the configured dataset. This matters for
 * the FREE-tier edge: eval runs are PREMIUM-gated, so a tenant that downgraded
 * with a live gate could never produce a new qualifying run - they can always
 * still disable the gate (the controller allows disable on any plan) instead
 * of being permanently blocked from promoting.
 */
@Entity
@Table(name = "agent_eval_gate")
public class AgentEvalGate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false, unique = true)
    private UUID agentId;

    /** FK to eval_dataset with cascade - a deleted dataset leaves nothing to gate against. */
    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /**
     * Freshness window for the qualifying run; null = any age qualifies. The
     * version binding already guarantees the run tested this exact
     * configuration, so this is strictly a "tested recently" policy on top.
     */
    @Column(name = "max_run_age_hours")
    private Integer maxRunAgeHours;

    /**
     * Simulation leg (V394): when true, promote additionally requires the
     * latest judged simulation session on this exact configuration to be
     * all-PASSED - "the candidate survived the digital twin" on top of "the
     * candidate passed the eval set". Opt-in; existing gates default false.
     */
    @Column(name = "require_simulation", nullable = false)
    private boolean requireSimulation;

    /**
     * Freshness window on the simulation evidence; null = any age qualifies.
     * Only consulted when {@link #requireSimulation} is true.
     */
    @Column(name = "max_simulation_age_hours")
    private Integer maxSimulationAgeHours;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentEvalGate() {
        // JPA
    }

    public AgentEvalGate(UUID agentId, UUID datasetId, boolean enabled, Integer maxRunAgeHours, String actor) {
        this(agentId, datasetId, enabled, maxRunAgeHours, false, null, actor);
    }

    public AgentEvalGate(UUID agentId, UUID datasetId, boolean enabled, Integer maxRunAgeHours,
                         boolean requireSimulation, Integer maxSimulationAgeHours, String actor) {
        this.agentId = agentId;
        this.datasetId = datasetId;
        this.enabled = enabled;
        this.maxRunAgeHours = maxRunAgeHours;
        this.requireSimulation = requireSimulation;
        this.maxSimulationAgeHours = maxSimulationAgeHours;
        this.createdBy = actor;
        this.updatedBy = actor;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(UUID datasetId, boolean enabled, Integer maxRunAgeHours, String actor) {
        update(datasetId, enabled, maxRunAgeHours, false, null, actor);
    }

    public void update(UUID datasetId, boolean enabled, Integer maxRunAgeHours,
                       boolean requireSimulation, Integer maxSimulationAgeHours, String actor) {
        this.datasetId = datasetId;
        this.enabled = enabled;
        this.maxRunAgeHours = maxRunAgeHours;
        this.requireSimulation = requireSimulation;
        this.maxSimulationAgeHours = maxSimulationAgeHours;
        this.updatedBy = actor;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public UUID getDatasetId() {
        return datasetId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Integer getMaxRunAgeHours() {
        return maxRunAgeHours;
    }

    public boolean isRequireSimulation() {
        return requireSimulation;
    }

    public Integer getMaxSimulationAgeHours() {
        return maxSimulationAgeHours;
    }

    public String getCreatedBy() {
        return createdBy;
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
