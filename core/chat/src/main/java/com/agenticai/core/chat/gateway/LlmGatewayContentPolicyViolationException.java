package com.agenticai.core.chat.gateway;

/**
 * Thrown by {@link LlmGatewayContentPolicyAdvisor} when a tenant's {@link
 * LlmGatewayContentPolicyAction#BLOCK} injection-screening policy flags an
 * outbound user message - rejected BEFORE the call reaches the LLM provider,
 * same "pre-call screening" posture as {@link LlmGatewayLimitExceededException}
 * (budget/rate-limit rejections). Kept as a distinct type rather than reusing
 * that class since this is a content-policy rejection, not a governance/quota
 * one - a caller (or {@code GlobalExceptionHandler}) may reasonably want to
 * map the two differently (e.g. 422 here vs 429 there).
 */
public class LlmGatewayContentPolicyViolationException extends RuntimeException {

    public LlmGatewayContentPolicyViolationException(String message) {
        super(message);
    }
}
