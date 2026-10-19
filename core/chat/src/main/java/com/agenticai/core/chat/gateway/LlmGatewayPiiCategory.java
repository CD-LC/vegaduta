package com.agenticai.core.chat.gateway;

/**
 * The PII categories {@link LlmGatewayPiiRedactor} can redact from an
 * outbound prompt, and the four per-category sub-toggles on {@link
 * LlmGatewayContentPolicy} (independent of each other - a tenant can, e.g.,
 * redact card/government-ID numbers while leaving email addresses alone).
 */
public enum LlmGatewayPiiCategory {
    EMAIL,
    PHONE,
    /** SSN (US), Aadhaar (India, 12-digit), PAN (India, alphanumeric) - see {@link LlmGatewayPiiRedactor}'s javadoc. */
    GOVERNMENT_ID,
    CREDIT_CARD
}
