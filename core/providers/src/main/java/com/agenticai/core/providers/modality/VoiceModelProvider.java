package com.agenticai.core.providers.modality;

/**
 * Voice/Speech-to-Text (STT) provider options for agents. Each represents a
 * configurable speech-to-text backend that agents can route voice input through.
 *
 * <p>When an agent creates a voice turn, the platform selects the transcription
 * provider based on this value and {@link #getDefaultModel()}. At agent-creation
 * time or when explicitly changed, the platform validates that the selected
 * provider is available on this deployment (see {@link VoiceProviderService}) and
 * that the model exists for that provider.
 *
 * <p>Unlike LLM providers (which are typically BYOK, tenant-managed credentials),
 * voice providers follow a mixed model:
 * <ul>
 *   <li>{@link #SIDECAR_WHISPER} - deployment-managed local Whisper instance
 *       (requires knowledge/sidecar profile active; no BYOK).</li>
 *   <li>{@link #SARVAM} - tenant BYOK (needs an active {@code SarvamCredential}
 *       for this tenant). Falls back to platform pool if available.</li>
 *   <li>{@link #ELEVENLABS} - tenant BYOK (needs an active {@code LlmProviderCredential}
 *       for ElevenLabs).</li>
 * </ul>
 *
 * <p>Stage 1 launches with {@link #SIDECAR_WHISPER} and {@link #SARVAM} only;
 * others are reserved for future stages.
 */
public enum VoiceModelProvider {
    /**
     * Local Whisper via the sidecar (knowledge/RAG stack). Requires the sidecar
     * to be running and the "knowledge" profile active. Fastest and most reliable
     * for push-to-talk use cases. No tenant-specific configuration needed beyond
     * having the sidecar available.
     */
    SIDECAR_WHISPER("sidecar-whisper", "openai/whisper-1", "Sidecar Whisper",
        "Fast, self-hosted transcription (runs on your deployment's servers)", false),

    /**
     * Sarvam's speech-to-text ({{@link #SARVAM} supports both STT and TTS, plus
     * translate/translit modes). Tenant must have an active {@code SarvamCredential}.
     * Auto-detects language by default, supports 23 languages.
     *
     * <p>Models: {@code saaras:v3} (default, all 23 languages, transliteration,
     * codemix support) or {@code saarika:v2.5} (legacy, 12 languages, transcribe-only).
     */
    SARVAM("sarvam", "saaras:v3", "Sarvam STT",
        "23-language auto-detect, transliteration", true),

    /**
     * ElevenLabs speech-to-text (future stage). Tenant must have an active
     * ElevenLabs BYOK credential. High-quality transcription, supports many
     * languages. (Reserved for future expansion.)
     */
    ELEVENLABS("elevenlabs", "eleven_multilingual_v2", "ElevenLabs STT",
        "High-quality multi-language transcription", true),

    /**
     * Reserved for future video-to-text providers (e.g., GPT-4V for video
     * transcription, Vertex Video Intelligence API, etc.).
     */
    VIDEO_PROVIDER_RESERVED_1("video-provider-reserved-1", "", "Reserved", "", true);

    private final String providerId;
    private final String defaultModel;
    // UI-facing text served by GET /api/providers/modalities - each constant
    // carries its own label/description so adding a provider to this enum is
    // the whole catalog change (the workbench renders what the endpoint
    // returns; it holds no per-provider text of its own).
    private final String displayName;
    private final String description;
    /**
     * Whether picking this provider is a paid-tier selection (BYOK or
     * metered) as opposed to a deployment-provided free path like the
     * sidecar. Served through the modality catalog so the selectors' free-
     * plan filtering stays data-driven instead of hardcoding which brands
     * cost money.
     */
    private final boolean premium;

    VoiceModelProvider(String providerId, String defaultModel, String displayName, String description, boolean premium) {
        this.providerId = providerId;
        this.defaultModel = defaultModel;
        this.displayName = displayName;
        this.description = description;
        this.premium = premium;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isPremium() {
        return premium;
    }

    /**
     * Whether this constant names a real, selectable provider. {@link
     * #VIDEO_PROVIDER_RESERVED_1} is a placeholder slot in the enum, not a
     * provider - the modality catalog filters on this rather than listing it.
     */
    public boolean isSelectable() {
        return this != VIDEO_PROVIDER_RESERVED_1;
    }

    /**
     * Whether this provider is currently "early access" or experimental.
     * The platform may enforce additional restrictions or logging for beta providers.
     */
    public boolean isBeta() {
        return this == ELEVENLABS || this == VIDEO_PROVIDER_RESERVED_1;
    }
}
