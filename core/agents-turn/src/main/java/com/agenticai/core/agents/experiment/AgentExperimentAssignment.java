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
 * One subject's sticky bucketing decision for one {@link AgentExperiment} -
 * migration V265's {@code agent_experiment_assignment}. Written once, on
 * first sight of a (experiment, subjectKey) pair, and never rewritten:
 * {@link AgentExperimentService#resolveEffectiveAgentId} looks this up
 * BEFORE bucketing, and a later change to {@link AgentExperiment#getTrafficPercent()}
 * must never reshuffle a subject who was already assigned - see that
 * method's javadoc.
 */
@Entity
@Table(name = "agent_experiment_assignment")
public class AgentExperimentAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "experiment_id", nullable = false)
    private UUID experimentId;

    @Column(name = "subject_key", nullable = false, length = 255)
    private String subjectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExperimentVariant variant;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    protected AgentExperimentAssignment() {
        // JPA
    }

    public AgentExperimentAssignment(UUID experimentId, String subjectKey, ExperimentVariant variant) {
        this.experimentId = experimentId;
        this.subjectKey = subjectKey;
        this.variant = variant;
        this.assignedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getExperimentId() {
        return experimentId;
    }

    public String getSubjectKey() {
        return subjectKey;
    }

    public ExperimentVariant getVariant() {
        return variant;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }
}
