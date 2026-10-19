package com.agenticai.core.workflow;

import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/**
 * Runs a Dynamic Fan-Out's items on a dedicated bounded daemon pool (runtime-sized width must not land on the
 * common pool: every worker blocks on I/O). Items are joined in item order; real per-item start/end instants
 * are captured on the worker. Same tenant / trigger ThreadLocal snapshot discipline as
 * {@link ParallelBranchRunner}. The pool is always shut down before {@link #run} returns.
 */
final class FanOutRunner {

    /**
     * One item's result plus its REAL start/end instants. {@code result} is null exactly when
     * {@code cancelledBeforeStart}: dequeued after a Stop Run request and never executed.
     */
    record ItemOutcome(
        WorkflowNodeExecutor.NodeExecutionResult result, Instant startedAt, Instant completedAt,
        boolean cancelledBeforeStart
    ) {
        ItemOutcome(WorkflowNodeExecutor.NodeExecutionResult result, Instant startedAt, Instant completedAt) {
            this(result, startedAt, completedAt, false);
        }

        static ItemOutcome cancelledBeforeStart(Instant observedAt) {
            return new ItemOutcome(null, observedAt, observedAt, true);
        }
    }

    /** Joined items; {@code failure} is set when an item threw (the first, in item order). */
    record Joined(List<ItemOutcome> outcomes, RuntimeException failure) {}

    /** Executes one item on the worker thread with its iteration scope. */
    @FunctionalInterface
    interface ItemCall {
        WorkflowNodeExecutor.NodeExecutionResult execute(String item, ExpressionScope scope);
    }

    Joined run(
        String runId, List<String> items, int workers, int concurrency, BooleanSupplier cancelRequested, ItemCall call
    ) {
        Optional<TenantContext> tenantSnapshot = TenantContext.current();
        Optional<InboundTriggerContext> triggerSnapshot = WorkflowTriggerContextHolder.current();
        ExecutorService pool = TenantAwareExecutors.newFixedThreadPool(concurrency, TenantAwareExecutors.threadFactory("workflow-fanout-" + runId, true));
        try {
            List<CompletableFuture<ItemOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                String item = items.get(i);
                ExpressionScope scope = ExpressionScope.iteration(item, i, items);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    // Stop Run at item granularity: an item dequeued after a cancel landed declines to start;
                    // already-running items are not interrupted.
                    if (cancelRequested.getAsBoolean()) {
                        return ItemOutcome.cancelledBeforeStart(Instant.now());
                    }
                    tenantSnapshot.ifPresent(TenantContext::set);
                    triggerSnapshot.ifPresent(WorkflowTriggerContextHolder::set);
                    Instant itemStarted = Instant.now();
                    try {
                        WorkflowNodeExecutor.NodeExecutionResult r = call.execute(item, scope);
                        return new ItemOutcome(r, itemStarted, Instant.now());
                    } finally {
                        WorkflowTriggerContextHolder.clear();
                        if (tenantSnapshot.isPresent()) {
                            TenantContext.clear();
                        }
                    }
                }, pool));
            }
            List<ItemOutcome> joined = new ArrayList<>();
            for (CompletableFuture<ItemOutcome> future : futures) {
                try {
                    joined.add(future.join());
                } catch (RuntimeException joinFailure) {
                    return new Joined(joined, joinFailure);
                }
            }
            return new Joined(joined, null);
        } finally {
            // Success: releases idle workers. Failure: also drops queued items and interrupts running ones.
            pool.shutdownNow();
        }
    }
}
