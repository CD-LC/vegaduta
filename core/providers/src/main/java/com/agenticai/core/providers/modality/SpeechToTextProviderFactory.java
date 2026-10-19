package com.agenticai.core.providers.modality;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Factory for selecting the right {@link SpeechToTextProvider} implementation
 * based on a {@link VoiceModelProvider} enum value. Implementations are
 * registered at startup and keyed by their provider enum.
 *
 * <p>Used by {@link com.agenticai.core.voice.VoiceCallController} to get the
 * right transcriber for an agent's voiceModelProvider field, and by
 * {@link com.agenticai.core.agents.core.AgentService} during validation to check
 * provider availability before accepting a model selection.
 */
@Component
public class SpeechToTextProviderFactory {

    private final Map<VoiceModelProvider, SpeechToTextProvider> providers = new HashMap<>();

    /**
     * Registers a {@link SpeechToTextProvider} implementation. Typically called
     * by Spring's dependency injection (providers implement
     * {@code SpeechToTextProvider} and are annotated {@code @Component}).
     */
    public void register(SpeechToTextProvider provider) {
        providers.put(provider.getProvider(), provider);
    }

    /**
     * Retrieves the provider implementation for a given enum value.
     *
     * @return {@link Optional#of(Object)} wrapping the provider if registered,
     *         {@link Optional#empty()} if the enum value is not yet implemented
     *         on this deployment (e.g., ElevenLabs reserved for a future pass)
     */
    public Optional<SpeechToTextProvider> getProvider(VoiceModelProvider providerEnum) {
        return Optional.ofNullable(providers.get(providerEnum));
    }

    /**
     * Checks whether a provider is available (has a registered implementation
     * AND that implementation reports {@link SpeechToTextProvider#isAvailable()}).
     */
    public boolean isAvailable(VoiceModelProvider providerEnum) {
        return getProvider(providerEnum)
            .map(SpeechToTextProvider::isAvailable)
            .orElse(false);
    }

    /**
     * Returns a reason why a provider is unavailable, or null if it's available.
     * Used in error messages during agent creation/update validation.
     */
    public String getUnavailabilityReason(VoiceModelProvider providerEnum) {
        return getProvider(providerEnum)
            .map(provider -> provider.isAvailable() ? null : provider.getUnavailabilityReason())
            .orElse("Provider '" + providerEnum.getProviderId() + "' is not available on this deployment");
    }

    /**
     * Convenience method: gets all currently available voice providers on this
     * deployment. Used by the web UI to populate provider selection dropdowns.
     */
    public Map<VoiceModelProvider, SpeechToTextProvider> getAvailableProviders() {
        Map<VoiceModelProvider, SpeechToTextProvider> available = new HashMap<>();
        providers.forEach((providerEnum, provider) -> {
            if (provider.isAvailable()) {
                available.put(providerEnum, provider);
            }
        });
        return available;
    }
}
