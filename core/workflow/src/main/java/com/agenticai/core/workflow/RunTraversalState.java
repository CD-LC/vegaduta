package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable state of ONE traversal leg of {@link WorkflowExecutionEngine}, shared by the main node loop and the
 * per-construct {@link ContainerStep}s (loop, parallel, fan_out, sub_flow, wait). It exists so those steps can
 * live in their own classes without a long parameter list: everything a step reads or advances (the step budget,
 * the trace, the file artifact, the node the failure handler should blame) is here.
 *
 * <p>Confined to the traversal thread: parallel and fan-out workers never touch it, their results are joined back
 * before any of it is written.
 */
final class RunTraversalState {

    final WorkflowDefinition definition;
    final WorkflowRun run;
    final List<NodeTraceEntry> trace;
    final String requestedBy;
    /** Read off the row, not passed down: a run resumed after an approval or pause keeps the mode it started in. */
    final RunMode mode;
    final UUID runId;
    final Map<String, WorkflowNodeDto> nodesById = new HashMap<>();
    final Map<String, List<WorkflowEdgeDto>> outgoing = new HashMap<>();
    /** {@code [fileName, fileData]} of the last Output(file) node, seeded from the row so a resume keeps it. */
    final String[] fileOutput;
    /** The node executing right now, so the failure handler blames it and not the last node that finished. */
    String activeNodeId = "unknown";
    /** The input the active node received, for the failure handler's trace entry. */
    String activeInput;
    int steps;

    RunTraversalState(
        WorkflowDefinition definition, WorkflowGraphDto graph, WorkflowRun run, List<NodeTraceEntry> trace,
        String requestedBy, int stepsAlreadyUsed
    ) {
        this.definition = definition;
        this.run = run;
        this.trace = trace;
        this.requestedBy = requestedBy;
        this.mode = run.getRunMode();
        this.runId = run.getId();
        this.steps = stepsAlreadyUsed;
        this.fileOutput = new String[]{run.getOutputFileName(), run.getOutputData()};
        for (WorkflowNodeDto node : graph.nodes()) {
            nodesById.put(node.id(), node);
        }
        for (WorkflowEdgeDto edge : graph.edges()) {
            outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
        }
    }

    String runIdText() {
        return runId.toString();
    }

    List<WorkflowEdgeDto> edgesOf(String nodeId) {
        return outgoing.getOrDefault(nodeId, List.of());
    }

    /** Charges one step against {@code maxStepsPerRun}; throws once the ceiling is crossed. */
    void chargeStep() {
        if (++steps > definition.getMaxStepsPerRun()) {
            throw new WorkflowStepLimitExceededException(definition.getMaxStepsPerRun());
        }
    }

    void captureFile(WorkflowNodeExecutor.NodeExecutionResult result) {
        if (result.fileName() != null) {
            fileOutput[0] = result.fileName();
            fileOutput[1] = result.fileData();
        }
    }

    void active(String nodeId, String input) {
        this.activeNodeId = nodeId;
        this.activeInput = input;
    }

    RunFinalizer.Leg leg() {
        return new RunFinalizer.Leg(run, definition, trace, fileOutput);
    }

    /** A leg view with no file artifact, as the pause / wait parks have always been written. */
    RunFinalizer.Leg legWithoutFile() {
        return new RunFinalizer.Leg(run, definition, trace, new String[]{null, null});
    }
}
