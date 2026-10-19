package com.agenticai.core.sharing;

/**
 * Thrown by {@link ShareRateLimiter} when the caller has exhausted their
 * per-minute window on the share-grant surface. Mapped to HTTP 429 in {@code
 * core/app}'s {@code GlobalExceptionHandler} - that is the ONLY place this
 * status is decided, same rule as this module's other two exceptions. The
 * message is a constant, generic string: it never reveals which cap (write
 * vs. read), the configured limit, or the current count - none of that is
 * the abuser's business.
 */
public class ShareRateLimitExceededException extends RuntimeException {

    public ShareRateLimitExceededException() {
        super("Too many sharing requests - please slow down and retry shortly.");
    }
}
