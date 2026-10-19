package com.agenticai.core.agents.dto;

import com.agenticai.core.providers.modality.VideoModelProvider;

/**
 * Request DTO for updating an agent's video-to-text provider and model selection.
 * Mirrors {@link AgentVoiceModelUpdateRequest} for voice providers.
 *
 * <p>Both provider and model must be set together. {@code provider == null}
 * clears the selection and reverts to the default fallback chain (if any).
 */
public record AgentVideoModelUpdateRequest(
    /**
     * The video transcription provider to use (e.g., OPENAI_WHISPER_VIDEO, SIDECAR_WHISPER_VIDEO).
     * Nullable - null means "clear the selection and use platform defaults".
     */
    VideoModelProvider videoModelProvider,

    /**
     * The model name/ID within the selected provider (e.g., "whisper-1").
     * Nullable, only meaningful when videoModelProvider is non-null.
     * If null but provider is set, the provider's default model is used.
     */
    String videoModel
) {}
