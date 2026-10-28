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
 * Dispatches a Parallel node's branches concurrently and joins them in COMPLETION order, so a
 * branch that fails early fails the join early - joining in index order meant a failure in the last
 * branch surfaced only after every slower earlier branch had finished. Owns the one thread-safety
 * obligation of this construct: the tenant and reply-to-sender ThreadLocals are snapshotted on the
 * calling thread and re-applied (then cleared) inside each branch, or a branch would run in no
 * tenant (fails closed) or a pooled thread's leftover one.
 */
final class ParallelBranchRunner {

    /**
     * One branch's node identity and result, paired so the post-join code needs no second lookup.
     */
    record Outcome(WorkflowNodeDto node, WorkflowNodeExecutor.NodeExecutionResult result) {}

    /**
     * Joined branches; {@code failure} / {@code failedBranch} are set when a branch threw (the
     * first, in completion order).
     */
    record Joined(List<Outcome> outcomes, RuntimeException failure, WorkflowNodeDto failedBranch) {}

    /**
     * One finished branch: either its {@link Outcome} or the {@link RuntimeException} it threw. The
     * error rides through the completion queue instead of the Future so the failed branch's
     * identity survives - {@code ExecutorCompletionService#take()} hands back a bare Future with
     * nothing to name the branch.
     */
    private record BranchResult(
            int index, WorkflowNodeDto node, Outcome outcome, RuntimeException error) {}

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
     * @param joinTimeoutSeconds upper bound on the wait for ALL branches (0 or less = unbounded). On
     *     expiry the unfinished branches are cancelled and interrupted and the first unfinished one
     *     (in branch order) is reported as failed with a {@link
     *     NodeAttemptExecutor.NodeStepTimeoutException}. A failed branch likewise interrupts its
     *     still-running siblings instead of leaving them to cause side effects after the fan-out
     *     has already failed.
     */
    Joined run(List<WorkflowNodeDto> branchNodes, BranchCall call, long joinTimeoutSeconds) {
        Optional<TenantContext> tenantSnapshot = TenantContext.current();
        Optional<InboundTriggerContext> triggerSnapshot = WorkflowTriggerContextHolder.current();
        ExecutorCompletionService<BranchResult> completion =
                new ExecutorCompletionService<>(executor);
        List<Future<BranchResult>> futures = new ArrayList<>();
        for (int idx = 0; idx < branchNodes.size(); idx++) {
            final int branchIndex = idx;
            WorkflowNodeDto branchNode = branchNodes.get(idx);
            futures.add(
                    completion.submit(
                            () -> {
                                tenantSnapshot.ifPresent(TenantContext::set);
                                triggerSnapshot.ifPresent(WorkflowTriggerContextHolder::set);
                                try {
                                    return new BranchResult(
                                            branchIndex,
                                            branchNode,
                                            new Outcome(branchNode, call.execute(branchNode)),
                                            null);
                                } catch (RuntimeException e) {
                                    return new BranchResult(branchIndex, branchNode, null, e);
                                } finally {
                                    WorkflowTriggerContextHolder.clear();
                                    if (tenantSnapshot.isPresent()) {
                                        TenantContext.clear();
                                    }
                                }
                            }));
        }
        // Completion order decides WHEN the join resolves; the outcomes themselves stay in branch
        // order so trace labels and the fan-in's collected-output array are deterministic.
        Outcome[] ordered = new Outcome[branchNodes.size()];
        int finishedCount = 0;
        boolean[] done = new boolean[branchNodes.size()];
        long deadline =
                joinTimeoutSeconds > 0
                        ? System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(joinTimeoutSeconds)
                        : 0;
        for (int i = 0; i < futures.size(); i++) {
            BranchResult finished;
            try {
                Future<BranchResult> next;
                if (joinTimeoutSeconds > 0) {
                    next =
                            completion.poll(
                                    Math.max(0, deadline - System.nanoTime()),
                                    java.util.concurrent.TimeUnit.NANOSECONDS);
                    if (next == null) {
                        futures.forEach(f -> f.cancel(true));
                        int first = 0;
                        while (first < done.length - 1 && done[first]) {
                            first++;
                        }
                        return new Joined(
                                new ArrayList<>(),
                                new NodeAttemptExecutor.NodeStepTimeoutException(
                                        branchNodes.get(first), (int) joinTimeoutSeconds),
                                branchNodes.get(first));
                    }
                } else {
                    next = completion.take();
                }
                finished = next.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                return new Joined(new ArrayList<>(), new CompletionException(ie), null);
            } catch (ExecutionException ee) {
                futures.forEach(f -> f.cancel(true));
                return new Joined(
                        new ArrayList<>(),
                        ee.getCause() instanceof RuntimeException re
                                ? re
                                : new CompletionException(ee),
                        null);
            }
            if (finished.error() != null) {
                // A queued sibling is told not to start; a running one cannot be un-run - same
                // position
                // the old index-order join left it in, just noticed sooner.
                futures.forEach(f -> f.cancel(true));
                return new Joined(
                        new ArrayList<>(),
                        new CompletionException(finished.error()),
                        finished.node());
            }
            ordered[finished.index()] = finished.outcome();
            done[finished.index()] = true;
            finishedCount++;
        }
        List<Outcome> outcomes = new ArrayList<>(finishedCount);
        for (Outcome o : ordered) {
            outcomes.add(o);
        }
        return new Joined(outcomes, null, null);
    }
}
