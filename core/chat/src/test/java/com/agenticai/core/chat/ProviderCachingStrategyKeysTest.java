package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * OpenAI prompt_cache_key per-agent routing, and the Gemini/Vertex explicit caching strategies
 * being inert no-ops (see GeminiProviderCachingStrategy's javadoc for why wiring them at the
 * build-time hook is unsafe).
 */
class ProviderCachingStrategyKeysTest {

    // --- OpenAI ------------------------------------------------------------------

    @Test
    void buildTimeKeyIsUnchangedCredentialOnlyHash() {
        OpenAIProviderCachingStrategy strategy = new OpenAIProviderCachingStrategy(true);
        OpenAiChatOptions options =
                strategy.apply(
                                OpenAiChatOptions.builder().model("gpt-4.1"),
                                new ProviderCachingContext("sk-test", "gpt-4.1"))
                        .build();

        String key = options.getPromptCacheKey();
        assertEquals(32, key.length(), "credential-only key keeps its 32-hex shape");
        assertFalse(key.contains("sk-test"));
        assertEquals(key, OpenAIProviderCachingStrategy.promptCacheKeyFor("sk-test", null));
    }

    @Test
    void systemPromptHashSeparatesAgentsOnOneCredential() {
        String a = OpenAIProviderCachingStrategy.promptCacheKeyFor("sk-test", "You are agent A");
        String b = OpenAIProviderCachingStrategy.promptCacheKeyFor("sk-test", "You are agent B");
        String a2 = OpenAIProviderCachingStrategy.promptCacheKeyFor("sk-test", "You are agent A");
        String credentialOnly = OpenAIProviderCachingStrategy.promptCacheKeyFor("sk-test", "  ");

        assertNotEquals(a, b);
        assertEquals(a, a2, "identical prefixes must share a routing key");
        assertTrue(
                a.startsWith(credentialOnly + "-"),
                "per-agent key extends the credential key: " + a);
        assertFalse(a.contains("agent A"), "prompt text never appears in the key");
    }

    @Test
    void differentCredentialsNeverShareAKeyForTheSamePrompt() {
        assertNotEquals(
                OpenAIProviderCachingStrategy.promptCacheKeyFor("sk-one", "sys"),
                OpenAIProviderCachingStrategy.promptCacheKeyFor("sk-two", "sys"));
    }

    @Test
    void perRequestOptionsGetTheSystemPromptAwareKey() {
        OpenAIProviderCachingStrategy strategy = new OpenAIProviderCachingStrategy(true);
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("gpt-4.1").build();

        strategy.applyToRequestOptions(options, "sk-test", "sys");

        assertEquals(
                OpenAIProviderCachingStrategy.promptCacheKeyFor("sk-test", "sys"),
                options.getPromptCacheKey());
    }

    @Test
    void disabledStrategySetsNoKey() {
        OpenAIProviderCachingStrategy strategy = new OpenAIProviderCachingStrategy(false);
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("gpt-4.1").build();

        strategy.applyToRequestOptions(options, "sk-test", "sys");
        OpenAiChatOptions built =
                strategy.apply(
                                OpenAiChatOptions.builder().model("gpt-4.1"),
                                new ProviderCachingContext("sk-test", "gpt-4.1"))
                        .build();

        assertNull(options.getPromptCacheKey());
        assertNull(built.getPromptCacheKey());
    }

    // --- Gemini / Vertex ---------------------------------------------------------

    @Test
    void geminiApplyNeverAttachesCachedContentEvenWhenFlagIsOn() {
        GeminiProviderCachingStrategy strategy = new GeminiProviderCachingStrategy(true);
        GoogleGenAiChatOptions.Builder builder =
                GoogleGenAiChatOptions.builder().model("gemini-2.5-flash");

        assertSame(
                builder,
                strategy.apply(builder, new ProviderCachingContext("key", "gemini-2.5-flash")));
        GoogleGenAiChatOptions options = builder.build();
        assertFalse(Boolean.TRUE.equals(options.getUseCachedContent()));
        assertNull(options.getCachedContentName());
        assertFalse(
                strategy.isEnabled("gemini-2.5-flash"),
                "an inert strategy must not report itself enabled");
    }

    @Test
    void vertexApplyNeverAttachesCachedContentEvenWhenFlagIsOn() {
        VertexProviderCachingStrategy strategy = new VertexProviderCachingStrategy(true);
        GoogleGenAiChatOptions.Builder builder =
                GoogleGenAiChatOptions.builder().model("gemini-2.5-flash");

        assertSame(
                builder,
                strategy.apply(builder, new ProviderCachingContext("encoded", "gemini-2.5-flash")));
        GoogleGenAiChatOptions options = builder.build();
        assertFalse(Boolean.TRUE.equals(options.getUseCachedContent()));
        assertNull(options.getCachedContentName());
        assertFalse(strategy.isEnabled("gemini-2.5-flash"));
    }
}
