package com.agenticai.core.workflow;

import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dispatches a Parallel node's branches concurrently and joins them in branch order. Owns the one
 * thread-safety obligation of this construct: the tenant and reply-to-sender ThreadLocals are snapshotted on
 * the calling thread and re-applied (then cleared) inside each branch, or a branch would run in no tenant
 * (fails closed) or a pooled thread's leftover one.
 */
final class ParallelBranchRunner {

    /** One branch's node identity and result, paired so the post-join code needs no second lookup. */
    record Outcome(WorkflowNodeDto node, WorkflowNodeExecutor.NodeExecutionResult result) {}

    /** Joined branches; {@code failure} / {@code failedBranch} are set when a branch threw (the first, in order). */
    record Joined(List<Outcome> outcomes, RuntimeException failure, WorkflowNodeDto failedBranch) {}

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
        return run(branchNodes, call, 0);
    }

    /**
     * @param joinTimeoutSeconds upper bound on the wait for ALL branches (0 or less = unbounded). On expiry the
     *     unfinished branches are cancelled and the first unfinished one (in branch order) is reported as
     *     failed with a {@link NodeAttemptExecutor.NodeStepTimeoutException}.
     *     On the first branch failure the still-running siblings are cancelled and interrupted instead of being
     *     left to run (and cause side effects) after the fan-out has already failed.
     */
    Joined run(List<WorkflowNodeDto> branchNodes, BranchCall call, long joinTimeoutSeconds) {
        Optional<TenantContext> tenantSnapshot = TenantContext.current();
        Optional<InboundTriggerContext> triggerSnapshot = WorkflowTriggerContextHolder.current();
        List<CompletableFuture<Outcome>> futures = new ArrayList<>();
        List<AtomicReference<Thread>> runners = new ArrayList<>();
        CompletableFuture<Void> firstFailure = new CompletableFuture<>();
        for (WorkflowNodeDto branchNode : branchNodes) {
            AtomicReference<Thread> runner = new AtomicReference<>();
            runners.add(runner);
            CompletableFuture<Outcome> future = CompletableFuture.supplyAsync(() -> {
                runner.set(Thread.currentThread());
                tenantSnapshot.ifPresent(TenantContext::set);
                triggerSnapshot.ifPresent(WorkflowTriggerContextHolder::set);
                try {
                    return new Outcome(branchNode, call.execute(branchNode));
                } finally {
                    runner.set(null);
                    WorkflowTriggerContextHolder.clear();
                    if (tenantSnapshot.isPresent()) {
                        TenantContext.clear();
                    }
                }
            }, executor);
            future.whenComplete((ok, err) -> {
                if (err != null && !(err instanceof CancellationException)) {
                    firstFailure.complete(null);
                }
            });
            futures.add(future);
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
        CompletableFuture<Object> settled = CompletableFuture.anyOf(all, firstFailure);
        boolean timedOut = false;
        try {
            if (joinTimeoutSeconds > 0) {
                settled.get(joinTimeoutSeconds, TimeUnit.SECONDS);
            } else {
                settled.get();
            }
        } catch (TimeoutException e) {
            timedOut = true;
        } catch (InterruptedException e) {
            cancelAll(futures, runners);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for parallel branches.", e);
        } catch (ExecutionException e) {
            // allOf completes exceptionally when a branch failed: handled below by inspecting each future.
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<Outcome> future = futures.get(i);
            if (future.isDone() && !future.isCompletedExceptionally()) {
                outcomes.add(future.join());
                continue;
            }
            if (future.isDone() && !future.isCancelled()) {
                cancelAll(futures, runners);
                try {
                    future.join();
                } catch (RuntimeException joinFailure) {
                    return new Joined(outcomes, joinFailure, branchNodes.get(i));
                }
            }
            if (timedOut && !future.isDone()) {
                cancelAll(futures, runners);
                return new Joined(outcomes,
                    new NodeAttemptExecutor.NodeStepTimeoutException(branchNodes.get(i), (int) joinTimeoutSeconds),
                    branchNodes.get(i));
            }
        }
        return new Joined(outcomes, null, null);
    }

    private static void cancelAll(List<CompletableFuture<Outcome>> futures, List<AtomicReference<Thread>> runners) {
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<Outcome> future = futures.get(i);
            if (!future.isDone()) {
                future.cancel(true);
                Thread t = runners.get(i).get();
                if (t != null) {
                    t.interrupt();
                }
            }
        }
    }
}
