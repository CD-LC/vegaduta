package com.agenticai.core.providers.modality;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderCredentialResolver;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Factory for checking text model provider availability on this deployment.
 * Unlike voice providers which have separate implementations, text providers
 * are checked by verifying the platform has active credentials for them.
 *
 * <p>Used by {@link TextModelProviderValidator} during agent creation/update
 * to ensure a selected provider is available, and by the web console to
 * populate text model provider selection dropdowns.
 */
@Component
public class TextModelProviderFactory {

    private final ProviderCredentialResolver credentialResolver;

    // Maps TextModelProvider enum values to their corresponding ProviderName
    private static final Map<TextModelProvider, ProviderName> PROVIDER_MAPPING = Map.ofEntries(
        Map.entry(TextModelProvider.ANTHROPIC, ProviderName.ANTHROPIC),
        Map.entry(TextModelProvider.OPENAI, ProviderName.OPENAI),
        Map.entry(TextModelProvider.GEMINI, ProviderName.GEMINI),
        Map.entry(TextModelProvider.GROK, ProviderName.GROK),
        Map.entry(TextModelProvider.SARVAM, ProviderName.SARVAM),
        Map.entry(TextModelProvider.MISTRAL, ProviderName.MISTRAL),
        Map.entry(TextModelProvider.DEEPSEEK, ProviderName.DEEPSEEK),
        Map.entry(TextModelProvider.GROQ, ProviderName.GROQ),
        Map.entry(TextModelProvider.META, ProviderName.META),
        Map.entry(TextModelProvider.OLLAMA, ProviderName.OLLAMA),
        Map.entry(TextModelProvider.LOCAL_OPENAI, ProviderName.LOCAL_OPENAI),
        Map.entry(TextModelProvider.OPENROUTER, ProviderName.OPENROUTER)
    );

    public TextModelProviderFactory(ProviderCredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    /**
     * Maps a TextModelProvider enum to its corresponding ProviderName enum.
     * Returns null if the provider is not yet implemented.
     */
    private @Nullable ProviderName toProviderName(TextModelProvider textProvider) {
        return PROVIDER_MAPPING.get(textProvider);
    }

    /**
     * Checks whether a text model provider is available on this deployment.
     * A provider is considered "available" if:
     * <ul>
     *   <li>It is implemented (has a ProviderName mapping)</li>
     *   <li>The platform has a working credential/configuration for it</li>
     *   <li>Its implementation (ChatClientFactory) can resolve clients</li>
     * </ul>
     *
     * @return true if the provider is available and can be used by agents,
     *         false if it's not implemented or not configured on this deployment
     */
    public boolean isAvailable(TextModelProvider provider) {
        if (provider == null) {
            return false;
        }

        ProviderName providerName = toProviderName(provider);
        if (providerName == null) {
            // This enum value exists but is not yet implemented
            return false;
        }

        try {
            // Try to resolve an active credential for this provider.
            // If none exists, the resolver throws, which we catch and return false.
            // If one exists, the provider is available.
            credentialResolver.resolveActiveKey(providerName);
            return true;
        } catch (Exception e) {
            // No active credential, provider not configured, or provider not
            // yet implemented - all treated as "unavailable"
            return false;
        }
    }

    /**
     * Returns a human-readable reason why a provider is unavailable, for
     * inclusion in error messages during agent creation/update validation.
     */
    public String getUnavailabilityReason(TextModelProvider provider) {
        if (provider == null) {
            return "Provider cannot be null";
        }

        ProviderName providerName = toProviderName(provider);
        if (providerName == null) {
            return "Provider '" + provider.getProviderId() + "' is not yet implemented on this deployment";
        }

        return "No active credential configured for provider '" + provider.getProviderId()
            + "' on this tenant. Activate a credential for this provider in Settings > Providers.";
    }

    /**
     * Gets all currently available text providers on this deployment.
     * Used by the web UI to populate provider selection dropdowns.
     *
     * @return a map of available TextModelProvider enum values to their display names
     */
    public Map<TextModelProvider, String> getAvailableProviders() {
        Map<TextModelProvider, String> available = new HashMap<>();
        for (TextModelProvider provider : TextModelProvider.values()) {
            if (isAvailable(provider)) {
                available.put(provider, provider.getProviderId());
            }
        }
        return available;
    }
}
