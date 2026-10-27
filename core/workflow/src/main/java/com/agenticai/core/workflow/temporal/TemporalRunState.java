package com.agenticai.core.workflow.temporal;

import com.agenticai.core.workflow.ExpressionScope;
import com.agenticai.core.workflow.NodeTracePayloads;
import com.agenticai.core.workflow.RunMode;
import com.agenticai.core.workflow.WorkflowIntelligenceSettings;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import io.temporal.workflow.Workflow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The traversal state of one {@link WorkflowRunWorkflowImpl#run} execution, shared with the
 * per-construct steps ({@link TemporalLoopStep}, {@link TemporalParallelStep}, {@link
 * TemporalFanOutStep}).
 *
 * <p><b>Determinism</b>: everything here is replay-safe workflow code. It holds plain data derived
 * from the immutable {@link WorkflowRunInput}, never reads a wall clock or random source except
 * through {@link Workflow} (steps do so via {@link Workflow#currentTimeMillis}), and every side
 * effect is an activity call (the two activity stubs it carries). The order of activity calls and
 * {@link Workflow#getVersion} markers made by the steps is the recorded history's contract: {@code
 * WorkflowRunWorkflowHistoryFixturesTest} replays histories recorded before the split and fails if
 * a step changes one.
 */
final class TemporalRunState {

    /**
     * Workflow.getVersion changeId for the recordContainerOutput activity after a loop / parallel /
     * fan_out node.
     */
    private static final String CONTAINER_OUTPUT_CHANGE_ID = "container-node-output-v1";

    final WorkflowRunInput input;
    final RunMode runMode;

    /** Executes nodes (heartbeating, retried per the run's policy). */
    final WorkflowNodeActivities nodeActivities;

    /**
     * Same stub configured with a single attempt, for nodes whose execution has an irreversible
     * side effect - the Temporal counterpart of {@link
     * com.agenticai.core.workflow.RetryPolicy#isRetryableNode}: a tool call or email send must not
     * be blind-retried by the activity policy into a duplicate effect.
     */
    final WorkflowNodeActivities nodeActivitiesOnce;

    /** Records progress and container outputs. */
    final WorkflowNodeActivities progressActivities;

    final Map<String, WorkflowNodeDto> nodesById = new HashMap<>();
    final Map<String, List<WorkflowEdgeDto>> outgoing = new HashMap<>();
    final List<NodeTraceEntry> trace = new ArrayList<>();
    String fileName;
    String fileData;

    /**
     * The node executing right now, so a failure is attributed to it and not to the last node that
     * finished.
     */
    String activeNodeId;

    int steps;

    TemporalRunState(
            WorkflowRunInput input,
            WorkflowNodeActivities nodeActivities,
            WorkflowNodeActivities nodeActivitiesOnce,
            WorkflowNodeActivities progressActivities) {
        this.input = input;
        this.runMode = RunMode.fromWire(input.runMode());
        this.nodeActivities = nodeActivities;
        this.nodeActivitiesOnce = nodeActivitiesOnce;
        this.progressActivities = progressActivities;
        WorkflowGraphDto graph = input.graph();
        for (WorkflowNodeDto node : graph.nodes()) {
            nodesById.put(node.id(), node);
        }
        for (WorkflowEdgeDto edge : graph.edges()) {
            outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
        }
    }

    List<WorkflowEdgeDto> edgesOf(String nodeId) {
        return outgoing.getOrDefault(nodeId, List.of());
    }

    /**
     * The activity stub {@code node} may be executed on: retried per the run policy for retryable
     * nodes, single-attempt for irreversible side-effect nodes. Pure selection (node type + its
     * {@code retryUnsafe} opt-in), so the choice is deterministic on replay.
     */
    WorkflowNodeActivities nodeActivitiesFor(WorkflowNodeDto node) {
        return com.agenticai.core.workflow.RetryPolicy.isRetryableNode(node)
                ? nodeActivities
                : nodeActivitiesOnce;
    }

    /** Charges one step; throws once {@code maxStepsPerRun} is crossed. */
    void chargeStep() {
        if (++steps > input.maxStepsPerRun()) {
            throw stepLimit();
        }
    }

    IllegalStateException stepLimit() {
        return new IllegalStateException(
                "Workflow exceeded the maximum of "
                        + input.maxStepsPerRun()
                        + " steps in one run.");
    }

    void captureFile(NodeExecutionActivityResult r) {
        if (r.fileName() != null) {
            fileName = r.fileName();
            fileData = r.fileData();
        }
    }

    /**
     * The activity input for running {@code node} on {@code nodeInput}; {@code scope} is the
     * iteration scope or null.
     */
    NodeExecutionActivityInput nodeInput(
            WorkflowNodeDto node, String nodeInput, ExpressionScope scope) {
        // A draft/test run stamps its mode on every node activity so the executor serves pins; a
        // production run
        // (or an input serialised before this field existed) resolves to null scopes, exactly as
        // before pins.
        return new NodeExecutionActivityInput(
                        input.workflowDefinitionId(),
                        input.workflowName(),
                        input.runId(),
                        node,
                        nodeInput,
                        input.requestedBy(),
                        input.tenantId(),
                        input.subject(),
                        input.roles(),
                        WorkflowIntelligenceSettings.defaults())
                .withScope(ExpressionScope.forMode(scope, runMode));
    }

    /**
     * Per-step run data - same sanitized input/output the synchronous engine attaches (see {@link
     * NodeTracePayloads}). Pure and deterministic, so safe in workflow code. No token/cost here:
     * the lookup is a DB read, which workflow code must not do, so usage stays null on this engine.
     */
    NodeTraceEntry withPayloads(
            NodeTraceEntry entry, String entryInput, String output, int perFieldCap) {
        return NodeTracePayloads.attach(
                entry, entryInput, output, perFieldCap, NodeTracePayloads.payloadCharsUsed(trace));
    }

    /**
     * Mid-run progress write after {@code node} (an activity: workflow code never touches the
     * database).
     */
    void recordProgress(WorkflowNodeDto node) {
        progressActivities.recordProgress(
                new RunProgressInput(
                        input.runId(),
                        input.workflowDefinitionId(),
                        input.workflowName(),
                        "RUNNING",
                        trace,
                        null,
                        fileName,
                        fileData,
                        false,
                        node.id(),
                        node.type(),
                        input.tenantId(),
                        input.subject(),
                        input.roles()));
    }

    void recordTerminal(WorkflowRunStatus status, String errorMessage) {
        progressActivities.recordProgress(
                new RunProgressInput(
                        input.runId(),
                        input.workflowDefinitionId(),
                        input.workflowName(),
                        status.name(),
                        trace,
                        errorMessage,
                        fileName,
                        fileData,
                        true,
                        null,
                        null,
                        input.tenantId(),
                        input.subject(),
                        input.roles()));
    }

    /** Writes the terminal row and builds the result the workflow returns. */
    WorkflowRunResult terminate(WorkflowRunStatus status, String message) {
        recordTerminal(status, message);
        return new WorkflowRunResult(status.name(), message, fileName, fileData);
    }

    /**
     * Ends the run at the node that was executing when it was cut short (Stop Run, or an uncaught
     * failure): one trace entry attributed to {@link #activeNodeId} - NOT to trace's last entry,
     * which is the last node that already finished successfully - then the terminal write. Stamped
     * with {@link Workflow#currentTimeMillis}, the replay-safe substitute for the wall clock.
     */
    WorkflowRunResult terminateAtActiveNode(
            WorkflowRunStatus status,
            String message,
            String entryStatus,
            String entrySummary,
            List<String> entryLogLines) {
        WorkflowNodeDto active = nodesById.get(activeNodeId);
        Instant now = Instant.ofEpochMilli(Workflow.currentTimeMillis());
        trace.add(
                new NodeTraceEntry(
                        activeNodeId,
                        active != null ? active.type() : "engine",
                        active != null ? active.label() : "Engine",
                        entryStatus,
                        entrySummary,
                        now,
                        now,
                        entryLogLines));
        return terminate(status, message);
    }

    /** A node (or container item) asked to end the run. */
    WorkflowRunResult halted(NodeExecutionActivityResult halting) {
        return terminate(WorkflowRunStatus.valueOf(halting.haltRun()), halting.summary());
    }

    /**
     * Stores a container node's merged output for {@code $node["<label>"]} (an activity: workflow
     * code never touches the database). Skipped on histories recorded before it existed.
     */
    void recordContainerOutput(WorkflowNodeDto node, String output) {
        if (Workflow.getVersion(CONTAINER_OUTPUT_CHANGE_ID, Workflow.DEFAULT_VERSION, 1) < 1) {
            return;
        }
        progressActivities.recordContainerOutput(
                new ContainerOutputInput(
                        input.workflowDefinitionId(),
                        input.workflowName(),
                        input.runId(),
                        input.requestedBy(),
                        node,
                        output,
                        input.tenantId(),
                        input.subject(),
                        input.roles()));
    }
}
