package com.agenticai.core.providers.modality;

/**
 * Video-to-Text (video STT) provider options for agents. Each represents a
 * configurable video transcription backend that agents can route video input through.
 *
 * <p>When an agent processes video input, the platform selects the transcription
 * provider based on this value and {@link #getDefaultModel()}. At agent-creation
 * time or when explicitly changed, the platform validates that the selected
 * provider is available on this deployment (see {@link VideoProviderValidator}) and
 * that the model exists for that provider.
 *
 * <p>Unlike LLM providers (which are typically BYOK, tenant-managed credentials),
 * video providers follow a mixed model similar to voice:
 * <ul>
 *   <li>{@link #OPENAI_WHISPER_VIDEO} - deployment-managed OpenAI Whisper
 *       for video transcription (requires API key configured globally).</li>
 *   <li>{@link #SIDECAR_WHISPER_VIDEO} - local Whisper instance via sidecar
 *       (requires knowledge/sidecar profile active; no BYOK).</li>
 *   <li>{@link #SARVAM_VIDEO} - Sarvam's video service (future expansion).</li>
 *   <li>{@link #CUSTOM_CV_MODEL} - custom computer vision model for video analysis
 *       (future expansion).</li>
 * </ul>
 *
 * <p>Stage 1 launches with {@link #OPENAI_WHISPER_VIDEO} and {@link #SIDECAR_WHISPER_VIDEO} only;
 * others are reserved for future stages.
 */
public enum VideoModelProvider {
    /**
     * OpenAI Whisper for video transcription. Requires OpenAI API key configured
     * at the deployment level. Fast, reliable, and supports multiple video formats.
     * No tenant-specific configuration needed beyond having OpenAI access on this deployment.
     */
    OPENAI_WHISPER_VIDEO("openai-whisper-video", "whisper-1", "OpenAI Whisper (Video)",
        "Multi-language video-to-text, high accuracy", true),

    /**
     * Local Whisper via the sidecar (knowledge/RAG stack). Requires the sidecar
     * to be running and the "knowledge" profile active. Fastest and most private
     * for video transcription use cases. No tenant-specific configuration needed beyond
     * having the sidecar available.
     */
    SIDECAR_WHISPER_VIDEO("sidecar-whisper-video", "openai/whisper-1", "Sidecar Whisper (Video)",
        "Fast, self-hosted video transcription (runs on your deployment's servers)", false),

    /**
     * Sarvam's video-to-text service (future expansion). Tenant must have an active
     * {@code SarvamCredential}. Supports multiple languages and video formats.
     * (Reserved for future expansion.)
     */
    SARVAM_VIDEO("sarvam-video", "saaras:v3", "Sarvam Video",
        "23-language auto-detect for video content", true),

    /**
     * Google Vertex Video Intelligence API - video-to-text transcription,
     * scene detection, object tracking, etc. (Future expansion.)
     */
    VERTEX_VIDEO("vertex-video", "builtin", "Vertex Video",
        "Video Intelligence transcription, scene and object detection", true),

    /**
     * Custom computer vision model for video analysis (future expansion).
     * May include frame extraction, OCR, scene understanding, etc.
     */
    CUSTOM_CV_MODEL("custom-cv-model", "", "Custom CV model",
        "Tenant-supplied computer-vision model for video analysis", true);

    private final String providerId;
    private final String defaultModel;
    // UI-facing text served by GET /api/providers/modalities - see
    // VoiceModelProvider's displayName/description for why each constant
    // carries its own.
    private final String displayName;
    private final String description;
    /** See {@link VoiceModelProvider}'s {@code premium} field. */
    private final boolean premium;

    VideoModelProvider(String providerId, String defaultModel, String displayName, String description, boolean premium) {
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
     * Whether this provider is currently "early access" or experimental.
     * The platform may enforce additional restrictions or logging for beta providers.
     */
    public boolean isBeta() {
        return this == SARVAM_VIDEO || this == VERTEX_VIDEO || this == CUSTOM_CV_MODEL;
    }
}
