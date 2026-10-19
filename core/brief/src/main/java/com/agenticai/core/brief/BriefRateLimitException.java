package com.agenticai.core.brief;

/** A caller hit the brief-plan burst or daily cap. Mapped to HTTP 429 by {@link BriefController}. */
public class BriefRateLimitException extends RuntimeException {

    public BriefRateLimitException(String message) {
        super(message);
    }
}
