package com.agenticai.core.agents.vision;

/**
 * A per-user-per-day vision allowance (camera/screen frames, or WATCH/LIVE
 * minutes - see {@link VisionUsageService}) is exhausted. Mapped to HTTP 429
 * in {@code GlobalExceptionHandler}, same "come back later (or upgrade)"
 * semantics as {@code SignupRateLimitExceededException} - the message names
 * the live configured limit, never a hardcoded copy of it.
 */
public class VisionQuotaExceededException extends RuntimeException {

    public VisionQuotaExceededException(String message) {
        super(message);
    }
}
