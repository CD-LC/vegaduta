package com.agenticai.core.providers.dto;

/**
 * One selectable option in {@code GET /api/providers/modalities} - the
 * server-generated replacement for the workbench's hardcoded
 * VOICE_PROVIDERS/TTS_PROVIDERS/VIDEO_PROVIDERS arrays and its hand-wired
 * credential/health probes. {@code provider} is the enum-name wire value
 * clients send back on {@code .../voice-model}, {@code .../tts-model} and
 * {@code .../video-model} PATCHes.
 */
public record ModalityProviderOption(
    /** Enum name, e.g. {@code "SIDECAR_WHISPER"} - the value round-tripped in agent PATCH bodies. */
    String provider,
    /** Provider's own id, e.g. {@code "sidecar-whisper"}. */
    String providerId,
    /** Display name carried on the enum constant. */
    String label,
    /** One-line description carried on the enum constant. */
    String description,
    /** The enum's default model for this provider. */
    String defaultModel,
    /**
     * A provider implementation bean is registered on this deployment.
     * {@code false} = "coming soon" surface (enum constant reserved, no
     * backend yet) - the UI should render the row disabled rather than
     * selectable, since saving it would fail validation anyway.
     */
    boolean implemented,
    /**
     * Usable RIGHT NOW for this tenant - resolved server-side by the
     * modality factories' {@code isAvailable} (credential active, sidecar
     * healthy, ...). The UI never probes provider credentials itself.
     */
    boolean available,
    /** Why {@code available} is false - the factory's reason string, or null. */
    String unavailabilityReason,
    /**
     * Paid-tier/BYOK selection (the enum's own flag) - the selectors' free-
     * plan filtering reads this rather than hardcoding which brands cost
     * money.
     */
    boolean premium,
    /** Early-access/experimental flag where the enum defines one. */
    boolean beta
) {
}
