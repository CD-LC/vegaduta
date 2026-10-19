package com.agenticai.core.agents.dto;

import com.agenticai.core.providers.modality.VoiceModelProvider;
import jakarta.validation.constraints.AssertTrue;

/**
 * PATCH /api/agents/{id}/voice-model - explicit voice provider selection for
 * this agent. Allows agents to route voice/speech-to-text through a specific
 * provider (SIDECAR_WHISPER, SARVAM, etc.) rather than using the endpoint's
 * default fallback chain.
 *
 * <p>Both fields are required together - an agent cannot have a provider
 * without a model, or vice versa. Null values (via omission) clear the
 * selection and revert to the default fallback chain.
 *
 * <p>The selected provider must be available on this deployment at the time
 * of the request - AgentService validates this and rejects unavailable providers.
 */
public record AgentVoiceModelUpdateRequest(
    // Nullable - null means "clear the selection and use default fallback"
    VoiceModelProvider voiceModelProvider,
    // Nullable - only required when voiceModelProvider is set; both-null clears the selection
    String voiceModel
) {
    /**
     * Trims {@code voiceModel} before validation, same pattern as
     * {@link AgentModelUpdateRequest}. A model id with leading/trailing
     * spaces would be sent verbatim to the provider and rejected.
     */
    public AgentVoiceModelUpdateRequest {
        if (voiceModel != null) {
            voiceModel = voiceModel.trim();
        }
    }

    /**
     * Conditional validation: {@code voiceModel} is required only when
     * {@code voiceModelProvider} is set. Both-null is valid and means "clear
     * the selection and revert to the default fallback chain" (see {@link #clear()}).
     * An unconditional {@code @NotBlank} on {@code voiceModel} would reject that
     * documented reset flow, so the constraint has to be conditional like this.
     */
    @AssertTrue(message = "voiceModel is required when voiceModelProvider is set")
    public boolean isVoiceModelValidForProvider() {
        return voiceModelProvider == null || (voiceModel != null && !voiceModel.isBlank());
    }

    /**
     * Convenience factory for clearing the voice model selection.
     */
    public static AgentVoiceModelUpdateRequest clear() {
        return new AgentVoiceModelUpdateRequest(null, null);
    }

    /**
     * Convenience factory for setting a specific provider/model pair.
     */
    public static AgentVoiceModelUpdateRequest of(VoiceModelProvider provider, String model) {
        return new AgentVoiceModelUpdateRequest(provider, model);
    }
}
