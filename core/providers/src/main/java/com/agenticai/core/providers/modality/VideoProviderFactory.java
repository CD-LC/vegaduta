package com.agenticai.core.providers.modality;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Factory for selecting the right {@link VideoToTextProvider} implementation
 * based on a {@link VideoModelProvider} enum value. Implementations are
 * registered at startup and keyed by their provider enum.
 *
 * <p>Used by video processing components to get the right transcriber for an
 * agent's videoModelProvider field, and by {@link com.agenticai.core.agents.core.AgentService}
 * during validation to check provider availability before accepting a model selection.
 *
 * <p>Mirrors the design of {@link SpeechToTextProviderFactory} for voice providers.
 */
@Component
public class VideoProviderFactory {

    private final Map<VideoModelProvider, VideoToTextProvider> providers = new HashMap<>();

    /**
     * Registers a {@link VideoToTextProvider} implementation. Typically called
     * by Spring's dependency injection (providers implement
     * {@code VideoToTextProvider} and are annotated {@code @Component}).
     */
    public void register(VideoToTextProvider provider) {
        providers.put(provider.getProvider(), provider);
    }

    /**
     * Retrieves the provider implementation for a given enum value.
     *
     * @return {@link Optional#of(Object)} wrapping the provider if registered,
     *         {@link Optional#empty()} if the enum value is not yet implemented
     *         on this deployment (e.g., Sarvam Video reserved for a future pass)
     */
    public Optional<VideoToTextProvider> getProvider(VideoModelProvider providerEnum) {
        return Optional.ofNullable(providers.get(providerEnum));
    }

    /**
     * Checks whether a provider is available (has a registered implementation
     * AND that implementation reports {@link VideoToTextProvider#isAvailable()}).
     */
    public boolean isAvailable(VideoModelProvider providerEnum) {
        return getProvider(providerEnum)
            .map(VideoToTextProvider::isAvailable)
            .orElse(false);
    }

    /**
     * Returns a reason why a provider is unavailable, or null if it's available.
     * Used in error messages during agent creation/update validation.
     */
    public String getUnavailabilityReason(VideoModelProvider providerEnum) {
        return getProvider(providerEnum)
            .map(provider -> provider.isAvailable() ? null : provider.getUnavailabilityReason())
            .orElse("Provider '" + providerEnum.getProviderId() + "' is not available on this deployment");
    }

    /**
     * Convenience method: gets all currently available video providers on this
     * deployment. Used by the web UI to populate provider selection dropdowns.
     */
    public Map<VideoModelProvider, VideoToTextProvider> getAvailableProviders() {
        Map<VideoModelProvider, VideoToTextProvider> available = new HashMap<>();
        providers.forEach((providerEnum, provider) -> {
            if (provider.isAvailable()) {
                available.put(providerEnum, provider);
            }
        });
        return available;
    }
}
