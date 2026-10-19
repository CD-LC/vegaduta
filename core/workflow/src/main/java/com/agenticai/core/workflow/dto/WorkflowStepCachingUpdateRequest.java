package com.agenticai.core.workflow.dto;

/**
 * PATCH /api/workflows/{workflowId}/step-caching - toggles result-caching
 * for individual workflow steps. When enabled, a step whose input has not
 * changed since its last execution can reuse the cached output instead of
 * re-running the underlying agent call or tool invocation. Useful for
 * workflows that frequently re-enter the same intermediate state (e.g. a
 * looping supervisor that re-evaluates the same knowledge-base query).
 */
public record WorkflowStepCachingUpdateRequest(boolean enabled) {
}
