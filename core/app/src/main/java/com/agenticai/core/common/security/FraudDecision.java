package com.agenticai.core.common.security;

/**
 * Fraud detection decision: the action taken in response to an abuse detection event.
 */
public enum FraudDecision {
    /**
     * Request is allowed to proceed without additional verification
     */
    ALLOW,

    /**
     * Request is challenged: client must complete a CAPTCHA or other verification
     */
    CHALLENGE,

    /**
     * Request is blocked: immediate 403 response
     */
    BLOCK;

    public String getHttpStatus() {
        return this == BLOCK ? "403" : "403"; // Both use 403, but CHALLENGE includes challenge header
    }

    public boolean isSuspicious() {
        return this != ALLOW;
    }
}
