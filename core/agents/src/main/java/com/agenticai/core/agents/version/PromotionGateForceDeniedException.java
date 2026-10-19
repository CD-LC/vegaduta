package com.agenticai.core.agents.version;

/**
 * Thrown when {@code force=true} is sent on a gated PRODUCTION promote by a
 * caller lacking the MANAGE_AGENTS scope - mapped to {@code 403 Forbidden} by
 * {@code AgentVersionController}. 403 rather than the sharing module's 404
 * posture because the caller already passed EDITOR access on this agent: its
 * existence is not a secret to protect, only the scope is missing (the same
 * reasoning {@code SecretWriteForbiddenException}'s mapping documents).
 */
public class PromotionGateForceDeniedException extends RuntimeException {

    public PromotionGateForceDeniedException(String message) {
        super(message);
    }
}
