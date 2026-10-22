package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.providers.ProviderName;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The seam every OpenAI-compatible / Anthropic factory builds through (ADR-0012 P1b, P3). The
 * wire-level equivalence with the old inline construction is {@link LlmFactoryWireParityTest}; this
 * pins the builder's own contract: options base, request-URL derivation (the SDK has no {@code
 * completionsPath}), legacy-wire normalisation, transport selection (guarded vs plain) and model
 * wiring.
 */
class OpenAiCompatModelBuilderTest {

    private final LlmHttpClientFactory http = mock(LlmHttpClientFactory.class);
    private final ResilientToolCallingManagerFactory tools =
            new ResilientToolCallingManagerFactory(JsonMappers.plain());
    private final LlmModelSupport support = support("MISTRAL");
    private final OpenAiCompatModelBuilder builder =
            new OpenAiCompatModelBuilder(http, tools, support);

    @SuppressWarnings("unchecked")
    private static LlmModelSupport support(String streamUsageDisabled) {
        ObjectProvider<ObservationRegistry> obs = mock(ObjectProvider.class);
        when(obs.getIfAvailable(any())).thenReturn(ObservationRegistry.NOOP);
        ObjectProvider<DefaultAgentAdvisor> adv = mock(ObjectProvider.class);
        when(adv.orderedStream()).thenAnswer(i -> Stream.<DefaultAgentAdvisor>empty());
        return new LlmModelSupport(
                obs,
                adv,
                2,
                Duration.ofMillis(1),
                Duration.ofMillis(2),
                true,
                streamUsageDisabled,
                "low",
                16,
                Duration.ofMinutes(30),
                false);
    }

    private void stubTransport() {
        when(http.transport(anyBoolean())).thenAnswer(i -> new CannedLlmTransport(() -> "{}"));
    }

    private static boolean streamUsage(OpenAiChatOptions options) {
        return options.getStreamOptions() != null
                && Boolean.TRUE.equals(options.getStreamOptions().includeUsage());
    }

    @Test
    void optionsCarryModelAndStreamUsageFromThePerProviderSetting() {
        OpenAiChatOptions openAi = builder.options(ProviderName.OPENAI, "gpt-4o").build();
        assertEquals("gpt-4o", openAi.getModel());
        assertTrue(streamUsage(openAi));
        // Mistral is disabled by default in app.llm.stream-usage.disabled-providers.
        OpenAiChatOptions mistral = builder.options(ProviderName.MISTRAL, "mistral-large").build();
        assertEquals("mistral-large", mistral.getModel());
        assertFalse(streamUsage(mistral));
    }

    @Test
    void baseOptionsMirrorsTheStreamUsageFlagVerbatim() {
        assertTrue(streamUsage(OpenAiCompatModelBuilder.baseOptions("m", true).build()));
        assertFalse(streamUsage(OpenAiCompatModelBuilder.baseOptions("m", false).build()));
        assertEquals("m", OpenAiCompatModelBuilder.baseOptions("m", true).build().getModel());
    }

    // ------------------------------------------------------------------ URL

    @Test
    void defaultRequestUrlIsBasePlusV1ChatCompletionsLikeSpringAi1x() {
        assertEquals(
                "https://api.deepseek.com/v1/chat/completions",
                builder.api("https://api.deepseek.com").requestUrl());
        assertEquals(
                "http://ollama.example:11434/v1/chat/completions",
                builder.api("http://ollama.example:11434/").requestUrl());
        // The SDK appends "chat/completions" to its base URL, so it is handed base + "/v1".
        assertEquals(
                "https://openrouter.ai/api/v1",
                builder.api("https://openrouter.ai/api").sdkBaseUrl());
    }

    @Test
    void customCompletionsPathIsAppendedVerbatimIncludingItsQueryString() {
        assertEquals(
                "https://r.openai.azure.com/openai/deployments/d/chat/completions?api-version=1",
                builder.api("https://r.openai.azure.com")
                        .completionsPath("/openai/deployments/d/chat/completions?api-version=1")
                        .requestUrl());
        assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                builder.api("https://generativelanguage.googleapis.com/")
                        .completionsPath("/v1beta/openai/chat/completions")
                        .requestUrl());
        // A null path keeps the default.
        assertEquals(
                "https://api.example.com/v1/chat/completions",
                builder.api("https://api.example.com").completionsPath(null).requestUrl());
    }

    @Test
    void noApiKeyMeansTheSdksNoAuthModeAndAMissingKeyIsRejected() {
        assertEquals("", builder.api("https://x").noApiKey().sdkApiKey());
        assertEquals("k", builder.api("https://x").apiKey("k").sdkApiKey());
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.api("https://x").apiKey(null).sdkApiKey());
    }

    // ---------------------------------------------------------- legacy wire

    private static String legacy(String json, boolean dropStreamOptions) {
        return new String(
                OpenAiCompatModelBuilder.legacyWire(
                        json.getBytes(StandardCharsets.UTF_8), dropStreamOptions),
                StandardCharsets.UTF_8);
    }

    @Test
    void legacyWireRemovesIncludeObfuscationButKeepsIncludeUsage() {
        assertEquals(
                "{\"stream\":true,\"stream_options\":{\"include_usage\":true}}",
                legacy(
                        "{\"stream\":true,\"stream_options\":{\"include_obfuscation\":false,\"include_usage\":true}}",
                        false));
    }

    @Test
    void legacyWireDropsStreamOptionsEntirelyWhenTheProviderHasStreamUsageDisabled() {
        assertEquals(
                "{\"stream\":true}",
                legacy(
                        "{\"stream\":true,\"stream_options\":{\"include_obfuscation\":false,\"include_usage\":false}}",
                        true));
    }

    @Test
    void legacyWireDropsAnExplicitStrictFalseFromToolFunctionsButKeepsStrictTrue() {
        assertEquals(
                "{\"stream\":false,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"a\"}},"
                        + "{\"type\":\"function\",\"function\":{\"name\":\"b\",\"strict\":true}}]}",
                legacy(
                        "{\"stream\":false,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"strict\":false}},"
                                + "{\"type\":\"function\",\"function\":{\"name\":\"b\",\"strict\":true}}]}",
                        false));
    }

    @Test
    void legacyWireRestoresStreamFalseAndTheOneElementStopArray() {
        assertEquals(
                "{\"model\":\"m\",\"stop\":[\"END\"],\"stream\":false}",
                legacy("{\"model\":\"m\",\"stop\":\"END\"}", false));
        // A real stream request keeps its own "stream": true, and a stop ARRAY is untouched.
        assertEquals(
                "{\"stream\":true,\"stop\":[\"A\",\"B\"]}",
                legacy("{\"stream\":true,\"stop\":[\"A\",\"B\"]}", false));
    }

    @Test
    void anthropicLegacyWireRestoresStreamFalseAndBlockContentButNeverTouchesBlocks() {
        String out =
                new String(
                        AnthropicModelBuilder.legacyWire(
                                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"cache_control\":{\"type\":\"ephemeral\"}}]}]}"
                                        .getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8);
        assertEquals(
                "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]},"
                        + "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"cache_control\":{\"type\":\"ephemeral\"}}]}],\"stream\":false}",
                out);
    }

    @Test
    void legacyWireLeavesAnUnaffectedOrUnparseableBodyByteIdentical() {
        byte[] plain = "{\"model\":\"m\",\"stream\":false}".getBytes(StandardCharsets.UTF_8);
        assertSame(plain, OpenAiCompatModelBuilder.legacyWire(plain, false));
        byte[] garbage = "not json".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(garbage, OpenAiCompatModelBuilder.legacyWire(garbage, true));
    }

    // -------------------------------------------------------------- wiring

    @Test
    void plainApiUsesTheSharedNonGuardedTransport() {
        stubTransport();
        builder.chatModel(
                builder.api("https://api.example.com").apiKey("k"),
                builder.options(ProviderName.OPENAI, "gpt-4o").build());
        verify(http).transport(false);
        verify(http, never()).transport(true);
    }

    @Test
    void guardedApiUsesTheGuardedTransportOnly() {
        stubTransport();
        builder.chatModel(
                builder.api("https://tenant.example.com").guarded().noApiKey(),
                builder.options(ProviderName.OLLAMA, "llama3").build());
        verify(http).transport(true);
        verify(http, never()).transport(false);
    }

    @Test
    void chatModelKeepsTheGivenOptionsAndFoldsInTheEndpoint() {
        stubTransport();
        OpenAiChatOptions options =
                builder.options(ProviderName.OPENAI, "gpt-4o")
                        .maxTokens(77)
                        .temperature(0.3)
                        .build();
        OpenAiChatModel model =
                builder.chatModel(
                        builder.api("https://api.example.com")
                                .apiKey("k")
                                .headers(Map.of("x-h", "v")),
                        options);
        OpenAiChatOptions defaults = model.getOptions();
        assertEquals("gpt-4o", defaults.getModel());
        assertEquals(77, defaults.getMaxTokens());
        assertEquals(0.3, defaults.getTemperature());
        assertTrue(streamUsage(defaults));
        assertEquals("https://api.example.com/v1", defaults.getBaseUrl());
        assertEquals("k", defaults.getApiKey());
        assertEquals(
                0,
                defaults.getMaxRetries(),
                "the SDK's own retry loop is off; LlmRetryInterceptor is the policy");
        assertEquals(Map.of("x-h", "v"), defaults.getCustomHeaders());
    }

    // ------------------------------------------------------------ Anthropic

    @Test
    void anthropicBuilderWiresKeyOptionsAndTheSharedTransport() {
        stubTransport();
        AnthropicModelBuilder anthropic = new AnthropicModelBuilder(http, tools, support);
        AnthropicChatOptions options =
                AnthropicChatOptions.builder().model("claude-sonnet-4").maxTokens(99).build();
        AnthropicChatModel model = anthropic.chatModel("sk-an", options);
        AnthropicChatOptions defaults = (AnthropicChatOptions) model.getOptions();
        assertEquals("claude-sonnet-4", defaults.getModel());
        assertEquals(99, defaults.getMaxTokens());
        assertEquals(0, defaults.getMaxRetries());
        verify(http).transport(false);
        assertThrows(IllegalArgumentException.class, () -> anthropic.chatModel(null, options));
    }
}
