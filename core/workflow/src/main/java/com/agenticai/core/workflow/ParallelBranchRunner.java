package com.agenticai.core.workflow;

import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
        Optional<TenantContext> tenantSnapshot = TenantContext.current();
        Optional<InboundTriggerContext> triggerSnapshot = WorkflowTriggerContextHolder.current();
        List<CompletableFuture<Outcome>> futures = new ArrayList<>();
        for (WorkflowNodeDto branchNode : branchNodes) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                tenantSnapshot.ifPresent(TenantContext::set);
                triggerSnapshot.ifPresent(WorkflowTriggerContextHolder::set);
                try {
                    return new Outcome(branchNode, call.execute(branchNode));
                } finally {
                    WorkflowTriggerContextHolder.clear();
                    if (tenantSnapshot.isPresent()) {
                        TenantContext.clear();
                    }
                }
            }, executor));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                outcomes.add(futures.get(i).join());
            } catch (RuntimeException joinFailure) {
                return new Joined(outcomes, joinFailure, branchNodes.get(i));
            }
        }
        return new Joined(outcomes, null, null);
    }
}
