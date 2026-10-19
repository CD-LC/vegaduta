package com.agenticai.core.workflow.temporal;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * The durable counterpart to {@code WorkflowExecutionEngine.execute}'s
 * node-walking loop - one Temporal Workflow Execution per {@code
 * workflow_run} row. See {@link WorkflowRunWorkflowImpl} for the
 * implementation and its javadoc for the determinism constraints that
 * shaped this design.
 */
@WorkflowInterface
public interface WorkflowRunWorkflow {

    @WorkflowMethod
    WorkflowRunResult run(WorkflowRunInput input);

    /**
     * Delivers the human approval decision to a run parked at an
     * {@code AWAITING_APPROVAL} node (v1 signal-resume path - see {@code
     * WorkflowRunWorkflowImpl}'s {@code AWAIT_APPROVAL_RESUME} change marker
     * for how this interacts with histories recorded before the signal
     * existed). Run-state persistence stays with {@code
     * WorkflowApprovalActionExecutor}, which is also the only caller - it
     * sends this signal AFTER it has already CAS-claimed the run row.
     */
    @SignalMethod
    void approvalDecision(WorkflowApprovalSignal signal);
}
