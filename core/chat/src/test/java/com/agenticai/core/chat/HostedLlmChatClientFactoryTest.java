package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.chat.HostedLlmChatClientFactory.ResolvedEndpoint;
import com.agenticai.core.providers.PlatformHostedLlmProvider;
import org.junit.jupiter.api.Test;

/**
 * Locks in the {@code baseUrl}/{@code completionsPath} shaping decision that {@link
 * HostedLlmChatClientFactory} feeds Spring AI's {@code OpenAiApi}.
 *
 * <p>The interesting case is a provider whose OpenAI-compat endpoint is NOT {@code
 * {baseUrl}/v1/chat/completions} - the motivating example being Google Gemini ({@code
 * https://generativelanguage.googleapis.com/v1beta/openai/chat/completions}, with no bare {@code
 * /v1} segment). For such a row the factory must (a) set a custom {@code completionsPath} and (b)
 * leave {@code baseUrl} untouched - blindly stripping {@code /v1} (correct for conventional
 * Groq/Together rows) would be exactly wrong here. Verified through {@link
 * HostedLlmChatClientFactory#resolveEndpoint}, the seam that isolates this decision so it is
 * testable without {@code OpenAiApi}'s package-private internals.
 */
class HostedLlmChatClientFactoryTest {

    private static PlatformHostedLlmProvider row(String baseUrl, String completionsPath) {
        return new PlatformHostedLlmProvider(
                "test", baseUrl, "some-model", "enc-key", true, 100, null, completionsPath);
    }

    @Test
    void customCompletionsPathIsPassedThroughAndBaseUrlIsUsedVerbatim() {
        // The Gemini row an admin would actually enter.
        PlatformHostedLlmProvider gemini =
                row("https://generativelanguage.googleapis.com", "/v1beta/openai/chat/completions");

        ResolvedEndpoint endpoint = HostedLlmChatClientFactory.resolveEndpoint(gemini);

        assertEquals("https://generativelanguage.googleapis.com", endpoint.baseUrl());
        assertEquals("/v1beta/openai/chat/completions", endpoint.completionsPath());
    }

    @Test
    void customPathRowDoesNotGetV1StrippedFromBaseUrl() {
        // Even if a custom-path provider's base URL happens to end in /v1, we
        // must NOT strip it: with a custom completionsPath the base URL is the
        // literal host prefix, not the OpenAI-convention "host minus /v1".
        PlatformHostedLlmProvider p = row("https://example.com/api/v1", "/openai/chat/completions");

        ResolvedEndpoint endpoint = HostedLlmChatClientFactory.resolveEndpoint(p);

        assertEquals("https://example.com/api/v1", endpoint.baseUrl());
        assertEquals("/openai/chat/completions", endpoint.completionsPath());
    }

    @Test
    void nullCompletionsPathKeepsExistingBehaviourDefaultPathAndV1Stripping() {
        // Conventional row: no custom path, and normalizeBaseUrl still strips a
        // trailing /v1 (the pre-existing double-"/v1" 404 guard) so OpenAiApi's
        // default /v1/chat/completions lands correctly.
        PlatformHostedLlmProvider togetherWithTrailingV1 = row("https://api.together.xyz/v1", null);

        ResolvedEndpoint endpoint =
                HostedLlmChatClientFactory.resolveEndpoint(togetherWithTrailingV1);

        assertEquals("https://api.together.xyz", endpoint.baseUrl());
        assertNull(endpoint.completionsPath());
    }

    @Test
    void blankCompletionsPathIsTreatedAsAbsentNotAsACustomPath() {
        PlatformHostedLlmProvider p = row("https://api.groq.com/openai", "   ");

        ResolvedEndpoint endpoint = HostedLlmChatClientFactory.resolveEndpoint(p);

        assertEquals("https://api.groq.com/openai", endpoint.baseUrl());
        assertNull(endpoint.completionsPath());
    }

    // ---- per-agent sampling threading (stopSequences etc. were silently dropped) ----

    private static final SamplingOptions FULL =
            new SamplingOptions(40, java.util.List.of("END", "STOP"), 0.4, 0.3, 7);

    @Test
    void openAiCompatRowReceivesStopPenaltiesAndSeedButNotTopK() {
        var options =
                HostedLlmChatClientFactory.openAiCompatOptionsBuilder(
                                "llama-3.3-70b", true, 512, 0.7, 0.9, FULL)
                        .build();

        assertEquals(java.util.List.of("END", "STOP"), options.getStop());
        assertEquals(0.4, options.getFrequencyPenalty());
        assertEquals(0.3, options.getPresencePenalty());
        assertEquals(7, options.getSeed());
        assertEquals(512, options.getMaxTokens());
        assertEquals(0.7, options.getTemperature());
    }

    @Test
    void noSamplingLeavesRequestUntouched() {
        var options =
                HostedLlmChatClientFactory.openAiCompatOptionsBuilder(
                                "llama-3.3-70b", true, 512, 0.7, 0.9, null)
                        .build();

        assertNull(options.getStop());
        assertNull(options.getFrequencyPenalty());
        assertNull(options.getPresencePenalty());
        assertNull(options.getSeed());
    }

    @Test
    void reasoningModelKeepsOnlySeed() {
        var options =
                HostedLlmChatClientFactory.openAiCompatOptionsBuilder(
                                "gpt-5-mini", true, 512, 0.7, 0.9, FULL)
                        .build();

        assertNull(options.getStop());
        assertNull(options.getFrequencyPenalty());
        assertNull(options.getPresencePenalty());
        assertEquals(7, options.getSeed());
        assertEquals(512, options.getMaxCompletionTokens());
    }

    @Test
    void vertexOptionsReceiveTopKStopAndPenalties() {
        var builder =
                org.springframework.ai.google.genai.GoogleGenAiChatOptions.builder()
                        .model("gemini-2.5-flash");
        FULL.applyTo(builder);
        var options = builder.build();

        assertEquals(40, options.getTopK());
        assertEquals(java.util.List.of("END", "STOP"), options.getStopSequences());
        assertEquals(0.4, options.getFrequencyPenalty());
        assertEquals(0.3, options.getPresencePenalty());
    }

    @Test
    void blankOrNullKeyTakesTheKeylessNoAuthPath() {
        assertTrue(HostedLlmChatClientFactory.isKeyless(null));
        assertTrue(HostedLlmChatClientFactory.isKeyless(""));
        assertTrue(HostedLlmChatClientFactory.isKeyless("   "));
    }

    @Test
    void realKeyKeepsTheBearerPath() {
        assertFalse(HostedLlmChatClientFactory.isKeyless("sk-abc"));
        assertFalse(HostedLlmChatClientFactory.isKeyless("ollama"));
    }
}
