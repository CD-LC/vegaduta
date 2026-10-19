package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Builds a ready-to-use {@link ChatClient} for one provider from a raw API
 * key. One implementation per supported {@link ProviderName} — mirrors the
 * {@code ProviderValidator} pattern in the providers package so adding a new
 * provider (OpenAI, Bedrock, ...) means adding one new bean, not touching
 * existing code.
 */
public interface AgentChatClientFactory {

    ProviderName supportedProvider();

    ChatClient create(String apiKey, String model, int maxTokens, double temperature, double topP);

    /**
     * Provider-specific options overload (2026-09-23). {@code providerOptions} carries
     * per-call, per-agent extras the six-arg signature can't express - e.g. {@link
     * XaiServerToolSupport#OPTION_TOOLS} for xAI's server-side Agent Tools. The default
     * implementation ignores the map entirely, so none of the other fifteen factories need
     * touching; a provider that supports an option overrides this and reads the keys it owns.
     */
    default ChatClient create(
        String apiKey, String model, int maxTokens, double temperature, double topP,
        java.util.Map<String, Object> providerOptions
    ) {
        return create(apiKey, model, maxTokens, temperature, topP);
    }
}
