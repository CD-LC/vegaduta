package com.agenticai.core.agents.experiment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentExperimentRepository extends JpaRepository<AgentExperiment, UUID> {

    /** The experiment list for one agent, newest first. */
    List<AgentExperiment> findByAgentIdOrderByCreatedAtDesc(UUID agentId);

    Optional<AgentExperiment> findByIdAndAgentId(UUID id, UUID agentId);

    /**
     * The at-most-one active (DRAFT/RUNNING) experiment for this agent -
     * backed by {@code uq_agent_experiment_active_per_agent}, so "at most
     * one" is a database invariant, not just a convention this query hopes
     * holds.
     */
    Optional<AgentExperiment> findByAgentIdAndStatusIn(UUID agentId, List<ExperimentStatus> statuses);

    /**
     * The hot-path lookup {@link AgentExperimentService#resolveEffectiveAgentId}
     * uses on every chat turn: "is there a RUNNING experiment for this
     * agent". Same invariant as above restricts this to at most one row.
     */
    Optional<AgentExperiment> findByAgentIdAndStatus(UUID agentId, ExperimentStatus status);

    /**
     * The scheduled auto-stop sweep's per-tenant read (called once per
     * active tenant schema by {@code TenantSchemaIterator}) - every RUNNING
     * experiment that configured a threshold.
     */
    List<AgentExperiment> findByStatusAndAutoStopErrorThresholdPctIsNotNull(ExperimentStatus status);
}
