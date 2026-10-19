package com.agenticai.core.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One execution of a {@link WorkflowDefinition}. {@code id} doubles as the
 * {@code runId} correlation identifier used on the {@code workflow.events}
 * Kafka topic (see {@code WorkflowEventPublisher}) - generated up front by
 * {@link WorkflowExecutionEngine}, not by the database, so it can be
 * referenced in the very first "run started" event before this row is even
 * persisted. {@code traceJson} is the serialized {@code List<NodeTraceEntry>}
 * - the run-trace overlay's data source (PRD Section 5), and deliberately a
 * JSON blob rather than a child table for the same reason
 * {@code WorkflowDefinition.graphJson} is: a run's trace shape will keep
 * evolving per node type without needing a schema migration each time.
 */
@Entity
@Table(name = "workflow_run")
public class WorkflowRun {

    @Id
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "workflow_name", nullable = false, length = 200)
    private String workflowName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkflowRunStatus status;

    @Column(name = "requested_by", length = 200)
    private String requestedBy;

    @Column(name = "trace_json", columnDefinition = "TEXT")
    private String traceJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // Output node "file" destination (#152) - the run's own row is used as
    // the download-able artifact rather than a separate file-storage
    // subsystem, since a run's output is naturally small (agent replies,
    // knowledge lookups) and already lives in this table's trace_json
    // sibling column. Both nullable: only set when an Output node's channel
    // is "file".
    @Column(name = "output_file_name", length = 255)
    private String outputFileName;

    @Column(name = "output_data", columnDefinition = "TEXT")
    private String outputData;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // Pause/resume (V261) - the resume envelope, written only while PAUSED:
    // the node the traversal was about to run, and the previous node's
    // output it was about to consume. Same shape the approval-resume
    // envelope carries in the request payload (WorkflowApprovalActionExecutor),
    // kept as columns because a paused run has no approval row to hold them.
    // Cleared on resume so a stale envelope can never masquerade as a live
    // pause after the run has moved on.
    @Column(name = "paused_node_id", length = 100)
    private String pausedNodeId;

    @Column(name = "paused_node_input", columnDefinition = "TEXT")
    private String pausedNodeInput;

    // Workflow versioning (V273) - the workflow_version.version_number this
    // run executed against, stamped by WorkflowExecutionEngine#prepareRun at
    // start so run-to-run diff can say "these two runs ran different graphs"
    // with data, not inference. Nullable forever for runs that predate
    // versioning - an honest "unknown", never a fabricated 1.
    @Column(name = "workflow_version")
    private Integer workflowVersion;

    // V374: how the run was started. Persisted (not just passed down) so a run that parks for approval or
    // pause and resumes later keeps the mode it started in. Defaults to PRODUCTION, which never reads pins.
    @Enumerated(EnumType.STRING)
    @Column(name = "run_mode", nullable = false, length = 16)
    private RunMode runMode = RunMode.PRODUCTION;

    // V490: the optional operator note sent with the run (the Run modal's "Run comment"). Stored beside the
    // run, never part of the input the nodes see.
    @Column(name = "run_comment", length = 2000)
    private String comment;

    protected WorkflowRun() {
        // JPA
    }

    public WorkflowRun(UUID id, UUID workflowId, String workflowName, String requestedBy) {
        this.id = id;
        this.workflowId = workflowId;
        this.workflowName = workflowName;
        this.requestedBy = requestedBy;
        this.status = WorkflowRunStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    /**
     * Real-time log viewer (#393): updates just the trace, mid-run, without
     * touching {@code status}/{@code completedAt} - called once per node
     * inside {@link WorkflowExecutionEngine#execute}'s loop so a concurrent
     * {@code GET .../runs/{id}} poll sees real, growing progress instead of
     * only the all-at-once trace {@link #complete} writes at the very end.
     */
    public void updateTrace(String traceJson) {
        this.traceJson = traceJson;
    }

    public void complete(WorkflowRunStatus status, String traceJson, String errorMessage) {
        complete(status, traceJson, errorMessage, null, null);
    }

    public void complete(
        WorkflowRunStatus status, String traceJson, String errorMessage, String outputFileName, String outputData
    ) {
        this.status = status;
        this.traceJson = traceJson;
        this.errorMessage = errorMessage;
        this.outputFileName = outputFileName;
        this.outputData = outputData;
        this.completedAt = Instant.now();
    }

    /**
     * Pause Run (V261): parks the run between nodes. NOT {@link #complete} -
     * {@code completedAt} stays null because a paused run has not finished,
     * it is waiting; treating it as completed would lie to every consumer
     * that reads "done-ness" off that timestamp (and to the recovery job's
     * stale-RUNNING sweep, which keys on RUNNING only - PAUSED is its own
     * status, so no sweep change is needed either).
     */
    public void pause(String traceJson, String pausedNodeId, String pausedNodeInput) {
        this.status = WorkflowRunStatus.PAUSED;
        this.traceJson = traceJson;
        this.pausedNodeId = pausedNodeId;
        this.pausedNodeInput = pausedNodeInput;
    }

    /**
     * Called when a resume claims the run: the envelope has been read and the
     * run is RUNNING again, so the columns must not outlive the state they
     * describe.
     */
    public void clearPauseEnvelope() {
        this.pausedNodeId = null;
        this.pausedNodeInput = null;
    }

    /**
     * Resume Run (V261): flips PAUSED&rarr;RUNNING and clears the envelope in
     * one step. {@code compareAndSetStatus} has already claimed the row, but
     * it ran as a bulk update - the managed entity in this session can still
     * read PAUSED, and saving it would clobber the claim. Stamp the entity
     * with the state the DB already holds before it is saved.
     */
    public void resume() {
        this.status = WorkflowRunStatus.RUNNING;
        clearPauseEnvelope();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public WorkflowRunStatus getStatus() {
        return status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getTraceJson() {
        return traceJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getOutputFileName() {
        return outputFileName;
    }

    public String getOutputData() {
        return outputData;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    /** The resume envelope - see {@link #pause}. Null unless PAUSED. */
    public String getPausedNodeId() {
        return pausedNodeId;
    }

    public String getPausedNodeInput() {
        return pausedNodeInput;
    }

    /** The version ordinal this run executed - see the field's javadoc. Null for pre-V273 runs. */
    public Integer getWorkflowVersion() {
        return workflowVersion;
    }

    /** Called once, by {@code WorkflowExecutionEngine#prepareRun} at run start - see the field's javadoc. */
    public void setWorkflowVersion(Integer workflowVersion) {
        this.workflowVersion = workflowVersion;
    }

    /** Null when the run was started without a comment. */
    public String getComment() {
        return comment;
    }

    /** Called once at run start; blank is stored as null. */
    public void setComment(String comment) {
        this.comment = comment == null || comment.isBlank() ? null : comment.strip();
    }

    public RunMode getRunMode() {
        return runMode == null ? RunMode.PRODUCTION : runMode;
    }

    /** Called once at run start, before the first node executes. */
    public void setRunMode(RunMode runMode) {
        this.runMode = runMode == null ? RunMode.PRODUCTION : runMode;
    }
}
