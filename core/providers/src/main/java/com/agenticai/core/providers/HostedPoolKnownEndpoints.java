package com.agenticai.core.providers;

import java.util.EnumMap;
import java.util.Map;

/**
 * Static defaults for the hosted pool's "pick a provider, paste a key" flow. Split out of {@link
 * PlatformHostedLlmProviderService} on 2026-10-14 (file-size ratchet); the values and their
 * provenance are unchanged.
 */
final class HostedPoolKnownEndpoints {

    private HostedPoolKnownEndpoints() {}

    /**
     * Default {@code (baseUrl, completionsPath)} for each {@link PlatformHostedLlmProviderService#HOSTED_POOL_COMPATIBLE_PROVIDERS}
     * value - lets an admin pick a provider and paste a key without ever typing a base URL,
     * mirroring the tenant BYOK flow (`ProvidersPanel.tsx`) where the provider identity alone is
     * enough. Values match the compile-time constants each dedicated BYOK chat-client factory
     * already uses (see {@code OpenAIAgentChatClientFactory}, {@code GrokAgentChatClientFactory}'s
     * {@code https://api.x.ai}, {@code OpenRouterAgentChatClientFactory}'s {@code
     * https://openrouter.ai/api}, {@code MetaAgentChatClientFactory}'s {@code https://api.meta.ai})
     * plus Gemini's OpenAI-compat surface (see {@link
     * PlatformHostedLlmProvider#getCompletionsPath()}'s javadoc). An admin-supplied {@code
     * baseUrl}/{@code completionsPath} always wins over this default - see {@code PlatformHostedLlmProviderService#resolveRequest}.
     */
    static final Map<ProviderName, KnownEndpoint> KNOWN_PROVIDER_ENDPOINTS = from(new ProviderEndpoints());

    /**
     * The hosted pool's default per provider, built from {@code app.llm.endpoints} so the pool, the key
     * validators and the chat-client factories can never disagree about a vendor's address. A null
     * {@code completionsPath} means the conventional "/v1/chat/completions".
     */
    static Map<ProviderName, KnownEndpoint> from(ProviderEndpoints endpoints) {
        Map<ProviderName, KnownEndpoint> known = new EnumMap<>(ProviderName.class);
        for (ProviderName provider : ProviderName.values()) {
            if (!PlatformHostedLlmProviderService.HOSTED_POOL_COMPATIBLE_PROVIDERS.contains(provider)) {
                continue;
            }
            endpoints.endpoint(provider).ifPresent(e ->
                known.put(provider, new KnownEndpoint(e.getBaseUrl(), e.getCompletionsPath())));
        }
        return Map.copyOf(known);
    }

    record KnownEndpoint(String baseUrl, String completionsPath) {}
}
