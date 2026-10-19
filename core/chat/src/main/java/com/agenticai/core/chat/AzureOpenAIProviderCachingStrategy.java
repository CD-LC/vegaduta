package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Flag-only / routing-hint caching strategy for Azure OpenAI, same shape as
 * {@link OpenAIProviderCachingStrategy}. Azure's data-plane chat completions
 * endpoint auto-caches any request whose first 1024+ tokens repeat a recent
 * request (learn.microsoft.com/azure/ai-foundry/openai/how-to/prompt-caching)
 * with no opt-in required and no cache-object lifecycle to manage - the only
 * request-side lever is the same {@code prompt_cache_key} consistency hint
 * OpenAI itself documents (most load-bearing on GPT-5.6+ deployments, where
 * it combines with the prefix hash to improve matching; harmless as an
 * optional field on older deployments, which simply ignore it - no cache-miss
 * behavior changes by sending it). Since {@link AzureOpenAIAgentChatClientFactory}
 * already reuses Spring AI's {@code OpenAiChatOptions}/{@code OpenAiApi}
 * directly (Azure's data plane is OpenAI-chat-completions-compatible), this
 * strategy's generic type is {@code OpenAiChatOptions.Builder} - the exact
 * same concrete class {@link OpenAIProviderCachingStrategy} uses - so {@code
 * promptCacheKey} serializes to the documented {@code "prompt_cache_key"}
 * field with no new plumbing.
 */
@Component
public class AzureOpenAIProviderCachingStrategy implements ProviderCachingStrategy<OpenAiChatOptions.Builder> {

    private final boolean enabled;

    public AzureOpenAIProviderCachingStrategy(
        @Value("${app.llm.azure-openai.prompt-caching.enabled:true}") boolean enabled
    ) {
        this.enabled = enabled;
    }

    // SHA-256 of the BYOK api key, hex-encoded and truncated - same reasoning
    // as OpenAIProviderCachingStrategy#promptCacheKeyFor: a short opaque
    // per-credential routing token, never the raw key/deployment secret.
    private static String promptCacheKeyFor(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm; unreachable in practice.
            // Fail open to "no cache key" rather than fail the ChatClient.
            return null;
        }
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.AZURE_OPENAI;
    }

    @Override
    public boolean isEnabled(String model) {
        return enabled;
    }

    @Override
    public OpenAiChatOptions.Builder apply(OpenAiChatOptions.Builder optionsBuilder, ProviderCachingContext context) {
        if (!isEnabled(context.model())) {
            return optionsBuilder;
        }
        String cacheKey = promptCacheKeyFor(context.apiKey());
        if (cacheKey != null) {
            optionsBuilder.promptCacheKey(cacheKey);
        }
        return optionsBuilder;
    }
}
