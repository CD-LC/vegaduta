package com.agenticai.core.templates;

/**
 * Thrown when a user has hit their per-day template-import cap (an abuse
 * control - see {@link TemplateImportRateLimiter}). Mapped to HTTP 429 (Too
 * Many Requests) in {@code core/app}'s {@code GlobalExceptionHandler}, right
 * next to its sibling {@link TemplateSubmissionLimitException}. The cap hit
 * is also logged (never silently dropped), per the contract.
 */
public class TemplateImportLimitException extends RuntimeException {

    public TemplateImportLimitException(int cap) {
        super("You've reached today's limit of " + cap + " template imports. "
            + "Try again tomorrow, or contact support if you need a higher limit.");
    }
}
