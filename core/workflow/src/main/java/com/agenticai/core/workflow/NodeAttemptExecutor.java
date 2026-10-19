package com.agenticai.core.workflow;

import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.stream.NodeStreamContext;
import com.agenticai.core.workflow.stream.NodeStreamSink;
import com.agenticai.core.workflow.stream.WorkflowRunStreamHub;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            NodeStreamContext.bind(sink);
            ExpressionScopeContext.bind(ExpressionScope.forMode(scope, mode));
            try {
                return nodeExecutor.executeNode(
                    definition.getId().toString(), definition.getName(), runId, node, input, requestedBy,
                    WorkflowIntelligenceSettings.from(definition), attachments);
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
                NodeStreamContext.clear();
                ExpressionScopeContext.clear();
            }
        }
    }
}
