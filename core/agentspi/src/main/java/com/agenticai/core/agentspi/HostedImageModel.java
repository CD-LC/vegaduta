package com.agenticai.core.agentspi;

/**
 * The platform's own hosted image model, called directly with a named model (the public demo's
 * "magic" image runs - platform-paid, no tenant provider involved). Kernel-owned (ADR-0011 rule a);
 * implemented by {@code imagegen.agenttools.GeminiHostedImageModel}.
 */
public interface HostedImageModel {

    record Image(byte[] bytes, String mimeType, String model) {}

    /** Cheap, non-throwing: whether the platform has a hosted image provider enabled at all. */
    boolean isAvailable();

    /**
     * @throws RuntimeException when the platform has no hosted image provider or the call fails
     */
    Image generate(String prompt, String model);
}
