package com.agenticai.core.workflow;

import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.stream.NodeStreamContext;
import com.agenticai.core.workflow.stream.NodeStreamSink;
import com.agenticai.core.workflow.stream.WorkflowRunStreamHub;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retry execution of one node for {@link WorkflowExecutionEngine}: binds the live-stream sink and the expression
 * scope to the calling thread, calls {@link WorkflowNodeExecutor#executeNode}, and retries a failed attempt per
 * {@link RetryPolicy} with {@link BackoffCalculator}'s jittered delay. Used from the traversal thread, parallel
 * branch threads and fan-out workers alike, so it keeps no per-run state.
 */
final class NodeAttemptExecutor {

    private static final Logger log = LoggerFactory.getLogger(NodeAttemptExecutor.class);

    /**
     * Runs a node attempt off the caller's thread so {@code stepTimeoutSeconds} can be enforced. A tenant-aware
     * pool re-applies the SUBMITTER's tenant and pin on the worker (AGENTS rule 12); the trigger context holder
     * is carried across by hand in {@link #runWithTimeout}.
     */
    private static final ExecutorService ATTEMPT_POOL = TenantAwareExecutors.newCachedThreadPool(
        TenantAwareExecutors.threadFactory("workflow-node-attempt", true));

    private final WorkflowNodeExecutor nodeExecutor;
    /** Live run events (phase 3); nullable for hand-built test engines. */
    private final WorkflowRunStreamHub streamHub;

    NodeAttemptExecutor(WorkflowNodeExecutor nodeExecutor, WorkflowRunStreamHub streamHub) {
        this.nodeExecutor = nodeExecutor;
        this.streamHub = streamHub;
    }

    WorkflowNodeExecutor.NodeExecutionResult execute(
        WorkflowDefinition definition, String runId, WorkflowNodeDto node, String input, String requestedBy,
        List<ChatInlineAttachment> attachments, RunMode mode
    ) {
        return execute(definition, runId, node, input, requestedBy, attachments, null, mode);
    }

    /**
     * @param scope loop / fan_out / sub_flow iteration facts ($item, $index, $items) bound to this thread for
     *     the node's execution, or null for an ordinary node (parallel branches get none, by design).
     * @param mode the run's mode: anything but PRODUCTION stamps the mode on the bound scope, which is the only
     *     thing that lets WorkflowNodeExecutor look a pin up (a production run binds no mode at all)
     */
    WorkflowNodeExecutor.NodeExecutionResult execute(
        WorkflowDefinition definition, String runId, WorkflowNodeDto node, String input, String requestedBy,
        List<ChatInlineAttachment> attachments, ExpressionScope scope, RunMode mode
    ) {
        return execute(definition, runId, node, input, requestedBy, attachments, scope, mode, -1);
    }

    /**
     * @param totalAttemptsOverride a positive value replaces the legacy per-node/workflow retry count with exactly
     *     that many TOTAL attempts (1 = a single try); -1 keeps the legacy count. Only the per-node error strategy
     *     ({@link NodeErrorStrategy}, flag-gated) passes a positive value - it drives its own retry loop.
     */
    WorkflowNodeExecutor.NodeExecutionResult execute(
        WorkflowDefinition definition, String runId, WorkflowNodeDto node, String input, String requestedBy,
        List<ChatInlineAttachment> attachments, ExpressionScope scope, RunMode mode, int totalAttemptsOverride
    ) {
        // +1: the configured retry count is retries AFTER the first try, this loop's attempt counter is the TOTAL
        // attempt number - see WorkflowDefinition#maxRetryAttempts's javadoc. Per-node override (Tier-3): a
        // node's own "retryAttempts" config wins over the workflow-level default, same 0-5 clamp.
        int maxAttempts = RetryPolicy.totalAttempts(node, definition.getMaxRetryAttempts(), totalAttemptsOverride);
        int attempt = 0;
        while (true) {
            attempt++;
            // Live stream (phase 3): node.started per attempt, and a sink bound to THIS thread (main traversal,
            // parallel branch or fan-out worker) so the executor can stream an agent's tokens and tool steps.
            NodeStreamSink sink = streamHub == null ? NodeStreamSink.NOOP : streamHub.nodeSink(runId, node.id());
            if (sink.isLive()) {
                Map<String, Object> started = new HashMap<>();
                started.put("nodeType", node.type());
                started.put("label", node.label());
                started.put("attempt", attempt);
                streamHub.publish(runId, "node.started", node.id(), started);
            }
            try {
                return runWithTimeout(definition, runId, node, input, requestedBy, attachments, scope, mode, sink);
            } catch (RuntimeException e) {
                if (!RetryPolicy.shouldRetry(node, attempt, maxAttempts, e)) {
                    throw e;
                }
                log.warn("Node '{}' ({}, type {}) failed on attempt {}/{} for run {}: {} - retrying.",
                    node.label(), node.id(), node.type(), attempt, maxAttempts, runId, e.getMessage());
                try {
                    Thread.sleep(BackoffCalculator.jitteredDelayMillis(attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            } finally {
                sink.close();
            }
        }
    }

    /**
     * One attempt. With a positive {@code stepTimeoutSeconds} the node runs on a worker thread and the wait is
     * bounded: on timeout the worker is interrupted and a {@link NodeStepTimeoutException} is thrown (the retry /
     * error-strategy machinery treats it like any other node failure). The stream sink, expression scope, tenant
     * and trigger context are bound on whichever thread actually runs the node. A non-positive timeout runs
     * inline on the caller's thread.
     */
    private WorkflowNodeExecutor.NodeExecutionResult runWithTimeout(
        WorkflowDefinition definition, String runId, WorkflowNodeDto node, String input, String requestedBy,
        List<ChatInlineAttachment> attachments, ExpressionScope scope, RunMode mode, NodeStreamSink sink
    ) {
        int timeoutSeconds = effectiveTimeoutSeconds(node, definition.getStepTimeoutSeconds());
        if (timeoutSeconds <= 0) {
            return runBound(definition, runId, node, input, requestedBy, attachments, scope, mode, sink);
        }
        Optional<InboundTriggerContext> triggerSnapshot = WorkflowTriggerContextHolder.current();
        TenantScope tenantScope = TenantScope.capture();
        Future<WorkflowNodeExecutor.NodeExecutionResult> future = ATTEMPT_POOL.submit(() -> tenantScope.get(() -> {
            triggerSnapshot.ifPresent(WorkflowTriggerContextHolder::set);
            try {
                return runBound(definition, runId, node, input, requestedBy, attachments, scope, mode, sink);
            } finally {
                WorkflowTriggerContextHolder.clear();
            }
        }));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new NodeStepTimeoutException(node, timeoutSeconds);
        } catch (InterruptedException ie) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running node '" + node.label() + "'.", ie);
        } catch (CancellationException ce) {
            throw new IllegalStateException("Node '" + node.label() + "' was cancelled.", ce);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        }
    }

    private WorkflowNodeExecutor.NodeExecutionResult runBound(
        WorkflowDefinition definition, String runId, WorkflowNodeDto node, String input, String requestedBy,
        List<ChatInlineAttachment> attachments, ExpressionScope scope, RunMode mode, NodeStreamSink sink
    ) {
        NodeStreamContext.bind(sink);
        ExpressionScopeContext.bind(ExpressionScope.forMode(scope, mode));
        try {
            return nodeExecutor.executeNode(
                definition.getId().toString(), definition.getName(), runId, node, input, requestedBy,
                WorkflowIntelligenceSettings.from(definition), attachments);
        } finally {
            NodeStreamContext.clear();
            ExpressionScopeContext.clear();
        }
    }


    /**
     * Floor for every node type that is legitimately slow (agent / LLM / sub-workflow / tool ...). The
     * workflow-level {@code stepTimeoutSeconds} defaults to 30 in every existing row and was never enforced
     * before, so applying it to an agent node would fail workflows that have always worked. Override with
     * {@code -Dvegaduta.workflow.longRunningStepTimeoutSeconds=N} (0 = no limit for those types).
     */
    static final int LONG_RUNNING_FLOOR_SECONDS =
        Integer.getInteger("vegaduta.workflow.longRunningStepTimeoutSeconds", 600);

    /** Upper bound accepted for a per-node {@code timeoutSeconds}. */
    static final int MAX_NODE_TIMEOUT_SECONDS = 3600;

    /** Types where the workflow-level step timeout is a sensible bound: no model call, no nested run. */
    private static final java.util.Set<String> FAST_TYPES = java.util.Set.of("http", "condition");

    /**
     * Timeout for one attempt of {@code node}: a node's own {@code timeoutSeconds} config wins (1..3600);
     * otherwise the workflow-level value for {@link #FAST_TYPES}; otherwise at least
     * {@link #LONG_RUNNING_FLOOR_SECONDS}. Zero or less means "no limit".
     */
    static int effectiveTimeoutSeconds(WorkflowNodeDto node, int workflowStepTimeoutSeconds) {
        Object raw = node.config() == null ? null : node.config().get("timeoutSeconds");
        if (raw != null) {
            try {
                int v = raw instanceof Number n ? n.intValue() : Integer.parseInt(raw.toString().trim());
                if (v >= 1) {
                    return Math.min(v, MAX_NODE_TIMEOUT_SECONDS);
                }
            } catch (NumberFormatException ignored) {
                // malformed override: fall through to the type default
            }
        }
        String type = node.type() == null ? "" : node.type().toLowerCase(java.util.Locale.ROOT);
        if (FAST_TYPES.contains(type)) {
            return workflowStepTimeoutSeconds;
        }
        if (LONG_RUNNING_FLOOR_SECONDS <= 0) {
            return 0;
        }
        return Math.max(workflowStepTimeoutSeconds, LONG_RUNNING_FLOOR_SECONDS);
    }

    /** A node attempt exceeded the workflow's {@code stepTimeoutSeconds}. */
    static final class NodeStepTimeoutException extends RuntimeException {
        NodeStepTimeoutException(WorkflowNodeDto node, int timeoutSeconds) {
            super("Node '" + node.label() + "' (" + node.type() + ") exceeded the step timeout of "
                + timeoutSeconds + "s.");
        }
    }
}
