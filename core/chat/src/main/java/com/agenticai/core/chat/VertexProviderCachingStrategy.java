package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link ProviderName#GOOGLE_VERTEX} counterpart to {@link
 * GeminiProviderCachingStrategy} - and, like it, <b>deliberately a documented
 * no-op</b> (2026-09-28). Vertex and the Gemini Developer API share one Spring
 * AI model class ({@code GoogleGenAiChatModel}, see {@link
 * VertexAiChatClientFactory}'s javadoc), so every reason explicit {@code
 * cachedContent} is unsafe to wire for Gemini applies here unchanged: {@link
 * #apply} runs at ChatClient build time with no system prompt or tools in
 * scope (a cache attached here would bleed one agent's system prompt into
 * another's on the same credential and model), and Spring AI 1.1.7 always
 * re-sends {@code system_instruction} and {@code tools}, which the API rejects
 * alongside {@code cachedContent}. Read {@link GeminiProviderCachingStrategy}'s
 * class javadoc for the full analysis and the shape of a safe follow-up.
 *
 * <p>The previous, never-called {@code ensureCache} creation path (which
 * exchanged the encoded Vertex credential for a bearer token and uploaded tool
 * schemas as plain text) was removed with it; {@link GoogleGenAiCacheManager}
 * keeps the content-keyed, TTL-evicting bookkeeping a future per-request
 * implementation would need.
 *
 * <p>{@code app.llm.vertex.prompt-caching.enabled} still binds (default
 * {@code false}); {@code true} logs a startup WARN that it is inert. Vertex
 * implicit caching is server-side and unaffected.
 */
@Component
public class VertexProviderCachingStrategy implements ProviderCachingStrategy<GoogleGenAiChatOptions.Builder> {

    private static final Logger log = LoggerFactory.getLogger(VertexProviderCachingStrategy.class);

    public VertexProviderCachingStrategy(
        @Value("${app.llm.vertex.prompt-caching.enabled:false}") boolean enabled
    ) {
        if (enabled) {
            log.warn("app.llm.vertex.prompt-caching.enabled=true has no effect: explicit Vertex cachedContent is "
                + "not wired (see GeminiProviderCachingStrategy javadoc). Vertex implicit caching still applies.");
        }
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.GOOGLE_VERTEX;
    }

    /** Always {@code false}: nothing is ever applied, whatever the flag says - see the class javadoc. */
    @Override
    public boolean isEnabled(String model) {
        return false;
    }

    /** Always returns {@code optionsBuilder} unchanged - see the class javadoc. */
    @Override
    public GoogleGenAiChatOptions.Builder apply(GoogleGenAiChatOptions.Builder optionsBuilder, ProviderCachingContext context) {
        return optionsBuilder;
    }
}
