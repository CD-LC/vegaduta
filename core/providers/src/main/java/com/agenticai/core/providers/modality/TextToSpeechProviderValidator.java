package com.agenticai.core.providers.modality;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Validates that an agent's TTS provider/model selection is valid for this
 * deployment. Used by {@code AgentService} when an agent is created or
 * updated. Mirrors {@link VoiceProviderValidator} exactly, one enum swap.
 *
 * <p>Key invariants:
 * <ul>
 *   <li>Provider must be implemented and available on this deployment</li>
 *   <li>Provider and model must be set together (both non-null or both null)</li>
 *   <li>Model must be non-blank if provider is set</li>
 * </ul>
 */
@Component
public class TextToSpeechProviderValidator {

    private final TextToSpeechProviderFactory providerFactory;

    public TextToSpeechProviderValidator(TextToSpeechProviderFactory providerFactory) {
        this.providerFactory = providerFactory;
    }

    /**
     * Validates a TTS provider/model pair selected for an agent. Throws
     * {@link IllegalArgumentException} if the selection is invalid.
     *
     * @param provider the TTS provider enum (or null to use default fallback)
     * @param model the model name (or null)
     */
    public void validate(TtsModelProvider provider, String model) {
        // Both null is OK - means use the default ElevenLabs-then-Sarvam
        // fallback chain.
        if (provider == null && model == null) {
            return;
        }

        if (provider == null || !StringUtils.hasText(model)) {
            throw new IllegalArgumentException(
                "TTS provider and model must be set together. " +
                "If you don't want to explicitly select a provider, leave both null to use the default fallback chain."
            );
        }

        if (!providerFactory.isAvailable(provider)) {
            String reason = providerFactory.getUnavailabilityReason(provider);
            throw new IllegalArgumentException(
                "Cannot select TTS provider '" + provider.getProviderId() + "': " + reason
            );
        }

        if (model.isBlank()) {
            throw new IllegalArgumentException(
                "TTS model cannot be blank when a provider is selected."
            );
        }
    }

    /**
     * Convenience method: checks if a provider/model selection would be
     * valid. Returns false rather than throwing.
     */
    public boolean isValid(TtsModelProvider provider, String model) {
        try {
            validate(provider, model);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
