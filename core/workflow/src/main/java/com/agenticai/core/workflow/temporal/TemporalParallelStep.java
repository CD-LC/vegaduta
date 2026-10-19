package com.agenticai.core.workflow.temporal;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.workflow.NodeTracePayloads;
import com.agenticai.core.workflow.WorkflowTraversalRules;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Parallel Fan-Out - mirrors the in-process engine's parallel step (single node per branch, every branch runs
 * against the same fan-out input, the join resolves the next hop off the LAST non-halting branch in edge order).
 * The concurrency primitive is the only thing that differs, and has to: the in-process engine uses plain
 * {@code CompletableFuture}s, which is exactly the non-deterministic, real-OS-thread concurrency a Temporal
 * Workflow method must never do (real threads race differently on every replay). {@link Async#function} schedules
 * the activity and returns a {@link Promise} immediately, so the fan-out loop stays single-threaded deterministic
 * workflow code; only the activity tasks run concurrently, on the worker's activity executor.
 */
final class TemporalParallelStep implements TemporalStep {

    // Mirrors WorkflowExecutionEngine.MAX_PARALLEL_BRANCHES (package-private to the workflow package): the same
    // defense-in-depth ceiling on how many branches one Parallel node can fan out to.
    private static final int MAX_PARALLEL_BRANCHES = 10;

    // Workflow.getVersion changeId for the Parallel-node fan-out. This was a genuine change to the workflow's
    // decision logic (previously an unconditional IllegalStateException; now a real fan-out). getVersion records a
    // marker in the execution's own history the first time it is reached, and returns maxSupported (1) from then
    // on. An execution whose history already contains this code region from BEFORE the change finds no marker and
    // gets Workflow.DEFAULT_VERSION, so replay retraces the OLD (throwing) branch exactly as originally executed
    // instead of tripping the non-determinism detector. Future changes to this fan-out logic follow the same
    // pattern: a new changeId, or a bumped maxSupported here.
    private static final String PARALLEL_FAN_OUT_CHANGE_ID = "parallel-fan-out-v1";

    private static final ObjectMapper OBJECT_MAPPER = JsonMappers.plain();

    @Override
    public Outcome run(TemporalRunState s, WorkflowNodeDto node, String currentOutput) {
        List<WorkflowEdgeDto> branchEdges = s.edgesOf(node.id());
        if (branchEdges.isEmpty()) {
            // Defense in depth - TemporalWorkflowExecutionEngine already calls OrchestratorService.validate(graph)
            // before starting this workflow, which rejects a Parallel node with no outgoing edge.
            throw new IllegalStateException(
                    "Parallel node '" + node.label() + "' has no downstream branches to fan out to.");
        }
        List<WorkflowNodeDto> branchNodes = new ArrayList<>();
        for (WorkflowEdgeDto edge : branchEdges) {
            WorkflowNodeDto branchNode = s.nodesById.get(edge.target());
            if (branchNode != null) {
                branchNodes.add(branchNode);
            }
        }
        if (branchNodes.size() > MAX_PARALLEL_BRANCHES) {
            // Defense in depth - same ceiling OrchestratorService enforces at save/activate time.
            throw new IllegalStateException(
                    "Parallel node '"
                            + node.label()
                            + "' has "
                            + branchNodes.size()
                            + " branches, more than the "
                            + MAX_PARALLEL_BRANCHES
                            + " this engine allows in one fan-out.");
        }
        if (s.steps + branchNodes.size() > s.input.maxStepsPerRun()) {
            throw s.stepLimit();
        }
        s.steps += branchNodes.size();

        int version = Workflow.getVersion(PARALLEL_FAN_OUT_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
        if (version == Workflow.DEFAULT_VERSION) {
            throw new IllegalStateException(
                    "Parallel node '"
                            + node.label()
                            + "' is not yet supported on the Temporal execution engine"
                            + " (app.workflow.execution-engine=temporal). Switch this"
                            + " workflow's execution engine to the default synchronous"
                            + " engine, or remove the Parallel node.");
        }

        s.activeNodeId = node.id();
        long fanOutStartedMillis = Workflow.currentTimeMillis();
        List<Promise<NodeExecutionActivityResult>> branchPromises = new ArrayList<>();
        for (WorkflowNodeDto branchNode : branchNodes) {
            NodeExecutionActivityInput branchInput = s.nodeInput(branchNode, currentOutput, null);
            branchPromises.add(Async.function(s.nodeActivities::executeNode, branchInput));
        }

        // Blocks this workflow's coroutine (not an OS thread) until every branch activity completes. Like the
        // in-process engine's join, Promise.allOf fails on the first branch failure it meets rather than
        // collecting partial failures: an uncaught RuntimeException from a branch (after Temporal's own retry
        // policy on the activity stub is exhausted) propagates out of the whole Parallel node, attributed to the
        // Parallel node itself via activeNodeId - an accepted parity gap with the legacy engine.
        Promise.allOf(branchPromises).get();

        NodeExecutionActivityResult haltOutcome = null;
        List<String> collected = new ArrayList<>();
        WorkflowNodeDto lastBranchNode = null;
        String lastBranchHandle = null;
        for (int i = 0; i < branchPromises.size(); i++) {
            NodeExecutionActivityResult r = branchPromises.get(i).get();
            WorkflowNodeDto branchNode = branchNodes.get(i);
            s.trace.add(
                    s.withPayloads(
                            new NodeTraceEntry(
                                    branchNode.id(),
                                    branchNode.type(),
                                    branchNode.label() + " (parallel " + (i + 1) + "/" + branchPromises.size() + ")",
                                    r.status(),
                                    r.summary(),
                                    Instant.ofEpochMilli(r.startedAtEpochMilli()),
                                    Instant.ofEpochMilli(r.completedAtEpochMilli()),
                                    r.logLines()),
                            currentOutput,
                            r.output(),
                            NodeTracePayloads.MAX_ITERATION_FIELD_CHARS));
            s.captureFile(r);
            if (haltOutcome == null && r.haltRun() != null) {
                haltOutcome = r;
            }
            if (r.haltRun() == null) {
                collected.add(r.output());
                lastBranchNode = branchNode;
                lastBranchHandle = r.branch();
            }
        }

        if (haltOutcome != null) {
            return Outcome.finished(s.halted(haltOutcome));
        }

        s.trace.add(
                new NodeTraceEntry(
                        node.id(),
                        node.type(),
                        node.label(),
                        "OK",
                        "Fanned out to " + branchPromises.size() + " branch(es), all completed.",
                        Instant.ofEpochMilli(fanOutStartedMillis),
                        Instant.ofEpochMilli(Workflow.currentTimeMillis()),
                        List.of()));

        s.recordProgress(node);

        String merged = WorkflowTraversalRules.writeJsonArray(OBJECT_MAPPER, collected);
        s.recordContainerOutput(node, merged);
        // Join point: same accepted, documented single-join-point limitation as the in-process engine - resolved
        // off the LAST non-halting branch in edge order, not a true multi-way join.
        String next =
                lastBranchNode == null
                        ? null
                        : WorkflowTraversalRules.nextNodeIdFirstEdge(
                                s.edgesOf(lastBranchNode.id()), lastBranchHandle);
        return Outcome.next(next, merged);
    }
}
