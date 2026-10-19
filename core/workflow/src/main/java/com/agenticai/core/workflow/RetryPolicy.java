package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.security.TenantLimitExceededException;

/**
 * Pure retry rules for the in-process engine: how many total attempts a node gets and whether a failed
 * attempt may be retried. No I/O, no clock, no randomness (see {@link BackoffCalculator} for the delay).
 */
final class RetryPolicy {

    private static final int MAX_NODE_RETRIES = 5;

    private RetryPolicy() {}

    /**
     * Per-node retry policy: a node's {@code retryAttempts} config overrides the workflow-level
     * {@code maxRetryAttempts} for that node only - same 0-5 clamp, "retries after the first try" semantics.
     * A missing or malformed value falls back to the workflow default rather than failing the run.
     */
    static int nodeMaxRetries(WorkflowNodeDto node, int workflowDefaultRetries) {
        Object raw = node.config().get("retryAttempts");
        if (raw == null) {
            return Math.max(0, workflowDefaultRetries);
        }
        try {
            int retries = raw instanceof Number num ? num.intValue() : Integer.parseInt(raw.toString().trim());
            return Math.min(Math.max(retries, 0), MAX_NODE_RETRIES);
        } catch (NumberFormatException e) {
            return Math.max(0, workflowDefaultRetries);
        }
    }

    /**
     * TOTAL attempts for a node execution. {@code totalAttemptsOverride > 0} replaces the legacy count with
     * exactly that many total attempts (the per-node error strategy drives its own loop); -1 keeps the legacy
     * count (retries + the first try).
     */
    static int totalAttempts(WorkflowNodeDto node, int workflowDefaultRetries, int totalAttemptsOverride) {
        return totalAttemptsOverride > 0 ? totalAttemptsOverride : nodeMaxRetries(node, workflowDefaultRetries) + 1;
    }

    /**
     * Output nodes are never retried: they email/WhatsApp/upload and a blind retry risks sending twice when
     * the destination already received the message before the failure surfaced.
     */
    static boolean isRetryableNode(WorkflowNodeDto node) {
        return !"output".equalsIgnoreCase(node.type());
    }

    /** A quota rejection is deterministic within the backoff window, so it is never retried. */
    static boolean shouldRetry(WorkflowNodeDto node, int attempt, int maxAttempts, RuntimeException failure) {
        return isRetryableNode(node) && attempt < maxAttempts && !(failure instanceof TenantLimitExceededException);
    }
}
