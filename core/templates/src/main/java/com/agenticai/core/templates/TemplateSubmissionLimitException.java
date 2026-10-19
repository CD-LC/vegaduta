package com.agenticai.core.templates;

/**
 * Thrown when a tenant has hit its per-day template-creation cap (an abuse
 * control - see the contract's "Rate / abuse limits"). Mapped to HTTP 429 (Too
 * Many Requests) in {@code core/app}'s {@code GlobalExceptionHandler}. The cap
 * hit is also logged (never silently dropped), per the contract.
 */
public class TemplateSubmissionLimitException extends RuntimeException {

    public TemplateSubmissionLimitException(int cap) {
        super("You've reached today's limit of " + cap + " template submissions for this workspace. "
            + "Try again tomorrow, or contact support if you need a higher limit.");
    }
}
