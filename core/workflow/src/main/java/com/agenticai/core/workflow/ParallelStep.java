package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Parallel Fan-Out (Master Roadmap 2026 item #12): like Loop, NOT a single-node operation, so it is special-cased
 * in the traversal - only the traversal holds the graph's adjacency, which "find every branch node" needs. Each
 * outgoing edge names one branch node (a single node per branch, the same bounded-scope choice Loop makes for its
 * body); every branch runs CONCURRENTLY against the same input (a genuine fan-OUT, not a pipeline) on {@link
 * ParallelBranchRunner}, which carries the tenant and trigger ThreadLocals across the hop.
 */
final class ParallelStep implements ContainerStep {

    private final TraversalKit kit;
    private final ParallelBranchRunner runner;

    ParallelStep(TraversalKit kit, ParallelBranchRunner runner) {
        this.kit = kit;
        this.runner = runner;
    }

    @Override
    public String nodeType() {
        return "parallel";
    }

    @Override
    public Outcome run(RunTraversalState s, WorkflowNodeDto node, String currentOutput) {
        WorkflowGraphSupport graphSupport = kit.graphSupport;
        List<WorkflowEdgeDto> branchEdges = s.edgesOf(node.id());
        if (branchEdges.isEmpty()) {
            // Defense in depth - OrchestratorService.validate() already rejects a Parallel node with no outgoing
            // edges before a run can start at all.
            throw new WorkflowValidationException(List.of(
                "Parallel node '" + node.label() + "' has no downstream branches to fan out to."));
        }
        List<WorkflowNodeDto> branchNodes = new ArrayList<>();
        for (WorkflowEdgeDto edge : branchEdges) {
            WorkflowNodeDto branchNode = s.nodesById.get(edge.target());
            if (branchNode != null) {
                branchNodes.add(branchNode);
            }
        }
        int maxBranches = WorkflowExecutionEngine.MAX_PARALLEL_BRANCHES;
        if (branchNodes.size() > maxBranches) {
            throw new WorkflowValidationException(List.of(
                "Parallel node '" + node.label() + "' has " + branchNodes.size()
                    + " branches, more than the " + maxBranches + " this engine allows in one fan-out."));
        }
        if (s.steps + branchNodes.size() > s.definition.getMaxStepsPerRun()) {
            throw new WorkflowStepLimitExceededException(s.definition.getMaxStepsPerRun());
        }
        s.steps += branchNodes.size();

        String fanOutInput = currentOutput;
        Instant fanOutStarted = Instant.now();
        // Workflow Share mic/video - a Loop/Parallel node immediately after Trigger does not receive the
        // attachment; a voice/photo-triggered workflow uses a single Agent node.
        ParallelBranchRunner.Joined joined = runner.run(branchNodes, branchNode -> kit.attempts.execute(
            s.definition, s.runIdText(), branchNode, fanOutInput, s.requestedBy, List.of(), s.mode));
        List<ParallelBranchRunner.Outcome> outcomes = joined.outcomes();
        RuntimeException branchFailure = joined.failure();
        WorkflowNodeDto failedBranch = joined.failedBranch();
        // Per-node error branch on the failed BRANCH node (Tier-3) - same routing contract as the main
        // traversal, loop-body and fan-out worker paths.
        if (branchFailure != null) {
            String errorTarget = failedBranch == null ? null
                : WorkflowGraphSupport.errorEdgeTarget(failedBranch, s.edgesOf(failedBranch.id()));
            if (errorTarget == null) {
                throw branchFailure;
            }
            String detail = branchFailure.getCause() != null
                ? String.valueOf(branchFailure.getCause().getMessage())
                : String.valueOf(branchFailure.getMessage());
            kit.recorder.add(s, new NodeTraceEntry(failedBranch.id(), failedBranch.type(), failedBranch.label(), "ERROR",
                "Parallel branch failed and the run was routed to the error branch: " + detail,
                fanOutStarted, Instant.now(), List.of(detail)),
                failedBranch, fanOutInput, null, NodeTracePayloads.MAX_ITERATION_FIELD_CHARS, null);
            kit.recorder.persist(s);
            return Outcome.next(errorTarget, graphSupport.errorOutputJson(failedBranch, detail, fanOutInput));
        }

        ParallelBranchRunner.Outcome haltOutcome = null;
        List<String> collected = new ArrayList<>();
        WorkflowNodeDto lastBranchNode = null;
        String lastBranchHandle = null;
        for (int i = 0; i < outcomes.size(); i++) {
            ParallelBranchRunner.Outcome outcome = outcomes.get(i);
            WorkflowNodeExecutor.NodeExecutionResult r = outcome.result();
            kit.recorder.add(s, new NodeTraceEntry(
                outcome.node().id(), outcome.node().type(),
                outcome.node().label() + " (parallel " + (i + 1) + "/" + outcomes.size() + ")",
                r.status(), r.summary(), fanOutStarted, Instant.now(), r.logLines()),
                outcome.node(), fanOutInput, r.output(), NodeTracePayloads.MAX_ITERATION_FIELD_CHARS, null);
            s.captureFile(r);
            if (haltOutcome == null && r.haltRun() != null) {
                haltOutcome = outcome;
            }
            if (r.haltRun() == null) {
                collected.add(r.output());
                lastBranchNode = outcome.node();
                lastBranchHandle = r.branch();
            }
        }
        kit.recorder.persist(s);
        for (ParallelBranchRunner.Outcome outcome : outcomes) {
            kit.recorder.nodeCompleted(s, outcome.node(), outcome.result().status());
        }

        if (haltOutcome != null) {
            return Outcome.finished(kit.halted(s, haltOutcome.result()));
        }

        String merged = graphSupport.writeJsonArray(collected);
        kit.recordContainerOutput(s, node, merged);
        kit.recorder.add(s, new NodeTraceEntry(node.id(), node.type(), node.label(), "OK",
            "Fanned out to " + outcomes.size() + " branch(es), all completed.",
            fanOutStarted, Instant.now(), List.of()), node, fanOutInput, merged, NodeTracePayloads.MAX_FIELD_CHARS, null);
        kit.recorder.persist(s);

        // Join point: every branch is expected, by convention (see WorkflowNodeType's javadoc), to converge on
        // the same downstream node - resolved here off the LAST non-halting branch rather than requiring every
        // branch's own next-hop to agree at runtime. A graph that fans out to genuinely different downstream
        // nodes still runs; it continues from whichever branch resolved last (accepted, documented limitation).
        String next = lastBranchNode == null ? null
            : graphSupport.nextNodeId(lastBranchNode, s.edgesOf(lastBranchNode.id()), lastBranchHandle);
        return Outcome.next(next, merged);
    }
}
