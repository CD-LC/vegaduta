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
 * Flag-only / routing-hint caching strategy for OpenAI. Unlike Anthropic,
 * OpenAI's prompt caching is automatic server-side for any request whose
 * prefix is &gt;=1024 tokens and repeats a recent request - there is no
 * {@code cache_control}-style opt-in and no cache-object lifecycle to manage.
 * All this strategy does is set {@code OpenAiChatOptions#promptCacheKey}
 * (platform.openai.com/docs/guides/prompt-caching), an optional routing hint
 * that increases cache-hit consistency by steering a given key's requests to
 * the same backend cache partition. Moved into this class, unchanged, from
 * where {@link OpenAIAgentChatClientFactory} inlined it earlier in this same
 * uncommitted work (this logic has no prior commit history of its own yet -
 * see why the key is derived from a hash of the BYOK API key rather than a
 * tenant/agent id in this class's own reasoning above, not a linked history).
 *
 * <p><b>Per-agent routing (2026-09-28).</b> A key derived from the API key
 * alone is ONE routing key for every agent on that credential, although each
 * agent has a different system-prompt prefix. OpenAI routes by prefix hash
 * combined with this key, and a single key receiving traffic for many
 * different prefixes spreads them over the same partition and overflows it
 * sooner - worse hit rates for everyone on the tenant. {@link
 * #promptCacheKeyFor(String, String)} therefore appends a short hash of the
 * system prompt, so each distinct agent prefix gets its own routing key while
 * identical prefixes still share one. It is only usable where the system
 * prompt is known, i.e. per REQUEST: {@link #apply} runs when the ChatClient
 * is BUILT, from a {@link ProviderCachingContext} that carries only {@code
 * (apiKey, model)}, so it keeps the credential-only key. {@link
 * #applyToRequestOptions} is the per-request entry point; it has no caller
 * yet - wiring it needs the OpenAI factory (or the chat service) to pass a
 * per-request {@link OpenAiChatOptions}, which is outside this class.
 */
@Component
public class OpenAIProviderCachingStrategy implements ProviderCachingStrategy<OpenAiChatOptions.Builder> {

    /** Hex chars of the system-prompt hash appended to the credential key - short on purpose, it is a routing token. */
    private static final int SYSTEM_PROMPT_HASH_HEX_CHARS = 12;

    private final boolean enabled;

    public OpenAIProviderCachingStrategy(
        @Value("${app.llm.openai.prompt-caching.enabled:true}") boolean enabled
    ) {
        this.enabled = enabled;
    }

    // SHA-256 of the BYOK api key, hex-encoded and truncated - OpenAI's
    // prompt_cache_key has no documented length ceiling that matters here,
    // truncated purely to keep it a short opaque routing token rather than a
    // full 64-char hash. Never the raw key.
    private static String promptCacheKeyFor(String apiKey) {
        return sha256Hex(apiKey, 32);
    }

    /**
     * Routing key for one (credential, system prompt) pair: {@code
     * <credential hash>-<system prompt hash>}. Falls back to the
     * credential-only key when {@code systemPrompt} is null/blank, so a call
     * without a system prompt routes exactly as before. Neither the raw key
     * nor the prompt text ever appears in the result. {@code null} only if
     * hashing is unavailable (never, in practice).
     */
    static String promptCacheKeyFor(String apiKey, String systemPrompt) {
        String credentialKey = promptCacheKeyFor(apiKey);
        if (credentialKey == null || systemPrompt == null || systemPrompt.isBlank()) {
            return credentialKey;
        }
        String promptHash = sha256Hex(systemPrompt, SYSTEM_PROMPT_HASH_HEX_CHARS);
        return promptHash == null ? credentialKey : credentialKey + "-" + promptHash;
    }

    private static String sha256Hex(String value, int hexChars) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, hexChars);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm; unreachable in practice.
            // Fail open to "no cache key" rather than fail the ChatClient.
            return null;
        }
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.OPENAI;
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

    /**
     * Per-request variant of {@link #apply}: sets a routing key that also
     * reflects {@code systemPrompt} on the request's own options. No-op when
     * disabled or when {@code options} is null. See the class javadoc - no
     * caller is wired yet.
     */
    public OpenAiChatOptions applyToRequestOptions(OpenAiChatOptions options, String apiKey, String systemPrompt) {
        if (options == null || !isEnabled(options.getModel())) {
            return options;
        }
        String cacheKey = promptCacheKeyFor(apiKey, systemPrompt);
        if (cacheKey != null) {
            options.setPromptCacheKey(cacheKey);
        }
        return options;
    }
}
