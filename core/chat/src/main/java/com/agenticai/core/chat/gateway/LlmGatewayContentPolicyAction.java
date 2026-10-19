package com.agenticai.core.chat.gateway;

/**
 * What {@link LlmGatewayContentPolicyAdvisor} does with a user message its
 * {@link LlmGatewayPromptInjectionScreener} flagged as a suspected
 * prompt-injection/jailbreak attempt, per tenant (see {@link
 * LlmGatewayContentPolicy#getInjectionScreeningAction()}).
 */
public enum LlmGatewayContentPolicyAction {
    /**
     * Default. Audit-log the flag (see {@code AuditLogService}) and let the
     * call proceed unchanged - same fail-open-toward-availability posture
     * {@code InboundSafetyScanService}'s FLAG mode already takes for channel
     * ingress: pattern matching has false positives, and a real customer
     * turn silently failing is the worse outcome.
     */
    FLAG,
    /**
     * Audit-log the flag AND reject the call before it reaches the provider,
     * by throwing {@link LlmGatewayContentPolicyViolationException}. Opt-in,
     * stricter posture for a tenant that would rather lose a false-positive
     * turn than risk a true-positive one reaching the model.
     */
    BLOCK
}
