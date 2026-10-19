package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.validation.ProviderNotSupportedException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class ChatClientRegistry {

    private final Map<ProviderName, AgentChatClientFactory> factoriesByProvider;

    public ChatClientRegistry(List<AgentChatClientFactory> factories) {
        this.factoriesByProvider = factories.stream()
            .collect(Collectors.toMap(AgentChatClientFactory::supportedProvider, Function.identity()));
    }

    public ChatClient createClient(
        ProviderName providerName, String apiKey, String model, int maxTokens, double temperature, double topP
    ) {
        return createClient(providerName, apiKey, model, maxTokens, temperature, topP, Map.of());
    }

    /**
     * {@code providerOptions} overload (2026-09-23) - forwards per-call provider-specific
     * extras to factories that understand them (today only {@code GROK}, {@link
     * XaiServerToolSupport}); every other factory's default overload ignores the map.
     */
    public ChatClient createClient(
        ProviderName providerName, String apiKey, String model, int maxTokens, double temperature, double topP,
        Map<String, Object> providerOptions
    ) {
        AgentChatClientFactory factory = factoriesByProvider.get(providerName);
        if (factory == null) {
            throw new ProviderNotSupportedException(providerName);
        }
        return factory.create(apiKey, model, maxTokens, temperature, topP, providerOptions);
    }
}
