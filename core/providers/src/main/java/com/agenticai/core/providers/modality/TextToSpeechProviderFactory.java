package com.agenticai.core.providers.modality;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Factory for selecting the right {@link TextToSpeechProvider} implementation
 * based on a {@link TtsModelProvider} enum value. Implementations are
 * registered at startup and keyed by their provider enum - mirrors {@link
 * SpeechToTextProviderFactory} exactly.
 *
 * <p>Used by {@code AgentService} during validation to check provider
 * availability before accepting a model selection, and by {@code
 * VoiceCallController}'s explicit-provider branch to look up the selected
 * agent's TTS provider at turn time.
 *
 * <p>Adding a THIRD TTS provider later: implement {@link TextToSpeechProvider},
 * add the enum value to {@link TtsModelProvider}, expose it as a bean (any module;
 * discovered by type in {@link TextToSpeechProviderConfiguration}) - no changes needed here or in
 * {@link TextToSpeechProviderValidator}.
 */
@Component
public class TextToSpeechProviderFactory {

    private final Map<TtsModelProvider, TextToSpeechProvider> providers = new HashMap<>();

    /**
     * Registers a {@link TextToSpeechProvider} implementation. Typically
     * called from {@link TextToSpeechProviderConfiguration}.
     */
    public void register(TextToSpeechProvider provider) {
        providers.put(provider.getProvider(), provider);
    }

    /**
     * Retrieves the provider implementation for a given enum value.
     *
     * @return {@link Optional#of(Object)} wrapping the provider if registered,
     *         {@link Optional#empty()} if the enum value has no implementation
     *         registered on this deployment
     */
    public Optional<TextToSpeechProvider> getProvider(TtsModelProvider providerEnum) {
        return Optional.ofNullable(providers.get(providerEnum));
    }

    /**
     * Checks whether a provider is available (has a registered implementation
     * AND that implementation reports {@link TextToSpeechProvider#isAvailable()}).
     */
    public boolean isAvailable(TtsModelProvider providerEnum) {
        return getProvider(providerEnum)
            .map(TextToSpeechProvider::isAvailable)
            .orElse(false);
    }

    /**
     * Returns a reason why a provider is unavailable, or null if it's
     * available. Used in error messages during agent create/update validation.
     */
    public String getUnavailabilityReason(TtsModelProvider providerEnum) {
        return getProvider(providerEnum)
            .map(provider -> provider.isAvailable() ? null : provider.getUnavailabilityReason())
            .orElse("Provider '" + providerEnum.getProviderId() + "' is not available on this deployment");
    }

    /**
     * Convenience method: gets all currently available TTS providers on this
     * deployment. Used by the web UI to populate provider selection dropdowns
     * (via {@code providerStatus}, same pattern the STT selector already uses).
     */
    public Map<TtsModelProvider, TextToSpeechProvider> getAvailableProviders() {
        Map<TtsModelProvider, TextToSpeechProvider> available = new HashMap<>();
        providers.forEach((providerEnum, provider) -> {
            if (provider.isAvailable()) {
                available.put(providerEnum, provider);
            }
        });
        return available;
    }
}
