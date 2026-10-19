package com.agenticai.core.quality.simulation;

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
 * One persona's adversarial run against the target agent, within a {@link
 * SimulationSession} (migration V266). State transitions live here, not in
 * {@link SimulationRunService} - same discipline {@code EvalRun} documents
 * for its own transitions.
 */
@Entity
@Table(name = "simulation_run")
public class SimulationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "persona_key", nullable = false, length = 64)
    private String personaKey;

    @Column(name = "persona_name", nullable = false, length = 255)
    private String personaName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SimulationRunStatus status;

    @Column(name = "transcript_json", columnDefinition = "TEXT")
    private String transcriptJson;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private SimulationVerdict verdict;

    @Column(name = "verdict_reason", columnDefinition = "TEXT")
    private String verdictReason;

    @Column(name = "turn_count", nullable = false)
    private int turnCount;

    /**
     * WHY the conversation ended (V344) - orthogonal to {@link #verdict}
     * (the judge's opinion of WHAT happened). Null on ERROR/CANCELLED rows.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private SimulationTermination termination;

    /**
     * The {@code agent_version} row this run's conversation ran against -
     * bound at trigger time, the same binding {@code
     * eval_run.agent_version_id} (V260) gives regression runs. Nullable:
     * pre-V344 rows and agents with no version rows carry no binding.
     */
    @Column(name = "agent_version_id")
    private UUID agentVersionId;

    /** Observed replay spend for this run's {@code sim:<runId>} session (V344). */
    @Column(name = "cost_usd", precision = 12, scale = 6)
    private java.math.BigDecimal costUsd;

    /**
     * Cooperative cancel flag (V344) - writer-separation: the cancel
     * endpoint flips this (via the session), only the owning worker writes
     * {@code status}.
     */
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected SimulationRun() {
        // JPA
    }

    public SimulationRun(UUID sessionId, String personaKey, String personaName) {
        this.sessionId = sessionId;
        this.personaKey = personaKey;
        this.personaName = personaName;
        this.status = SimulationRunStatus.QUEUED;
        this.turnCount = 0;
    }

    /** QUEUED -> RUNNING, stamped by the worker as it picks the run up. */
    public void markRunning() {
        assertTransitionFrom(SimulationRunStatus.QUEUED);
        this.status = SimulationRunStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    /** RUNNING -> PASSED/FAILED with the transcript, verdict and termination reason. */
    public void complete(
        String transcriptJson, int turnCount, SimulationVerdict verdict, String verdictReason,
        SimulationTermination termination
    ) {
        assertTransitionFrom(SimulationRunStatus.RUNNING);
        this.transcriptJson = transcriptJson;
        this.turnCount = turnCount;
        this.verdict = verdict;
        this.verdictReason = verdictReason;
        this.termination = termination;
        // INCONCLUSIVE has no PASSED/FAILED status counterpart on this entity
        // (see SimulationRunStatus's javadoc) - treated as FAILED-shaped for
        // status purposes ("did not confirm the agent held up"), but the
        // honest three-valued answer survives unmodified in `verdict` for
        // any reader that cares about the distinction.
        this.status = verdict == SimulationVerdict.PASSED ? SimulationRunStatus.PASSED : SimulationRunStatus.FAILED;
        this.completedAt = Instant.now();
    }

    /** QUEUED/RUNNING -> CANCELLED (V344) - written only by the owning worker. */
    public void markCancelled(String note) {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                "SimulationRun " + id + " is already terminal (" + status + ") - cannot cancel.");
        }
        this.status = SimulationRunStatus.CANCELLED;
        if (note != null) {
            this.errorMessage = note;
        }
        this.completedAt = Instant.now();
    }

    /** The cancel writer's touch point on a QUEUED run not yet picked up (V344). */
    public void requestCancel() {
        this.cancelRequested = true;
    }

    /** Trigger-time binding to the head agent_version row (V344) - see the field's javadoc. */
    public void bindAgentVersion(UUID agentVersionId) {
        this.agentVersionId = agentVersionId;
    }

    /** Accumulates observed replay spend - the owning worker writes it between turns. */
    public void recordCost(java.math.BigDecimal costUsd) {
        this.costUsd = costUsd;
    }

    /** Any non-terminal state -> ERROR. Reachable from QUEUED too (executor rejection, boot orphan sweep). */
    public void fail(String errorMessage) {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                "SimulationRun " + id + " is already terminal (" + status + ") - cannot fail it again.");
        }
        this.status = SimulationRunStatus.ERROR;
        this.errorMessage = errorMessage;
        this.completedAt = Instant.now();
    }

    private void assertTransitionFrom(SimulationRunStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                "SimulationRun " + id + " expected status " + expected + " but was " + status);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getPersonaKey() {
        return personaKey;
    }

    public String getPersonaName() {
        return personaName;
    }

    public SimulationRunStatus getStatus() {
        return status;
    }

    public String getTranscriptJson() {
        return transcriptJson;
    }

    public SimulationVerdict getVerdict() {
        return verdict;
    }

    public String getVerdictReason() {
        return verdictReason;
    }

    public int getTurnCount() {
        return turnCount;
    }

    /** Nullable - see the field's javadoc (V344). */
    public SimulationTermination getTermination() {
        return termination;
    }

    /** Nullable - which stored configuration this run exercised (V344). */
    public UUID getAgentVersionId() {
        return agentVersionId;
    }

    /** Nullable - observed replay spend (V344). */
    public java.math.BigDecimal getCostUsd() {
        return costUsd;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
