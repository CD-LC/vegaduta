package com.agenticai.core.workflow.temporal;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.workflow.ExpressionScope;
import com.agenticai.core.workflow.NodeTracePayloads;
import com.agenticai.core.workflow.WorkflowTraversalRules;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.workflow.Workflow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Loop / Iterator - same "repeat the next node per item" special-case as the in-process engine's
 * loop step. Every per-item node execution still goes through the {@code executeNode} activity
 * exactly like the single-node path, so it stays replay-safe: only the iteration bookkeeping itself
 * (list resolution, counting) runs in the workflow method, and that is pure, deterministic data
 * transformation with no I/O or wall clock reads.
 */
final class TemporalLoopStep implements TemporalStep {

    // Pure JSON parsing/serialization only (no I/O).
    private static final ObjectMapper OBJECT_MAPPER = JsonMappers.plain();

    @Override
    public Outcome run(TemporalRunState s, WorkflowNodeDto node, String currentOutput) {
        List<WorkflowEdgeDto> loopOutgoing = s.edgesOf(node.id());
        if (loopOutgoing.isEmpty()) {
            // Defense in depth - TemporalWorkflowExecutionEngine already calls
            // OrchestratorService.validate(graph)
            // before starting this workflow, which rejects a Loop node with no outgoing edge.
            throw new IllegalStateException(
                    "Loop node '" + node.label() + "' has no downstream node to repeat.");
        }
        WorkflowNodeDto bodyNode = s.nodesById.get(loopOutgoing.get(0).target());
        List<String> items =
                WorkflowTraversalRules.resolveLoopItems(OBJECT_MAPPER, node, currentOutput);
        int maxIterations = WorkflowTraversalRules.loopMaxIterations(node);
        int iterations = Math.min(items.size(), maxIterations);
        List<String> collected = new ArrayList<>();
        String lastBranch = null;
        NodeExecutionActivityResult haltResult = null;

        for (int i = 0; i < iterations; i++) {
            s.chargeStep();
            s.activeNodeId = bodyNode.id();
            NodeExecutionActivityResult r =
                    s.nodeActivities.executeNode(
                            s.nodeInput(
                                    bodyNode,
                                    items.get(i),
                                    ExpressionScope.iteration(items.get(i), i, items)));

            s.trace.add(
                    s.withPayloads(
                            new NodeTraceEntry(
                                    bodyNode.id(),
                                    bodyNode.type(),
                                    bodyNode.label() + " (loop " + (i + 1) + "/" + iterations + ")",
                                    r.status(),
                                    r.summary(),
                                    Instant.ofEpochMilli(r.startedAtEpochMilli()),
                                    Instant.ofEpochMilli(r.completedAtEpochMilli()),
                                    r.logLines()),
                            items.get(i),
                            r.output(),
                            NodeTracePayloads.MAX_ITERATION_FIELD_CHARS));
            s.captureFile(r);

            if (r.haltRun() != null) {
                haltResult = r;
                break;
            }

            s.recordProgress(bodyNode);

            collected.add(r.output());
            lastBranch = r.branch();
        }

        if (haltResult != null) {
            return Outcome.finished(s.halted(haltResult));
        }

        s.trace.add(
                new NodeTraceEntry(
                        node.id(),
                        node.type(),
                        node.label(),
                        "OK",
                        "Repeated '"
                                + bodyNode.label()
                                + "' "
                                + iterations
                                + " of "
                                + items.size()
                                + " item(s)"
                                + (items.size() > maxIterations
                                        ? " (capped at " + maxIterations + ")"
                                        : "")
                                + ".",
                        Instant.ofEpochMilli(Workflow.currentTimeMillis()),
                        Instant.ofEpochMilli(Workflow.currentTimeMillis()),
                        List.of()));

        s.recordProgress(node);

        String merged = WorkflowTraversalRules.writeJsonArray(OBJECT_MAPPER, collected);
        s.recordContainerOutput(node, merged);
        return Outcome.next(
                WorkflowTraversalRules.nextNodeIdFirstEdge(s.edgesOf(bodyNode.id()), lastBranch),
                merged);
    }
}
