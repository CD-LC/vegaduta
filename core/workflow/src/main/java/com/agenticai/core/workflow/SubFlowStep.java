package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sub-flow (Tier-3): the honest answer to "loops only iterate one node". A sub_flow node's config carries an
 * embedded "body" graph ({nodes, edges}) plus the loop family's itemsField/maxIterations contract. Each item walks
 * the WHOLE body graph - multi-step bodies, conditions, error edges between body nodes - and the body's terminal
 * output is that item's result. Every existing bound still holds: iterations charge against maxStepsPerRun one
 * step per executed BODY node, cancellation is checked per item, retry/error semantics come free via {@link
 * NodeAttemptExecutor} and errorEdgeTarget. Honest limitation, same as the loop family's approval caveat: an
 * approval/human-input halt inside the body completes the run with that status, but resume re-enters at the MAIN
 * graph - the body's own position is not replayed.
 */
final class SubFlowStep implements ContainerStep {

    private final TraversalKit kit;

    SubFlowStep(TraversalKit kit) {
        this.kit = kit;
    }

    @Override
    public String nodeType() {
        return "sub_flow";
    }

    @Override
    public Outcome run(RunTraversalState s, WorkflowNodeDto node, String currentOutput) {
        WorkflowGraphSupport graphSupport = kit.graphSupport;
        WorkflowGraphDto bodyGraph = graphSupport.readSubFlowBody(node);
        Map<String, WorkflowNodeDto> bodyById = new LinkedHashMap<>();
        for (WorkflowNodeDto bodyNode : bodyGraph.nodes()) {
            bodyById.put(bodyNode.id(), bodyNode);
        }
        Map<String, List<WorkflowEdgeDto>> bodyOut = new LinkedHashMap<>();
        for (WorkflowEdgeDto edge : bodyGraph.edges()) {
            bodyOut.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
        }
        WorkflowNodeDto bodyEntry = graphSupport.resolveSubFlowEntry(node, bodyGraph, bodyById, bodyOut);

        List<String> items = graphSupport.resolveLoopItems(node, currentOutput);
        int maxIterations = graphSupport.loopMaxIterations(node);
        int iterations = Math.min(items.size(), maxIterations);
        List<String> collected = new ArrayList<>();
        Instant subFlowStarted = Instant.now();
        WorkflowNodeExecutor.NodeExecutionResult haltResult = null;

        for (int i = 0; i < iterations; i++) {
            if (kit.cancellationRequested(s)) {
                return Outcome.finished(kit.cancelled(s, node, " during sub-flow item " + (i + 1) + "/" + iterations));
            }
            String itemOutput = items.get(i);
            String bodyCurrent = bodyEntry.id();
            Set<String> bodyVisited = new HashSet<>();
            while (bodyCurrent != null) {
                s.chargeStep();
                // Body-graph cycles (a body node wired back to an ancestor) terminate rather than spin forever.
                if (!bodyVisited.add(bodyCurrent)) {
                    break;
                }
                WorkflowNodeDto bodyNode = bodyById.get(bodyCurrent);
                if (bodyNode == null) {
                    throw new WorkflowValidationException(List.of(
                        "Sub-flow body edge points at missing node '" + bodyCurrent + "'."));
                }
                Instant bodyStarted = Instant.now();
                s.active(bodyNode.id(), itemOutput);
                WorkflowNodeExecutor.NodeExecutionResult r;
                try {
                    r = kit.attempts.execute(s.definition, s.runIdText(), bodyNode, itemOutput, s.requestedBy,
                        List.of(), ExpressionScope.iteration(items.get(i), i, items), s.mode);
                } catch (RuntimeException bodyFailure) {
                    // Error edges inside the body route inside the body - the body graph is a graph, so the same
                    // contract as the main traversal applies one level down.
                    String errTarget = WorkflowGraphSupport.errorEdgeTarget(
                        bodyNode, bodyOut.getOrDefault(bodyNode.id(), List.of()));
                    if (errTarget == null) {
                        throw bodyFailure;
                    }
                    kit.recorder.add(s, new NodeTraceEntry(bodyNode.id(), bodyNode.type(),
                        bodyNode.label() + " (sub-flow " + (i + 1) + "/" + iterations + ")", "ERROR",
                        "Body node failed and was routed to its error branch: " + bodyFailure.getMessage(),
                        bodyStarted, Instant.now(), List.of(String.valueOf(bodyFailure.getMessage()))),
                        bodyNode, itemOutput, null, NodeTracePayloads.MAX_ITERATION_FIELD_CHARS, null);
                    itemOutput = graphSupport.errorOutputJson(bodyNode, bodyFailure.getMessage(), itemOutput);
                    bodyCurrent = errTarget;
                    continue;
                }
                kit.recorder.add(s, new NodeTraceEntry(bodyNode.id(), bodyNode.type(),
                    bodyNode.label() + " (sub-flow " + (i + 1) + "/" + iterations + ")",
                    r.status(), r.summary(), bodyStarted, Instant.now(), r.logLines()),
                    bodyNode, itemOutput, r.output(), NodeTracePayloads.MAX_ITERATION_FIELD_CHARS, s.runId);
                s.captureFile(r);
                // Same incremental-trace persistence as the main branch - a long sub-flow run is exactly where
                // the real-time viewer needs mid-run entries.
                kit.recorder.persist(s);
                kit.recorder.nodeCompleted(s, bodyNode, r.status());
                if (r.haltRun() != null) {
                    haltResult = r;
                    break;
                }
                itemOutput = r.output();
                bodyCurrent = graphSupport.nextNodeId(bodyNode, bodyOut.getOrDefault(bodyNode.id(), List.of()), r.branch());
            }
            if (haltResult != null) {
                break;
            }
            collected.add(itemOutput);
        }

        if (haltResult != null) {
            return Outcome.finished(kit.halted(s, haltResult));
        }

        String merged = graphSupport.writeJsonArray(collected);
        kit.recordContainerOutput(s, node, merged);
        kit.recorder.add(s, new NodeTraceEntry(node.id(), node.type(), node.label(), "OK",
            "Ran the sub-flow body over " + iterations + " of " + items.size() + " item(s)"
                + (items.size() > iterations ? " (capped at " + maxIterations + " by maxIterations)" : "") + ".",
            subFlowStarted, Instant.now(), List.of()), node, currentOutput, merged, NodeTracePayloads.MAX_FIELD_CHARS, null);
        kit.recorder.persist(s);

        // Unlike Loop/Fan-out (whose one edge IS the body), a sub_flow's body lives in config - so its outgoing
        // edge is the plain continuation. No edge = the run ends with the merged array as its final output.
        return Outcome.next(graphSupport.nextNodeId(node, s.edgesOf(node.id()), null), merged);
    }
}
