package com.agenticai.core.workflow;

import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;

/**
 * Dispatches a Parallel node's branches concurrently and joins them in COMPLETION order, so a branch
 * that fails early fails the join early - joining in index order meant a failure in the last branch
 * surfaced only after every slower earlier branch had finished. Owns the one thread-safety obligation
 * of this construct: the tenant and reply-to-sender ThreadLocals are snapshotted on the calling thread
 * and re-applied (then cleared) inside each branch, or a branch would run in no tenant (fails closed)
 * or a pooled thread's leftover one.
 */
final class ParallelBranchRunner {

    /** One branch's node identity and result, paired so the post-join code needs no second lookup. */
    record Outcome(WorkflowNodeDto node, WorkflowNodeExecutor.NodeExecutionResult result) {}

    /** Joined branches; {@code failure} / {@code failedBranch} are set when a branch threw (the first, in completion order). */
    record Joined(List<Outcome> outcomes, RuntimeException failure, WorkflowNodeDto failedBranch) {}

    /**
     * One finished branch: either its {@link Outcome} or the {@link RuntimeException} it threw. The error
     * rides through the completion queue instead of the Future so the failed branch's identity survives -
     * {@code ExecutorCompletionService#take()} hands back a bare Future with nothing to name the branch.
     */
    private record BranchResult(WorkflowNodeDto node, Outcome outcome, RuntimeException error) {}

    /** Executes one branch node on the worker thread. */
    @FunctionalInterface
    interface BranchCall {
        WorkflowNodeExecutor.NodeExecutionResult execute(WorkflowNodeDto branchNode);
    }

    private final Executor executor;

    ParallelBranchRunner(Executor executor) {
        this.executor = executor;
    }

    Joined run(List<WorkflowNodeDto> branchNodes, BranchCall call) {
        Optional<TenantContext> tenantSnapshot = TenantContext.current();
        Optional<InboundTriggerContext> triggerSnapshot = WorkflowTriggerContextHolder.current();
        ExecutorCompletionService<BranchResult> completion = new ExecutorCompletionService<>(executor);
        List<Future<BranchResult>> futures = new ArrayList<>();
        for (WorkflowNodeDto branchNode : branchNodes) {
            futures.add(completion.submit(() -> {
                tenantSnapshot.ifPresent(TenantContext::set);
                triggerSnapshot.ifPresent(WorkflowTriggerContextHolder::set);
                try {
                    return new BranchResult(branchNode, new Outcome(branchNode, call.execute(branchNode)), null);
                } catch (RuntimeException e) {
                    return new BranchResult(branchNode, null, e);
                } finally {
                    WorkflowTriggerContextHolder.clear();
                    if (tenantSnapshot.isPresent()) {
                        TenantContext.clear();
                    }
                }
            }));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            BranchResult finished;
            try {
                finished = completion.take().get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(false));
                return new Joined(outcomes, new CompletionException(ie), null);
            } catch (ExecutionException ee) {
                futures.forEach(f -> f.cancel(false));
                return new Joined(outcomes,
                    ee.getCause() instanceof RuntimeException re ? re : new CompletionException(ee), null);
            }
            if (finished.error() != null) {
                // A queued sibling is told not to start; a running one cannot be un-run - same position
                // the old index-order join left it in, just noticed sooner.
                futures.forEach(f -> f.cancel(false));
                return new Joined(outcomes, new CompletionException(finished.error()), finished.node());
            }
            outcomes.add(finished.outcome());
        }
        return new Joined(outcomes, null, null);
    }
}
