package com.agenticai.core.agents.vision;

/**
 * No live vision-session record for the given id under this tenant - never
 * started, already ended, or expired past its 24h Redis TTL. Mapped to HTTP
 * 404 in {@code GlobalExceptionHandler}, same shape as
 * {@code AgentNotFoundException}.
 */
public class VisionSessionNotFoundException extends RuntimeException {

    public VisionSessionNotFoundException(String sessionId) {
        super("No active vision session '" + sessionId + "' - it may have already ended or expired.");
    }
}
