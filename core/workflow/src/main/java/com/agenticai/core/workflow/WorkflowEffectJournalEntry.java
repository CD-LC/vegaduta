package com.agenticai.core.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the workflow effect journal (V680): the durable record that an irreversible node ran
 * its side effect for a given {@code (run, node, iteration)}. Read-mostly from the JPA side - every
 * state change goes through {@link WorkflowEffectJournalRepository}'s atomic native statements, so
 * two racing executions can never both believe they own the effect. See {@link
 * WorkflowEffectJournal} for the protocol.
 */
@Entity
@Table(name = "workflow_effect_journal")
public class WorkflowEffectJournalEntry {

    public static final String STARTED = "STARTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String UNCERTAIN = "UNCERTAIN";

    @Id private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "node_id", nullable = false, length = 200)
    private String nodeId;

    @Column(name = "node_type", nullable = false, length = 64)
    private String nodeType;

    @Column(name = "iteration_key", nullable = false, length = 64)
    private String iterationKey;

    @Column(name = "idempotency_key", nullable = false, length = 512)
    private String idempotencyKey;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "result_status", length = 32)
    private String resultStatus;

    @Column(name = "result_output", columnDefinition = "TEXT")
    private String resultOutput;

    @Column(name = "result_output_truncated", nullable = false)
    private boolean resultOutputTruncated;

    @Column(name = "result_digest", length = 64)
    private String resultDigest;

    @Column(name = "result_branch", length = 200)
    private String resultBranch;

    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @Column(name = "result_file_name", length = 255)
    private String resultFileName;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected WorkflowEffectJournalEntry() {}

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public String getIterationKey() {
        return iterationKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getResultStatus() {
        return resultStatus;
    }

    public String getResultOutput() {
        return resultOutput;
    }

    public boolean isResultOutputTruncated() {
        return resultOutputTruncated;
    }

    public String getResultDigest() {
        return resultDigest;
    }

    public String getResultBranch() {
        return resultBranch;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public String getResultFileName() {
        return resultFileName;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
