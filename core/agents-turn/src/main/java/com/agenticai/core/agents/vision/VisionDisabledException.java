package com.agenticai.core.agents.vision;

/**
 * The platform-wide vision flag ({@code app.vision.enabled}) is off. Mapped
 * to HTTP 503 in {@code GlobalExceptionHandler} - "the platform itself isn't
 * serving this right now", same posture as
 * {@code FreeTierProviderPoolEmptyException}, not a client mistake and not
 * an entitlement problem.
 */
public class VisionDisabledException extends RuntimeException {

    public VisionDisabledException(String message) {
        super(message);
    }
}
