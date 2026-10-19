package com.agenticai.core.workflow;

import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One ordinary (non-container) node of {@link WorkflowExecutionEngine}'s traversal: executes it through the
 * shared attempt executor, applies the per-node error strategy (onError / retry) when enabled, records the trace
 * row, and decides where the run continues - the normal edge, the node's "error" edge, or a halt.
 *
 * <p>A node failure with neither {@code onError=continue} nor an error edge is rethrown, so the engine's outer
 * handler fails the run exactly as before.
 */
final class PlainNodeStep {

    private final TraversalKit kit;
    private final ObjectMapper objectMapper;

    PlainNodeStep(TraversalKit kit, ObjectMapper objectMapper) {
        this.kit = kit;
        this.objectMapper = objectMapper;
    }

    /**
     * @param attachments the run's one-shot inline attachments when this is the node right after the Trigger,
     *     else empty
     * @param errorStrategyEnabled {@code app.workflow.node-error-strategy.enabled}
     */
    ContainerStep.Outcome run(
        RunTraversalState s, WorkflowNodeDto node, String currentOutput, List<ChatInlineAttachment> attachments,
        boolean errorStrategyEnabled
    ) {
        RunTraceRecorder recorder = kit.recorder;
        WorkflowGraphSupport graphSupport = kit.graphSupport;
        List<NodeTraceEntry> trace = s.trace;
        String runId = s.runIdText();
        Instant startedAt = Instant.now();
        WorkflowNodeExecutor.NodeExecutionResult result;
        // Per-node error strategy (flag-gated; null = nothing configured or flag off = today's path exactly).
        final NodeErrorStrategy.Policy strategy = errorStrategyEnabled ? NodeErrorStrategy.parse(node) : null;
        final NodeErrorStrategy.Retry strategyRetry = strategy == null ? null : strategy.retry();
        final List<NodeErrorStrategy.Attempt> retriedAttempts = new ArrayList<>();
        final String nodeInput = currentOutput;
        try {
            if (strategyRetry == null) {
                result = kit.attempts.execute(
                    s.definition, runId, node, currentOutput, s.requestedBy, attachments, s.mode);
            } else {
                // The strategy owns the retry loop: each inner call is exactly one try.
                result = NodeErrorStrategy.runWithRetry(strategyRetry,
                    () -> kit.attempts.execute(s.definition, runId, node, nodeInput, s.requestedBy,
                        attachments, null, s.mode, 1),
                    Thread::sleep, Instant::now, retriedAttempts);
            }
        } catch (RuntimeException nodeFailure) {
            traceRetriedAttempts(trace, node, nodeInput, retriedAttempts, strategyRetry, true);
            if (strategy != null && strategy.onError() == NodeErrorStrategy.OnError.CONTINUE
                && !(nodeFailure instanceof TenantLimitExceededException)
                && !(nodeFailure instanceof WorkflowValidationException)) {
                // onError=continue: the failure becomes this node's {error, nodeId} output and the run
                // carries on down the node's normal outgoing edge. A plan/quota denial or a graph
                // validation failure is not a node failure to swallow (retry skips the former too), so
                // it still fails the run.
                trace.add(recorder.withRunData(trace, new NodeTraceEntry(node.id(), node.type(), node.label(), "ERROR",
                    "Node failed but the run continued (onError=continue): " + nodeFailure.getMessage(),
                    startedAt, Instant.now(), List.of(String.valueOf(nodeFailure.getMessage()))), node, nodeInput, null,
                    NodeTracePayloads.MAX_FIELD_CHARS, s.runId));
                String output = NodeErrorStrategy.continueOutput(objectMapper, node, nodeFailure.getMessage());
                kit.recordContainerOutput(s, node, output);
                recorder.persist(s);
                recorder.nodeCompleted(s, node, "ERROR");
                return ContainerStep.Outcome.next(graphSupport.nextNodeId(node, s.edgesOf(node.id()), null), output);
            }
            // Per-node error branch (Tier-3): a node wired with an
            // "error" route does not fail the run - the run continues
            // down that edge carrying a {error, failedNode*, input}
            // envelope. No error route = exact old behavior (the
            // outer catch in the engine fails the run), so nothing about an
            // existing graph changes.
            String errorTarget = WorkflowGraphSupport.errorEdgeTarget(node, s.edgesOf(node.id()));
            if (errorTarget == null) {
                throw nodeFailure;
            }
            Instant failedAt = Instant.now();
            trace.add(recorder.withRunData(trace, new NodeTraceEntry(node.id(), node.type(), node.label(), "ERROR",
                "Node failed and was routed to its error branch: " + nodeFailure.getMessage(),
                startedAt, failedAt, List.of(String.valueOf(nodeFailure.getMessage()))), node, currentOutput, null,
                NodeTracePayloads.MAX_FIELD_CHARS, s.runId));
            recorder.persist(s);
            recorder.nodeCompleted(s, node, "ERROR");
            return ContainerStep.Outcome.next(
                errorTarget, graphSupport.errorOutputJson(node, nodeFailure.getMessage(), currentOutput));
        }
        traceRetriedAttempts(trace, node, nodeInput, retriedAttempts, strategyRetry, false);
        trace.add(recorder.withRunData(trace, new NodeTraceEntry(
            node.id(), node.type(), node.label(), result.status(), result.summary(), startedAt, Instant.now(),
            result.logLines()), node, currentOutput, result.output(), NodeTracePayloads.MAX_FIELD_CHARS, s.runId));
        s.captureFile(result);

        // Real-time log viewer (#393): persist the trace-so-far after
        // every node, not just once at the very end - this is the one
        // change that makes a concurrent GET .../runs/{runId} poll see
        // genuine incremental progress instead of nothing until the
        // whole (still-synchronous) run finishes. Still the same
        // request thread as before - no async/threading change, so no
        // new TenantContext propagation concern.
        recorder.persist(s);
        recorder.nodeCompleted(s, node, result.status());

        // onError=continue also covers a node that REPORTS failure (FAILED halt) instead of throwing.
        if (result.haltRun() == WorkflowRunStatus.FAILED && strategy != null
            && strategy.onError() == NodeErrorStrategy.OnError.CONTINUE) {
            String output = NodeErrorStrategy.continueOutput(objectMapper, node, result.summary());
            kit.recordContainerOutput(s, node, output);
            return ContainerStep.Outcome.next(graphSupport.nextNodeId(node, s.edgesOf(node.id()), null), output);
        }

        // A FAILED halt with an error edge routes instead of
        // completing - the result-based counterpart of the exception
        // routing above (a node that reports failure rather than
        // throwing deserves the same branch).
        if (result.haltRun() == WorkflowRunStatus.FAILED) {
            String errorTarget = WorkflowGraphSupport.errorEdgeTarget(node, s.edgesOf(node.id()));
            if (errorTarget != null) {
                return ContainerStep.Outcome.next(
                    errorTarget, graphSupport.errorOutputJson(node, result.summary(), currentOutput));
            }
        }

        if (result.haltRun() != null) {
            return ContainerStep.Outcome.finished(kit.halted(s, result));
        }

        return ContainerStep.Outcome.next(
            graphSupport.nextNodeId(node, s.edgesOf(node.id()), result.branch()), result.output());
    }

    /** One ERROR step per retried attempt (and the irreversible-guard note on a final failure) - see {@link NodeErrorStrategy}. */
    private void traceRetriedAttempts(
        List<NodeTraceEntry> trace, WorkflowNodeDto node, String input, List<NodeErrorStrategy.Attempt> attempts,
        NodeErrorStrategy.Retry retry, boolean failedForGood
    ) {
        for (NodeTraceEntry entry : NodeErrorStrategy.attemptEntries(node, attempts, retry, failedForGood)) {
            trace.add(kit.recorder.withRunData(trace, entry, node, input, null, NodeTracePayloads.MAX_FIELD_CHARS, null));
        }
    }
}
