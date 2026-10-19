package com.agenticai.core.workflow.temporal;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.workflow.ExpressionScope;
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
 * Dynamic Fan-Out - mirrors the in-process engine's fan_out step (see {@code WorkflowNodeType}'s {@code FAN_OUT}
 * javadoc for the design: Loop's item resolution and single-worker graph shape, Parallel's concurrent execution,
 * both caps, pre-flight step charging).
 *
 * <p>Concurrency is the Temporal-shaped one: the in-process engine bounds it with a fixed thread pool, which would
 * mean nothing here (activity tasks run on the worker's own executor), so {@code maxConcurrency} is honored by
 * dispatching items in SLICES of that size via {@link Async#function} and joining each slice with {@link
 * Promise#allOf} before dispatching the next. That bounds the node's outstanding activity tasks at any instant, at
 * the accepted cost of a slice waiting on its slowest item. Slicing is pure arithmetic over the input list, so it
 * is replay-safe like every other decision here.
 */
final class TemporalFanOutStep implements TemporalStep {

    // Workflow.getVersion changeId for the Dynamic Fan-Out node - its own changeId, NOT a bumped maxSupported on
    // the parallel one, because the two changes are independent (a graph can contain either node type without the
    // other). Same forward-looking purpose as the parallel guard; unlike it, no recorded history can reach this
    // branch under DEFAULT_VERSION today, since a graph containing a fan_out node could not pass validation before.
    private static final String DYNAMIC_FAN_OUT_CHANGE_ID = "dynamic-fan-out-v1";

    private static final ObjectMapper OBJECT_MAPPER = JsonMappers.plain();

    @Override
    public Outcome run(TemporalRunState s, WorkflowNodeDto node, String currentOutput) {
        List<WorkflowEdgeDto> fanOutOutgoing = s.edgesOf(node.id());
        if (fanOutOutgoing.isEmpty()) {
            // Defense in depth - TemporalWorkflowExecutionEngine already calls OrchestratorService.validate(graph)
            // before starting this workflow, which rejects a Fan-out node without exactly one outgoing edge.
            throw new IllegalStateException(
                    "Fan-out node '" + node.label() + "' has no downstream worker node to run per item.");
        }
        WorkflowNodeDto workerNode = s.nodesById.get(fanOutOutgoing.get(0).target());
        // Same itemsField contract - and the same resolver - as Loop, matching the synchronous engine's reuse.
        List<String> items = WorkflowTraversalRules.resolveLoopItems(OBJECT_MAPPER, node, currentOutput);
        int maxItems = WorkflowTraversalRules.fanOutMaxItems(node);
        int fanOutWorkers = Math.min(items.size(), maxItems);

        // Pre-flight step charging with clamp-to-budget, identical to the synchronous engine's fan_out step
        // (truncate with a visible reason beats Loop's fail-at-item-N; an empty budget with items pending is the
        // one case that throws).
        int remainingSteps = s.input.maxStepsPerRun() - s.steps;
        if (fanOutWorkers > 0 && remainingSteps <= 0) {
            throw s.stepLimit();
        }
        boolean budgetCapped = fanOutWorkers > remainingSteps;
        fanOutWorkers = Math.min(fanOutWorkers, Math.max(remainingSteps, 0));
        s.steps += fanOutWorkers;

        int fanOutVersion = Workflow.getVersion(DYNAMIC_FAN_OUT_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
        if (fanOutVersion == Workflow.DEFAULT_VERSION) {
            throw new IllegalStateException(
                    "Fan-out node '"
                            + node.label()
                            + "' is not yet supported on this execution's recorded"
                            + " history version. Re-run the workflow to pick up the"
                            + " current engine version.");
        }

        s.activeNodeId = node.id();
        long fanOutStartedMillis = Workflow.currentTimeMillis();
        int concurrency =
                Math.min(WorkflowTraversalRules.fanOutConcurrency(node), Math.max(fanOutWorkers, 1));
        List<NodeExecutionActivityResult> itemResults = new ArrayList<>();
        for (int sliceStart = 0; sliceStart < fanOutWorkers; sliceStart += concurrency) {
            int sliceEnd = Math.min(sliceStart + concurrency, fanOutWorkers);
            List<Promise<NodeExecutionActivityResult>> slicePromises = new ArrayList<>();
            for (int i = sliceStart; i < sliceEnd; i++) {
                NodeExecutionActivityInput itemInput =
                        s.nodeInput(workerNode, items.get(i), ExpressionScope.iteration(items.get(i), i, items));
                slicePromises.add(Async.function(s.nodeActivities::executeNode, itemInput));
            }
            // Same first-failure semantics as the Parallel join: an uncaught failure from any item in this slice
            // propagates out of the whole Fan-out node, attributed to it via activeNodeId; queued LATER slices
            // are never dispatched. A Stop Run request surfaces here as CanceledFailure from the blocked get()
            // and is handled by the workflow's shared catch - cancellation granularity is per slice here, vs
            // per queued item on the synchronous engine; both leave already-running items to finish.
            Promise.allOf(slicePromises).get();
            for (Promise<NodeExecutionActivityResult> promise : slicePromises) {
                itemResults.add(promise.get());
            }
        }
        // Progress is recorded once after the whole fan-out, not per slice (matching Parallel's single post-join
        // write rather than Loop's per-iteration one): recordProgress is itself an activity, and one extra
        // activity per slice would grow the event history for a live-log refinement nobody has asked for.

        NodeExecutionActivityResult fanOutHalt = null;
        List<String> collected = new ArrayList<>();
        String lastBranch = null;
        for (int i = 0; i < itemResults.size(); i++) {
            NodeExecutionActivityResult r = itemResults.get(i);
            s.trace.add(
                    s.withPayloads(
                            new NodeTraceEntry(
                                    workerNode.id(),
                                    workerNode.type(),
                                    workerNode.label() + " (fan-out " + (i + 1) + "/" + itemResults.size() + ")",
                                    r.status(),
                                    r.summary(),
                                    Instant.ofEpochMilli(r.startedAtEpochMilli()),
                                    Instant.ofEpochMilli(r.completedAtEpochMilli()),
                                    r.logLines()),
                            items.get(i),
                            r.output(),
                            NodeTracePayloads.MAX_ITERATION_FIELD_CHARS));
            s.captureFile(r);
            if (fanOutHalt == null && r.haltRun() != null) {
                fanOutHalt = r;
            }
            if (r.haltRun() == null) {
                collected.add(r.output());
                lastBranch = r.branch();
            }
        }

        if (fanOutHalt != null) {
            return Outcome.finished(s.halted(fanOutHalt));
        }

        // Same cap-attribution phrasing as the synchronous engine - an author whose list was truncated needs to
        // know WHICH ceiling did it.
        String capNote =
                budgetCapped
                        ? " (truncated to the run's remaining "
                                + fanOutWorkers
                                + "-step budget - see this workflow's maxStepsPerRun)"
                        : (items.size() > maxItems
                                ? " (capped at " + maxItems + " by this node's maxItems)"
                                : "");
        s.trace.add(
                new NodeTraceEntry(
                        node.id(),
                        node.type(),
                        node.label(),
                        "OK",
                        "Fanned out '"
                                + workerNode.label()
                                + "' across "
                                + fanOutWorkers
                                + " of "
                                + items.size()
                                + " item(s), "
                                + concurrency
                                + " at a time"
                                + capNote
                                + ".",
                        Instant.ofEpochMilli(fanOutStartedMillis),
                        Instant.ofEpochMilli(Workflow.currentTimeMillis()),
                        List.of()));

        s.recordProgress(node);

        // Item-order merge; next hop off the single worker node, exactly like Loop's.
        String merged = WorkflowTraversalRules.writeJsonArray(OBJECT_MAPPER, collected);
        s.recordContainerOutput(node, merged);
        return Outcome.next(
                WorkflowTraversalRules.nextNodeIdFirstEdge(s.edgesOf(workerNode.id()), lastBranch), merged);
    }
}
