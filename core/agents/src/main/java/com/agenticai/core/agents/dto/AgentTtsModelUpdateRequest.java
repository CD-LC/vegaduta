package com.agenticai.core.agents.dto;

import com.agenticai.core.providers.modality.TtsModelProvider;
import jakarta.validation.constraints.AssertTrue;

/**
 * PATCH /api/agents/{id}/tts-model - explicit text-to-speech provider
 * selection for this agent. Output-side twin of {@link
 * AgentVoiceModelUpdateRequest} (speech-to-text) - same shape, one enum swap.
 *
 * <p>Both fields are required together - an agent cannot have a provider
 * without a model, or vice versa. Null values (via omission) clear the
 * selection and revert to the default ElevenLabs-then-Sarvam fallback chain.
 *
 * <p>The selected provider must be available on this deployment at the time
 * of the request - AgentService validates this and rejects unavailable providers.
 */
public record AgentTtsModelUpdateRequest(
    // Nullable - null means "clear the selection and use default fallback"
    TtsModelProvider ttsProvider,
    // Nullable - only required when ttsProvider is set; both-null clears the selection
    String ttsModel
) {
    /**
     * Trims {@code ttsModel} before validation, same pattern as {@link
     * AgentVoiceModelUpdateRequest}.
     */
    public AgentTtsModelUpdateRequest {
        if (ttsModel != null) {
            ttsModel = ttsModel.trim();
        }
    }

    /**
     * Conditional validation: {@code ttsModel} is required only when {@code
     * ttsProvider} is set. Both-null is valid and means "clear the selection
     * and revert to the default fallback chain" (see {@link #clear()}).
     */
    @AssertTrue(message = "ttsModel is required when ttsProvider is set")
    public boolean isTtsModelValidForProvider() {
        return ttsProvider == null || (ttsModel != null && !ttsModel.isBlank());
    }

    /**
     * Convenience factory for clearing the TTS model selection.
     */
    public static AgentTtsModelUpdateRequest clear() {
        return new AgentTtsModelUpdateRequest(null, null);
    }

    /**
     * Convenience factory for setting a specific provider/model pair.
     */
    public static AgentTtsModelUpdateRequest of(TtsModelProvider provider, String model) {
        return new AgentTtsModelUpdateRequest(provider, model);
    }
}
