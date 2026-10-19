package com.agenticai.core.agents.experiment;

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
 * One A/B experiment comparing a base agent (CONTROL) against a hidden
 * treatment clone (TREATMENT) - migration V265. See that migration's header
 * for the full "why a real cloned agent row, not a shadow execution path"
 * reasoning, and {@link ExperimentStatus} for the lifecycle this entity
 * enforces on itself (same "transitions live on the entity" discipline
 * {@code EvalRun} documents).
 */
@Entity
@Table(name = "agent_experiment")
public class AgentExperiment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExperimentStatus status;

    @Column(name = "control_agent_id", nullable = false)
    private UUID controlAgentId;

    @Column(name = "treatment_agent_id", nullable = false)
    private UUID treatmentAgentId;

    @Column(name = "treatment_version_id")
    private UUID treatmentVersionId;

    @Column(name = "traffic_percent", nullable = false)
    private int trafficPercent;

    @Column(name = "auto_stop_error_threshold_pct")
    private Integer autoStopErrorThresholdPct;

    @Column(name = "stop_reason", columnDefinition = "TEXT")
    private String stopReason;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    protected AgentExperiment() {
        // JPA
    }

    public AgentExperiment(
        UUID agentId,
        String name,
        UUID controlAgentId,
        UUID treatmentAgentId,
        UUID treatmentVersionId,
        int trafficPercent,
        Integer autoStopErrorThresholdPct,
        String createdBy
    ) {
        this.agentId = agentId;
        this.name = name;
        this.controlAgentId = controlAgentId;
        this.treatmentAgentId = treatmentAgentId;
        this.treatmentVersionId = treatmentVersionId;
        this.trafficPercent = trafficPercent;
        this.autoStopErrorThresholdPct = autoStopErrorThresholdPct;
        this.createdBy = createdBy;
        this.status = ExperimentStatus.DRAFT;
        this.createdAt = Instant.now();
    }

    /** DRAFT -> RUNNING. */
    public void start() {
        assertTransitionFrom(ExperimentStatus.DRAFT);
        this.status = ExperimentStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    /** DRAFT or RUNNING -> STOPPED, manual or automatic (see {@code reason}). */
    public void stop(String reason) {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                "Experiment " + id + " is already " + status + " - cannot stop it again.");
        }
        this.status = ExperimentStatus.STOPPED;
        this.stopReason = reason;
        this.stoppedAt = Instant.now();
    }

    private void assertTransitionFrom(ExperimentStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                "Experiment " + id + " expected status " + expected + " but was " + status);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getName() {
        return name;
    }

    public ExperimentStatus getStatus() {
        return status;
    }

    public UUID getControlAgentId() {
        return controlAgentId;
    }

    public UUID getTreatmentAgentId() {
        return treatmentAgentId;
    }

    /** Nullable - the agent_version row the treatment clone was created from (provenance only). */
    public UUID getTreatmentVersionId() {
        return treatmentVersionId;
    }

    public int getTrafficPercent() {
        return trafficPercent;
    }

    /** Nullable - null means no automatic stop guardrail is configured. */
    public Integer getAutoStopErrorThresholdPct() {
        return autoStopErrorThresholdPct;
    }

    /** Nullable - populated only once {@link #stop} has run. */
    public String getStopReason() {
        return stopReason;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getStoppedAt() {
        return stoppedAt;
    }
}
