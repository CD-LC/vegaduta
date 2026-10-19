package com.agenticai.core.workflow.temporal;

/**
 * Signal payload delivered to a parked {@link WorkflowRunWorkflow} when the
 * approval decision for its current node lands (see {@code
 * WorkflowRunWorkflowImpl}'s await-approval branch for the full contract).
 *
 * <p>{@code nodeId} identifies WHICH node the decision belongs to - a run
 * parks at exactly one node at a time, so the workflow checks this before
 * accepting the signal and ignores anything addressed to a different node.
 *
 * <p>The signal is deliberately just a wake-up + outcome carrier: the
 * authoritative state change (the {@code AWAITING_APPROVAL -> RUNNING} claim,
 * or the terminal {@code FAILED} write on rejection) is already done by
 * {@code WorkflowApprovalActionExecutor} under the same {@code
 * run_state_transitions} + {@code workflow_runs} transaction the legacy
 * engine uses, BEFORE this signal is sent. The workflow therefore only reads
 * the decision here and re-executes the approved node with the replay grant
 * carried through {@link NodeExecutionActivityInput#approvalReplayActionType}
 * - it never rewrites run status itself on the approve path (that would
 * double-write against the row the executor already advanced).
 */
public record WorkflowApprovalSignal(
    String nodeId,
    boolean approved,
    String decidedBy,
    String reason
) {
}
