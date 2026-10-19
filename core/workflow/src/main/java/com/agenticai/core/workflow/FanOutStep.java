package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic Fan-Out - the missing cell of the runtime-sized/concurrent 2x2 (see {@link WorkflowNodeType}'s
 * {@code FAN_OUT} javadoc for the design rationale): Loop's item resolution plus a bounded version of Parallel's
 * concurrent execution, over ONE worker node (Loop's graph shape, deliberately - the item set is runtime data,
 * not edges, so the post-fan-in next hop is unambiguous). Special-cased in the traversal for the same reason Loop
 * and Parallel are: only the traversal holds the graph's adjacency.
 */
final class FanOutStep implements ContainerStep {

    private final TraversalKit kit;
    private final FanOutRunner runner;

    FanOutStep(TraversalKit kit, FanOutRunner runner) {
        this.kit = kit;
        this.runner = runner;
    }

    @Override
    public String nodeType() {
        return "fan_out";
    }

    @Override
    public Outcome run(RunTraversalState s, WorkflowNodeDto node, String currentOutput) {
        WorkflowGraphSupport graphSupport = kit.graphSupport;
        List<WorkflowEdgeDto> fanOutOutgoing = s.edgesOf(node.id());
        if (fanOutOutgoing.isEmpty()) {
            // Defense in depth - OrchestratorService.validate() already rejects a Fan-out node without exactly
            // one outgoing edge before a run can start at all.
            throw new WorkflowValidationException(List.of(
                "Fan-out node '" + node.label() + "' has no downstream worker node to run per item."));
        }
        WorkflowNodeDto workerNode = s.nodesById.get(fanOutOutgoing.get(0).target());
        // Same itemsField contract - and the same resolver - as Loop, on purpose: a second, differently-spelled
        // way to answer "where is the array in my input" would be gratuitous divergence between two constructs
        // whose only real difference is sequential-vs-concurrent execution.
        List<String> items = graphSupport.resolveLoopItems(node, currentOutput);
        int maxItems = graphSupport.fanOutMaxItems(node);
        int workers = Math.min(items.size(), maxItems);

        // Step budget: charged PRE-FLIGHT like Parallel, not per-item-after-the-fact like Loop - Loop's post-hoc
        // charging means a 60-item list under the default 50-step budget runs ~49 items and then FAILS the whole
        // run at item 50, throwing away work already done. Here the worked set is clamped to the remaining
        // budget up front and the truncation is reported in this node's own trace summary instead. The one case
        // that still throws: a non-empty item list with zero budget left.
        int remainingSteps = s.definition.getMaxStepsPerRun() - s.steps;
        if (workers > 0 && remainingSteps <= 0) {
            throw new WorkflowStepLimitExceededException(s.definition.getMaxStepsPerRun());
        }
        boolean budgetCapped = workers > remainingSteps;
        workers = Math.min(workers, Math.max(remainingSteps, 0));
        s.steps += workers;

        Instant fanOutStarted = Instant.now();
        s.activeNodeId = node.id();

        // Dedicated bounded daemon pool, tenant/trigger ThreadLocal snapshots and item-granular Stop Run all
        // live in FanOutRunner.
        int concurrency = Math.min(graphSupport.fanOutConcurrency(node), Math.max(workers, 1));
        FanOutRunner.Joined fanOutJoined = runner.run(s.runIdText(), items, workers, concurrency,
            () -> kit.cancellationRequested(s),
            (item, scope) -> kit.attempts.execute(
                s.definition, s.runIdText(), workerNode, item, s.requestedBy, List.of(), scope, s.mode));
        RuntimeException itemFailure = fanOutJoined.failure();
        // Per-node error branch on the WORKER node (Tier-3): an item that exhausts retries routes the run down
        // the worker's error edge rather than failing the whole run.
        if (itemFailure != null) {
            String errorTarget = WorkflowGraphSupport.errorEdgeTarget(workerNode, s.edgesOf(workerNode.id()));
            if (errorTarget == null) {
                throw itemFailure;
            }
            String detail = itemFailure.getCause() != null
                ? String.valueOf(itemFailure.getCause().getMessage())
                : String.valueOf(itemFailure.getMessage());
            kit.recorder.add(s, new NodeTraceEntry(workerNode.id(), workerNode.type(), workerNode.label(), "ERROR",
                "A fan-out item failed and the run was routed to the error branch: " + detail,
                fanOutStarted, Instant.now(), List.of(detail)),
                workerNode, currentOutput, null, NodeTracePayloads.MAX_ITERATION_FIELD_CHARS, null);
            kit.recorder.persist(s);
            return Outcome.next(errorTarget, graphSupport.errorOutputJson(workerNode, detail, currentOutput));
        }
        List<FanOutRunner.ItemOutcome> outcomes = fanOutJoined.outcomes();

        WorkflowNodeExecutor.NodeExecutionResult haltResult = null;
        boolean cancelledMidFanOut = false;
        List<String> collected = new ArrayList<>();
        String lastBranch = null;
        int executed = 0;
        for (int i = 0; i < outcomes.size(); i++) {
            FanOutRunner.ItemOutcome outcome = outcomes.get(i);
            if (outcome.cancelledBeforeStart()) {
                // No per-item trace entry for an item that never ran - the cancelled finalisation's own entry is
                // the single, honest record of why the tail of the item set is missing from the trace.
                cancelledMidFanOut = true;
                continue;
            }
            executed++;
            WorkflowNodeExecutor.NodeExecutionResult r = outcome.result();
            kit.recorder.add(s, new NodeTraceEntry(
                workerNode.id(), workerNode.type(),
                workerNode.label() + " (fan-out " + (i + 1) + "/" + outcomes.size() + ")",
                r.status(), r.summary(), outcome.startedAt(), outcome.completedAt(), r.logLines()),
                workerNode, items.get(i), r.output(), NodeTracePayloads.MAX_ITERATION_FIELD_CHARS, null);
            s.captureFile(r);
            if (haltResult == null && r.haltRun() != null) {
                haltResult = r;
            }
            if (r.haltRun() == null) {
                collected.add(r.output());
                lastBranch = r.branch();
            }
        }
        kit.recorder.persist(s);
        for (FanOutRunner.ItemOutcome outcome : outcomes) {
            if (!outcome.cancelledBeforeStart()) {
                kit.recorder.nodeCompleted(s, workerNode, outcome.result().status());
            }
        }

        // Cancellation outranks a halt: a user who clicked Stop mid-fan-out asked for CANCELLED, not for whatever
        // terminal status a concurrently-halting item happened to produce.
        if (cancelledMidFanOut) {
            return Outcome.finished(kit.cancelled(s, workerNode,
                " during fan-out after " + executed + "/" + outcomes.size() + " item(s)"));
        }
        if (haltResult != null) {
            return Outcome.finished(kit.halted(s, haltResult));
        }

        // Loop's honest "N of M (capped)" phrasing, extended to name WHICH cap bit - an author whose 80-item list
        // worked only 25 items needs to know whether to raise this node's maxItems or maxStepsPerRun.
        String capNote = budgetCapped
            ? " (truncated to the run's remaining " + workers + "-step budget - see this workflow's maxStepsPerRun)"
            : (items.size() > maxItems ? " (capped at " + maxItems + " by this node's maxItems)" : "");
        String merged = graphSupport.writeJsonArray(collected);
        kit.recordContainerOutput(s, node, merged);
        kit.recorder.add(s, new NodeTraceEntry(node.id(), node.type(), node.label(), "OK",
            "Fanned out '" + workerNode.label() + "' across " + workers + " of " + items.size()
                + " item(s), " + concurrency + " at a time" + capNote + ".",
            fanOutStarted, Instant.now(), List.of()), node, currentOutput, merged, NodeTracePayloads.MAX_FIELD_CHARS, null);
        kit.recorder.persist(s);

        // Item-order merge (same output shape as Loop and Parallel: a JSON array of the per-item outputs), and
        // the next hop resolved off the single worker node exactly like Loop's.
        return Outcome.next(graphSupport.nextNodeId(workerNode, s.edgesOf(workerNode.id()), lastBranch), merged);
    }
}
