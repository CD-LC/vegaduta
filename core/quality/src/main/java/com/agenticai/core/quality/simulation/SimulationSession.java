package com.agenticai.core.quality.simulation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One simulation launch: N personas, each driving its own {@link
 * SimulationRun} against the same target agent (migration V266).
 */
@Entity
@Table(name = "simulation_session")
public class SimulationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** Comma-joined persona keys - see this column's own comment in migration V266. */
    @Column(name = "persona_keys", nullable = false, columnDefinition = "TEXT")
    private String personaKeys;

    @Column(name = "max_turns", nullable = false)
    private int maxTurns;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SimulationSessionStatus status;

    /**
     * Per-RUN cost ceiling in USD (V344, default $10.00 - the C3 spec's
     * number): each of this session's persona runs stops its conversation
     * once observed replay spend for its own {@code sim:<runId>} session
     * exceeds this. A budget-capped run ends CANCELLED - never a verdict.
     */
    @Column(name = "budget_usd", nullable = false, precision = 10, scale = 2)
    private BigDecimal budgetUsd;

    /**
     * Cooperative cancel flag (V344) - a separate column, NOT a status
     * write, so only the owning worker writes {@code status} for a live run
     * (the writer-separation contract {@code agent_job.cancel_requested}
     * documents). Each run worker observes it between turns.
     */
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "cancel_requested_by", length = 255)
    private String cancelRequestedBy;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected SimulationSession() {
        // JPA
    }

    public SimulationSession(UUID agentId, List<String> personaKeys, int maxTurns, String createdBy) {
        this.agentId = agentId;
        this.personaKeys = String.join(",", personaKeys);
        this.maxTurns = maxTurns;
        this.createdBy = createdBy;
        this.status = SimulationSessionStatus.QUEUED;
        this.budgetUsd = new BigDecimal("10.00");
        this.createdAt = Instant.now();
    }

    /** QUEUED/RUNNING -> RUNNING - idempotent, called as soon as the first run picks up work. */
    public void markRunning() {
        if (status == SimulationSessionStatus.COMPLETED
            || status == SimulationSessionStatus.CANCELLED) {
            return; // A session never regresses out of a terminal state.
        }
        this.status = SimulationSessionStatus.RUNNING;
    }

    /** -> COMPLETED, once every run has reached a terminal state. */
    public void markCompleted() {
        this.status = SimulationSessionStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /**
     * -> CANCELLED when the last live run drained after a cancel was
     * requested - the session's terminal state then records that it was
     * stopped, not that it ran to completion.
     */
    public void markCancelled() {
        this.status = SimulationSessionStatus.CANCELLED;
        this.completedAt = Instant.now();
    }

    /** The cancel WRITER's only touch point (V344) - never writes status. */
    public void requestCancel(String requestedBy) {
        this.cancelRequested = true;
        this.cancelRequestedBy = requestedBy;
    }

    /** Trigger-time option: per-run budget override. */
    public void setBudgetUsd(BigDecimal budgetUsd) {
        this.budgetUsd = budgetUsd;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public List<String> getPersonaKeys() {
        return personaKeys == null || personaKeys.isBlank() ? List.of() : List.of(personaKeys.split(","));
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public SimulationSessionStatus getStatus() {
        return status;
    }

    public BigDecimal getBudgetUsd() {
        return budgetUsd;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public String getCancelRequestedBy() {
        return cancelRequestedBy;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
