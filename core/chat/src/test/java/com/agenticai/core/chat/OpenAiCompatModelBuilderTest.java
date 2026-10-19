package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.providers.ProviderName;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The seam every OpenAI-compatible / Anthropic factory builds through (ADR-0012 P1b). The wire-level
 * equivalence with the old inline construction is {@link LlmFactoryWireParityTest}; this pins the
 * builder's own contract: options base, transport selection (guarded vs plain vs overridden) and
 * model wiring.
 */
class OpenAiCompatModelBuilderTest {

    private final LlmHttpClientFactory http = mock(LlmHttpClientFactory.class);
    private final ResilientToolCallingManagerFactory tools = new ResilientToolCallingManagerFactory(JsonMappers.plain());
    private final LlmModelSupport support = support("MISTRAL");
    private final OpenAiCompatModelBuilder builder = new OpenAiCompatModelBuilder(http, tools, support);

    @SuppressWarnings("unchecked")
    private static LlmModelSupport support(String streamUsageDisabled) {
        ObjectProvider<ObservationRegistry> obs = mock(ObjectProvider.class);
        when(obs.getIfAvailable(any())).thenReturn(ObservationRegistry.NOOP);
        ObjectProvider<DefaultAgentAdvisor> adv = mock(ObjectProvider.class);
        when(adv.orderedStream()).thenAnswer(i -> Stream.<DefaultAgentAdvisor>empty());
        return new LlmModelSupport(obs, adv, 2, Duration.ofMillis(1), Duration.ofMillis(2), true,
            streamUsageDisabled, "low", 16, Duration.ofMinutes(30), false);
    }

    private void stubTransports() {
        when(http.restClientBuilder()).thenAnswer(i -> RestClient.builder());
        when(http.guardedRestClientBuilder()).thenAnswer(i -> RestClient.builder());
        when(http.webClientBuilder()).thenAnswer(i -> WebClient.builder());
        when(http.guardedWebClientBuilder()).thenAnswer(i -> WebClient.builder());
    }

    @Test
    void optionsCarryModelAndStreamUsageFromThePerProviderSetting() {
        OpenAiChatOptions openAi = builder.options(ProviderName.OPENAI, "gpt-4o").build();
        assertEquals("gpt-4o", openAi.getModel());
        assertEquals(Boolean.TRUE, openAi.getStreamUsage());
        // Mistral is disabled by default in app.llm.stream-usage.disabled-providers.
        OpenAiChatOptions mistral = builder.options(ProviderName.MISTRAL, "mistral-large").build();
        assertEquals("mistral-large", mistral.getModel());
        assertEquals(Boolean.FALSE, mistral.getStreamUsage());
    }

    @Test
    void baseOptionsMirrorsTheStreamUsageFlagVerbatim() {
        assertEquals(Boolean.TRUE, OpenAiCompatModelBuilder.baseOptions("m", true).build().getStreamUsage());
        assertEquals(Boolean.FALSE, OpenAiCompatModelBuilder.baseOptions("m", false).build().getStreamUsage());
        assertEquals("m", OpenAiCompatModelBuilder.baseOptions("m", true).build().getModel());
    }

    @Test
    void plainApiUsesTheBoundedNonGuardedTransport() {
        stubTransports();
        OpenAiApi api = builder.api("https://api.example.com").apiKey("k").build();
        assertNotNull(api);
        verify(http).restClientBuilder();
        verify(http).webClientBuilder();
        verify(http, never()).guardedRestClientBuilder();
        verify(http, never()).guardedWebClientBuilder();
    }

    @Test
    void guardedApiUsesTheGuardedTransportOnly() {
        stubTransports();
        builder.api("https://tenant.example.com").guarded().noApiKey().build();
        verify(http).guardedRestClientBuilder();
        verify(http).guardedWebClientBuilder();
        verify(http, never()).restClientBuilder();
        verify(http, never()).webClientBuilder();
    }

    @Test
    void explicitTransportBuildersOverrideTheDefaults() {
        stubTransports();
        RestClient.Builder rest = RestClient.builder();
        WebClient.Builder web = WebClient.builder();
        builder.api("https://api.example.com").apiKey("k").restClientBuilder(rest).webClientBuilder(web).build();
        verify(http, never()).restClientBuilder();
        verify(http, never()).webClientBuilder();
    }

    @Test
    void customCompletionsPathHeadersAndNoKeyBuild() {
        stubTransports();
        LinkedMultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.add("api-key", "x");
        assertNotNull(builder.api("https://r.openai.azure.com").noApiKey().headers(headers)
            .completionsPath("/openai/deployments/d/chat/completions?api-version=1").guarded().build());
        assertNotNull(builder.api("https://api.example.com").apiKey("k").completionsPath(null).build());
    }

    @Test
    void chatModelKeepsTheGivenDefaultOptions() {
        stubTransports();
        OpenAiApi api = builder.api("https://api.example.com").apiKey("k").build();
        OpenAiChatOptions options = builder.options(ProviderName.OPENAI, "gpt-4o").maxTokens(77).temperature(0.3).build();
        OpenAiChatModel model = builder.chatModel(api, options);
        OpenAiChatOptions defaults = (OpenAiChatOptions) model.getDefaultOptions();
        assertEquals("gpt-4o", defaults.getModel());
        assertEquals(77, defaults.getMaxTokens());
        assertEquals(0.3, defaults.getTemperature());
        assertEquals(Boolean.TRUE, defaults.getStreamUsage());
    }

    @Test
    void restAndWebBuildersAreFreshEveryCall() {
        when(http.restClientBuilder()).thenAnswer(i -> RestClient.builder());
        RestClient.Builder a = builder.restClientBuilder();
        RestClient.Builder b = builder.restClientBuilder();
        assertFalse(a == b, "Spring AI *Api builders mutate the builder, so each call must be fresh");
        assertTrue(a != null && b != null);
    }

    // ------------------------------------------------------------ Anthropic

    @Test
    void anthropicBuilderWiresApiAndOptions() {
        stubTransports();
        AnthropicModelBuilder anthropic = new AnthropicModelBuilder(http, tools, support);
        AnthropicApi api = anthropic.api("sk-an");
        assertNotNull(api);
        AnthropicChatOptions options = AnthropicChatOptions.builder().model("claude-sonnet-4").maxTokens(99).build();
        AnthropicChatModel model = anthropic.chatModel(api, options);
        AnthropicChatOptions defaults = (AnthropicChatOptions) model.getDefaultOptions();
        assertEquals("claude-sonnet-4", defaults.getModel());
        assertEquals(99, defaults.getMaxTokens());
        verify(http).restClientBuilder();
        verify(http).webClientBuilder();
        assertSame(http, http);
    }
}
