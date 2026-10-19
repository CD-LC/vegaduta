package com.agenticai.core.providers;

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
    static final Map<ProviderName, KnownEndpoint> KNOWN_PROVIDER_ENDPOINTS =
            Map.of(
                    ProviderName.OPENAI, new KnownEndpoint("https://api.openai.com", null),
                    ProviderName.GROK, new KnownEndpoint("https://api.x.ai", null),
                    ProviderName.OPENROUTER, new KnownEndpoint("https://openrouter.ai/api", null),
                    ProviderName.GEMINI,
                            new KnownEndpoint(
                                    "https://generativelanguage.googleapis.com",
                                    "/v1beta/openai/chat/completions"),
                    // Values match MistralAgentChatClientFactory/DeepSeekAgentChatClientFactory/
                    // GroqAgentChatClientFactory's own BASE_URL constants exactly (2026-08-03).
                    ProviderName.MISTRAL, new KnownEndpoint("https://api.mistral.ai", null),
                    ProviderName.DEEPSEEK, new KnownEndpoint("https://api.deepseek.com", null),
                    ProviderName.GROQ, new KnownEndpoint("https://api.groq.com/openai", null),
                    // Matches MetaAgentChatClientFactory's BASE_URL (2026-09-17); the null
                    // completionsPath means the conventional "/v1/chat/completions".
                    ProviderName.META, new KnownEndpoint("https://api.meta.ai", null));

    record KnownEndpoint(String baseUrl, String completionsPath) {}
}
