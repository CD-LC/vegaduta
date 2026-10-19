package com.agenticai.core.agents.tools;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One row of {@code agent_step_journal} (V376). See {@link StepJournal}. */
@Entity
@Table(name = "agent_step_journal")
public class StepJournalEntry {

    @Id private UUID id;

    @Column(name = "run_id", nullable = false, length = 128)
    private String runId;

    @Column(name = "step_id", nullable = false, length = 200)
    private String stepId;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "result_text", columnDefinition = "TEXT")
    private String resultText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected StepJournalEntry() {}

    StepJournalEntry(String runId, String stepId, String kind, Instant now) {
        this.id = UUID.randomUUID();
        this.runId = runId;
        this.stepId = stepId;
        this.kind = kind;
        this.status = StepJournal.STARTED;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getStepId() {
        return stepId;
    }

    public String getKind() {
        return kind;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getResultText() {
        return resultText;
    }

    /** Puts a finished row back in flight (a repeat call of the same TOOL key). */
    void restart(Instant now) {
        this.status = StepJournal.STARTED;
        this.resultText = null;
        this.completedAt = null;
        this.createdAt = now;
    }

    void complete(String result, Instant now) {
        this.status = StepJournal.COMPLETED;
        this.resultText = result;
        this.completedAt = now;
    }
}
