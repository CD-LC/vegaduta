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
 * Flag-only / routing-hint caching strategy for Mistral AI, same shape as
 * {@link OpenAIProviderCachingStrategy}. Mistral's chat completions API
 * documents its own {@code prompt_cache_key} field (docs.mistral.ai/studio-api/
 * conversations/advanced/prompt-caching): caching itself is automatic and
 * server-side for any request whose prefix is a repeat of a recent one (64-
 * token cache blocks, cached tokens billed at 10% of standard input price) -
 * {@code prompt_cache_key} is purely an optional consistency hint that raises
 * the odds two requests sharing a prefix land on the same cache partition,
 * exactly like OpenAI's field of the same name. There is no cache-object
 * lifecycle to manage (no TTL/strategy enum), so this needs no more than
 * OpenAI's strategy did.
 *
 * <p>{@link MistralAgentChatClientFactory} reuses Spring AI's {@code
 * OpenAiChatOptions}/{@code OpenAiApi} directly (Mistral's API is OpenAI-
 * chat-completions-compatible and has no dedicated Spring AI starter) - so
 * this strategy's generic type is {@code OpenAiChatOptions.Builder}, the same
 * concrete class {@link OpenAIProviderCachingStrategy} uses, and {@code
 * OpenAiChatOptions#promptCacheKey} serializes to the exact {@code
 * "prompt_cache_key"} JSON field Mistral's API documents - no new
 * serialization plumbing needed.
 */
@Component
public class MistralProviderCachingStrategy implements ProviderCachingStrategy<OpenAiChatOptions.Builder> {

    private final boolean enabled;

    public MistralProviderCachingStrategy(
        @Value("${app.llm.mistral.prompt-caching.enabled:true}") boolean enabled
    ) {
        this.enabled = enabled;
    }

    // SHA-256 of the BYOK api key, hex-encoded and truncated - same reasoning
    // as OpenAIProviderCachingStrategy#promptCacheKeyFor: a short opaque
    // per-key routing token, never the raw key itself.
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
        return ProviderName.MISTRAL;
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
