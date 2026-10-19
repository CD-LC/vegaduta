package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gemini (AI Studio) explicit prompt caching - <b>deliberately a documented
 * no-op</b> (2026-09-28). Gemini's implicit caching, which Google applies
 * server-side to repeated prefixes with no client involvement, is unaffected
 * by this class and is what actually runs today.
 *
 * <h2>Why explicit caching is not wired</h2>
 * Explicit caching (ai.google.dev/gemini-api/docs/caching) is a separate
 * Caches API call that uploads the literal prefix - system instruction plus
 * tool declarations - and returns a {@code cachedContents/...} name for later
 * requests to reference. The previous version of this class had a creation
 * path ({@code ensureCache}) that nothing called, and a reuse path in {@link
 * #apply} that could therefore never find a cache. Wiring it was reviewed on
 * 2026-09-28 and rejected as unsafe, for four independent reasons:
 * <ol>
 *   <li><b>{@link #apply} runs at ChatClient BUILD time.</b> Its only input is
 *       {@link ProviderCachingContext} {@code (apiKey, model)} - the system
 *       prompt and tools do not exist yet; each caller adds them later via
 *       {@code chatClient.prompt().system(...).tools(...)}, and one built
 *       client is reused across agents. Any cache attached here would be keyed
 *       on credential+model only, so agent A's cached system prompt would be
 *       silently served to agent B on the same key and model (cross-agent
 *       system-prompt bleed).</li>
 *   <li><b>Spring AI 1.1.7 always re-sends the system instruction and tools.</b>
 *       Decompiled {@code GoogleGenAiChatModel#createGeminiRequest} (pinned
 *       spring-ai-google-genai-1.1.7.jar): it sets {@code
 *       GenerateContentConfig.cachedContent(name)} when {@code
 *       useCachedContent} is on, and then unconditionally sets {@code
 *       systemInstruction} from the prompt's system message and {@code tools}
 *       from the options' tool callbacks. Gemini rejects a request that
 *       carries {@code cachedContent} together with {@code system_instruction}
 *       or {@code tools}, so turning the cache on would fail every agent turn
 *       that has a system prompt or a tool.</li>
 *   <li><b>Tools cannot simply be stripped.</b> Spring AI's tool-calling loop
 *       resolves which callback to execute from those same options; removing
 *       tool declarations to satisfy (2) would break tool execution unless a
 *       custom ChatModel re-attached them - a much larger change than a
 *       caching flag justifies.</li>
 *   <li><b>The old creation path stored tool schemas as plain TEXT content</b>
 *       ({@code addTextContent}), so even a created cache held prose, not
 *       function declarations the model could call.</li>
 * </ol>
 * The safe version needs a per-request hook (an advisor that sees the final
 * system prompt and tool list, strips both from the outgoing request, and
 * references a cache keyed on credential + model + system prompt + tool
 * schemas - {@link GoogleGenAiCacheManager#cacheKey} already builds exactly
 * that key, with bounded TTL-evicting bookkeeping) plus a Gemini ChatModel
 * that keeps tool callbacks for execution while omitting their declarations
 * from the wire request. That spans the Gemini/Vertex factories and the chat
 * model subclass, and is left as a tracked follow-up rather than done here.
 *
 * <p>{@code app.llm.gemini.prompt-caching.enabled} is kept (default {@code
 * false}) so existing configuration still binds; setting it {@code true}
 * logs a startup WARN that it is inert instead of silently doing nothing.
 */
@Component
public class GeminiProviderCachingStrategy implements ProviderCachingStrategy<GoogleGenAiChatOptions.Builder> {

    private static final Logger log = LoggerFactory.getLogger(GeminiProviderCachingStrategy.class);

    public GeminiProviderCachingStrategy(
        @Value("${app.llm.gemini.prompt-caching.enabled:false}") boolean enabled
    ) {
        if (enabled) {
            log.warn("app.llm.gemini.prompt-caching.enabled=true has no effect: explicit Gemini cachedContent is "
                + "not wired (see GeminiProviderCachingStrategy javadoc). Gemini implicit caching still applies.");
        }
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.GEMINI;
    }

    /** Always {@code false}: nothing is ever applied, whatever the flag says - see the class javadoc. */
    @Override
    public boolean isEnabled(String model) {
        return false;
    }

    /**
     * Always returns {@code optionsBuilder} unchanged - never sets {@code
     * useCachedContent}/{@code cachedContentName}. See the class javadoc for
     * why attaching a cache at this build-time hook would be both wrong
     * (cross-agent bleed) and rejected by the API (system instruction and
     * tools are still sent).
     */
    @Override
    public GoogleGenAiChatOptions.Builder apply(GoogleGenAiChatOptions.Builder optionsBuilder, ProviderCachingContext context) {
        return optionsBuilder;
    }
}
