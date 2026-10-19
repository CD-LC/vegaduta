package com.agenticai.core.chat.gateway;

/**
 * Thrown by {@link LlmGatewayBudgetService}/{@link LlmGatewayRateLimiterService}
 * when a tenant's outbound call is rejected BEFORE it reaches the LLM
 * provider - the "pre-call screening" half of #848's gateway seam, distinct
 * from {@link com.agenticai.core.chat.UpstreamProviderException} (which
 * wraps a call that DID reach the provider and failed there). Mapped to
 * HTTP 429 by {@code GlobalExceptionHandler}, same "client-actionable, not a
 * server bug" posture as {@code WorkflowTriggerRateLimiter}'s own limit
 * exception elsewhere in this codebase.
 */
public class LlmGatewayLimitExceededException extends RuntimeException {

    public LlmGatewayLimitExceededException(String message) {
        super(message);
    }
}
