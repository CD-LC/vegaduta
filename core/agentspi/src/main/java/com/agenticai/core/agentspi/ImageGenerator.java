package com.agenticai.core.agentspi;

/**
 * Generates one image for the current tenant through whichever image provider serves it (the
 * tenant's own key or the platform pool), with the tenant's quota applied. Kernel-owned (ADR-0011
 * rule a); implemented by {@code imagegen.agenttools.OrchestratorImageGenerator}.
 */
public interface ImageGenerator {

    record GeneratedImage(byte[] bytes, String contentType, String model, String provider) {}

    /**
     * @param size optional provider size hint, e.g. {@code 1024x1024}
     * @throws NotConfiguredException when no provider can serve images for this tenant
     */
    GeneratedImage generate(String prompt, String size);

    class NotConfiguredException extends RuntimeException {
        public NotConfiguredException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
