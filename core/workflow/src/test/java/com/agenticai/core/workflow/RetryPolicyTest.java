package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link RetryPolicy}: per-node retry parsing, attempt counting and retryability rules. */
class RetryPolicyTest {

    private static WorkflowNodeDto node(String type, Map<String, Object> config) {
        return new WorkflowNodeDto("n1", type, Map.of(), Map.of("config", config));
    }

    @Test
    void missingNodeConfigFallsBackToTheWorkflowDefault() {
        assertEquals(2, RetryPolicy.nodeMaxRetries(node("agent", Map.of()), 2));
        WorkflowNodeDto bare = new WorkflowNodeDto("n1", "agent", Map.of(), null);
        assertEquals(2, RetryPolicy.nodeMaxRetries(bare, 2));
        assertEquals(0, RetryPolicy.nodeMaxRetries(bare, -4), "a negative default floors at 0");
    }

    @Test
    void retryAttemptsOverridesAndClamps() {
        assertEquals(3, RetryPolicy.nodeMaxRetries(node("agent", Map.of("retryAttempts", 3)), 0));
        assertEquals(5, RetryPolicy.nodeMaxRetries(node("agent", Map.of("retryAttempts", 99)), 0));
        assertEquals(0, RetryPolicy.nodeMaxRetries(node("agent", Map.of("retryAttempts", -2)), 4));
        assertEquals(
                3,
                RetryPolicy.nodeMaxRetries(node("agent", Map.of("retryAttempts", "3")), 0),
                "string numbers coerce");
        assertEquals(
                1,
                RetryPolicy.nodeMaxRetries(node("agent", Map.of("retryAttempts", "oops")), 1),
                "a malformed value keeps the default");
    }

    @Test
    void totalAttemptsHonoursTheOverrideOrTheRetryCount() {
        WorkflowNodeDto n = node("agent", Map.of("retryAttempts", 2));
        assertEquals(3, RetryPolicy.totalAttempts(n, 0, -1), "retries plus the first try");
        assertEquals(7, RetryPolicy.totalAttempts(n, 0, 7), "an override is exact");
        assertEquals(3, RetryPolicy.totalAttempts(n, 0, 0), "0 does not count as an override");
    }

    @Test
    void outputNodesAreNeverRetryable() {
        assertFalse(RetryPolicy.isRetryableNode(node("output", Map.of())));
        assertFalse(RetryPolicy.isRetryableNode(node("OUTPUT", Map.of())));
        assertTrue(RetryPolicy.isRetryableNode(node("agent", Map.of())));
    }

    @Test
    void shouldRetryRequiresAttemptsLeftAndARetryableFailure() {
        WorkflowNodeDto n = node("agent", Map.of());
        assertTrue(RetryPolicy.shouldRetry(n, 1, 3, new IllegalStateException("x")));
        assertFalse(RetryPolicy.shouldRetry(n, 3, 3, new IllegalStateException("x")));
        assertFalse(
                RetryPolicy.shouldRetry(n, 1, 3, new TenantLimitExceededException("quota")),
                "a quota rejection is deterministic, never retried");
        assertFalse(
                RetryPolicy.shouldRetry(
                        node("output", Map.of()), 1, 3, new IllegalStateException("x")));
    }
}
