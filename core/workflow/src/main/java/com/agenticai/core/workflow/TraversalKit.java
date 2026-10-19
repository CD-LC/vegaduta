package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;

/**
 * What every {@link ContainerStep} and the engine's node loop share: trace recording, node execution with retry,
 * the leg finaliser and the Stop Run flag. A plain holder so each step takes one constructor argument.
 */
final class TraversalKit {

    final RunTraceRecorder recorder;
    final NodeAttemptExecutor attempts;
    final RunFinalizer finalizer;
    final WorkflowGraphSupport graphSupport;
    private final WorkflowRunCancellationService cancellationService;
    private final WorkflowNodeExecutor nodeExecutor;

    TraversalKit(
        RunTraceRecorder recorder, NodeAttemptExecutor attempts, RunFinalizer finalizer,
        WorkflowGraphSupport graphSupport, WorkflowRunCancellationService cancellationService,
        WorkflowNodeExecutor nodeExecutor
    ) {
        this.recorder = recorder;
        this.attempts = attempts;
        this.finalizer = finalizer;
        this.graphSupport = graphSupport;
        this.cancellationService = cancellationService;
        this.nodeExecutor = nodeExecutor;
    }

    boolean cancellationRequested(RunTraversalState state) {
        return cancellationService.isCancellationRequested(state.runId);
    }

    /**
     * Stop Run - the terminal path for every cancellation check site. {@code nextNode} is whichever node the
     * traversal was about to hand off to when the flag was observed - attributed on the trace entry as "cancelled
     * before it started" rather than as a failure of that node, since it never actually ran.
     */
    WorkflowRun cancelled(RunTraversalState state, WorkflowNodeDto nextNode, String contextSuffix) {
        return finalizer.finalizeLeg(state.leg(), new RunFinalizer.Cancelled(nextNode, contextSuffix));
    }

    /** A node (or container item) asked to end the run: AWAITING_APPROVAL, AWAITING_INPUT, FAILED, ... */
    WorkflowRun halted(RunTraversalState state, WorkflowNodeExecutor.NodeExecutionResult halting) {
        return finalizer.finalizeLeg(state.leg(), new RunFinalizer.Halted(halting.haltRun(), halting.summary()));
    }

    /**
     * The container node's own output ({@code loop}, {@code parallel}, {@code fan_out}, {@code sub_flow} never
     * reach {@code executeNode}), so a later {@code $node["<container label>"]} reads the merged array.
     */
    void recordContainerOutput(RunTraversalState state, WorkflowNodeDto node, String output) {
        nodeExecutor.recordContainerOutput(
            state.definition.getId().toString(), state.definition.getName(), state.runIdText(),
            state.requestedBy, node, output);
    }
}
