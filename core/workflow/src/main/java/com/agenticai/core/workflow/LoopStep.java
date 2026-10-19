package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Loop / Iterator ("repeat the next node per item" - {@link WorkflowNodeType}'s javadoc): NOT a single-node
 * operation like every other type, so it is special-cased in the traversal rather than inside {@link
 * WorkflowNodeExecutor#executeNode} - only the traversal holds the graph's adjacency, which "find the node to
 * repeat" needs. No back edge is ever created (the body node's own next-hop is resolved normally once the loop
 * finishes), so the walk stays linear and OrchestratorService's cycle detection is untouched.
 */
final class LoopStep implements ContainerStep {

    private final TraversalKit kit;

    LoopStep(TraversalKit kit) {
        this.kit = kit;
    }

    @Override
    public String nodeType() {
        return "loop";
    }

    @Override
    public Outcome run(RunTraversalState s, WorkflowNodeDto node, String currentOutput) {
        WorkflowGraphSupport graphSupport = kit.graphSupport;
        List<WorkflowEdgeDto> loopOutgoing = s.edgesOf(node.id());
        if (loopOutgoing.isEmpty()) {
            // Defense in depth - OrchestratorService.validate() already rejects a Loop node with no outgoing
            // edge before a run can start at all.
            throw new WorkflowValidationException(List.of(
                "Loop node '" + node.label() + "' has no downstream node to repeat."));
        }
        WorkflowNodeDto bodyNode = s.nodesById.get(loopOutgoing.get(0).target());
        List<String> items = graphSupport.resolveLoopItems(node, currentOutput);
        int maxIterations = graphSupport.loopMaxIterations(node);
        int iterations = Math.min(items.size(), maxIterations);
        List<String> collected = new ArrayList<>();
        Instant loopStarted = Instant.now();
        String lastBranch = null;
        WorkflowNodeExecutor.NodeExecutionResult haltResult = null;
        // Per-node error branch on the BODY node (Tier-3): an item that exhausts retries routes the whole run
        // down the body node's error edge instead of failing the run - same contract as the main traversal.
        String[] errorRoute = null;

        for (int i = 0; i < iterations; i++) {
            s.chargeStep();
            // Same between-node Stop Run check as the main loop, at Loop-iteration granularity too - a stuck or
            // slow 25/100-iteration loop is exactly the scenario Stop Run exists for.
            if (kit.cancellationRequested(s)) {
                return Outcome.finished(kit.cancelled(s, bodyNode, " during loop iteration " + (i + 1) + "/" + iterations));
            }
            Instant iterStarted = Instant.now();
            s.active(bodyNode.id(), items.get(i));
            // Workflow Share mic/video - a Loop/Parallel node immediately after Trigger does not receive the
            // attachment; the expected shape is a single Agent node reading the camera frame directly.
            WorkflowNodeExecutor.NodeExecutionResult r;
            try {
                r = kit.attempts.execute(s.definition, s.runIdText(), bodyNode, items.get(i), s.requestedBy,
                    List.of(), ExpressionScope.iteration(items.get(i), i, items), s.mode);
            } catch (RuntimeException bodyFailure) {
                String errorTarget = WorkflowGraphSupport.errorEdgeTarget(bodyNode, s.edgesOf(bodyNode.id()));
                if (errorTarget == null) {
                    throw bodyFailure;
                }
                kit.recorder.add(s, new NodeTraceEntry(bodyNode.id(), bodyNode.type(),
                    bodyNode.label() + " (loop " + (i + 1) + "/" + iterations + ")", "ERROR",
                    "Item failed and the run was routed to the error branch: " + bodyFailure.getMessage(),
                    iterStarted, Instant.now(), List.of(String.valueOf(bodyFailure.getMessage()))),
                    bodyNode, items.get(i), null, NodeTracePayloads.MAX_ITERATION_FIELD_CHARS, null);
                errorRoute = new String[]{errorTarget,
                    graphSupport.errorOutputJson(bodyNode, bodyFailure.getMessage(), items.get(i))};
                break;
            }
            kit.recorder.add(s, new NodeTraceEntry(
                bodyNode.id(), bodyNode.type(), bodyNode.label() + " (loop " + (i + 1) + "/" + iterations + ")",
                r.status(), r.summary(), iterStarted, Instant.now(), r.logLines()),
                bodyNode, items.get(i), r.output(), NodeTracePayloads.MAX_ITERATION_FIELD_CHARS, s.runId);
            s.captureFile(r);
            kit.recorder.persist(s);
            kit.recorder.nodeCompleted(s, bodyNode, r.status());

            if (r.haltRun() != null) {
                // A FAILED halt with an error edge routes like an exception - see the main traversal branch.
                if (r.haltRun() == WorkflowRunStatus.FAILED) {
                    String errorTarget = WorkflowGraphSupport.errorEdgeTarget(bodyNode, s.edgesOf(bodyNode.id()));
                    if (errorTarget != null) {
                        errorRoute = new String[]{errorTarget,
                            graphSupport.errorOutputJson(bodyNode, r.summary(), items.get(i))};
                        break;
                    }
                }
                haltResult = r;
                break;
            }
            collected.add(r.output());
            lastBranch = r.branch();
        }

        if (errorRoute != null) {
            kit.recorder.persist(s);
            return Outcome.next(errorRoute[0], errorRoute[1]);
        }
        if (haltResult != null) {
            return Outcome.finished(kit.halted(s, haltResult));
        }

        String merged = graphSupport.writeJsonArray(collected);
        kit.recordContainerOutput(s, node, merged);
        kit.recorder.add(s, new NodeTraceEntry(node.id(), node.type(), node.label(), "OK",
            "Repeated '" + bodyNode.label() + "' " + iterations + " of " + items.size() + " item(s)"
                + (items.size() > maxIterations ? " (capped at " + maxIterations + ")" : "") + ".",
            loopStarted, Instant.now(), List.of()), node, currentOutput, merged, NodeTracePayloads.MAX_FIELD_CHARS, null);
        kit.recorder.persist(s);
        return Outcome.next(graphSupport.nextNodeId(bodyNode, s.edgesOf(bodyNode.id()), lastBranch), merged);
    }
}
