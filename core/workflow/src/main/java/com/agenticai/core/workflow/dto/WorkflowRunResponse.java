package com.agenticai.core.workflow.dto;

import com.agenticai.core.workflow.WorkflowRun;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record WorkflowRunResponse(
    UUID id,
    UUID workflowId,
    String workflowName,
    WorkflowRunStatus status,
    String requestedBy,
    String errorMessage,
    List<NodeTraceEntry> trace,
    Instant startedAt,
    Instant completedAt,
    String outputFileName,
    // C1 pin: the workflow_version ordinal this run executed (stamped at
    // run start, label-resolved). Null for runs that predate stamping -
    // the console renders it as a "v{n}" badge and omits it when null.
    Integer workflowVersion,
    // Draft runs and pinning: "production" or "draft" (lower case, as sent in the run request). Additive; an
    // older client ignores it.
    String mode,
    // How many trace entries were served from a pin (status PINNED) instead of executing - the run summary that
    // stops a green draft run being mistaken for a real end-to-end run. 0 for every production run.
    int pinnedNodeCount,
    // SE-14: the operator's optional "Run comment", stored beside the run and never part of the node input.
    String comment,
    // SE-15: how many nodes degraded to SKIPPED (bad regex, blocked URL, unusable model reply, feature off) while
    // the run carried on and finished. A COMPLETED run with a non-zero count is "completed with warnings".
    int warningCount,
    // SE-54: true only on the response to a cancel request that was accepted for a RUNNING run. The row still
    // reads RUNNING (cancellation is observed between nodes); this says the stop is on its way. False elsewhere.
    boolean cancelRequested
) {

    /** Pre-comment arity: no comment, no warnings, no pending cancel. */
    public WorkflowRunResponse(
        UUID id, UUID workflowId, String workflowName, WorkflowRunStatus status, String requestedBy,
        String errorMessage, List<NodeTraceEntry> trace, Instant startedAt, Instant completedAt,
        String outputFileName, Integer workflowVersion, String mode, int pinnedNodeCount
    ) {
        this(id, workflowId, workflowName, status, requestedBy, errorMessage, trace, startedAt, completedAt,
            outputFileName, workflowVersion, mode, pinnedNodeCount, null, 0, false);
    }

    /** Pre-mode arity: a production run with nothing pinned. */
    public WorkflowRunResponse(
        UUID id, UUID workflowId, String workflowName, WorkflowRunStatus status, String requestedBy,
        String errorMessage, List<NodeTraceEntry> trace, Instant startedAt, Instant completedAt,
        String outputFileName, Integer workflowVersion
    ) {
        this(id, workflowId, workflowName, status, requestedBy, errorMessage, trace, startedAt, completedAt,
            outputFileName, workflowVersion, "production", 0, null, 0, false);
    }


    private static final Logger log = LoggerFactory.getLogger(WorkflowRunResponse.class);

    /** The response to an accepted cancel request on a RUNNING run: same row, flagged {@code cancelRequested}. */
    public static WorkflowRunResponse cancelRequested(WorkflowRun run, ObjectMapper objectMapper) {
        WorkflowRunResponse r = from(run, objectMapper);
        return new WorkflowRunResponse(r.id, r.workflowId, r.workflowName, r.status, r.requestedBy, r.errorMessage,
            r.trace, r.startedAt, r.completedAt, r.outputFileName, r.workflowVersion, r.mode, r.pinnedNodeCount,
            r.comment, r.warningCount, true);
    }

    public static WorkflowRunResponse from(WorkflowRun run, ObjectMapper objectMapper) {
        List<NodeTraceEntry> trace;
        try {
            trace = run.getTraceJson() == null
                ? List.of()
                : objectMapper.readValue(run.getTraceJson(), objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, NodeTraceEntry.class));
        } catch (Exception e) {
            // An empty trace renders as "this run did nothing" in the UI, which is a lie about
            // a run that may well have executed every node. WorkflowRunRecoveryJob already
            // treats unparseable trace bytes as a distinct, preserve-as-is state (see its
            // readTraceOrNull javadoc) - the read path should at least say so out loud.
            log.warn("Workflow run {} has unparseable traceJson - reporting an EMPTY trace to the client",
                run.getId(), e);
            trace = List.of();
        }
        int pinned = 0;
        int warnings = 0;
        for (NodeTraceEntry entry : trace) {
            if ("PINNED".equals(entry.status())) {
                pinned++;
            } else if ("SKIPPED".equals(entry.status())) {
                warnings++;
            }
        }
        return new WorkflowRunResponse(
            run.getId(),
            run.getWorkflowId(),
            run.getWorkflowName(),
            run.getStatus(),
            run.getRequestedBy(),
            run.getErrorMessage(),
            trace,
            run.getStartedAt(),
            run.getCompletedAt(),
            run.getOutputFileName(),
            run.getWorkflowVersion(),
            run.getRunMode().wire(),
            pinned,
            run.getComment(),
            warnings,
            false
        );
    }
}
