package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.providers.AzureOpenAiCredential;
import com.agenticai.core.providers.OllamaCredential;
import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wire-level characterisation of every LLM chat-client factory (ADR-0012 P1b). Each factory is
 * driven end to end against a canned HTTP transport and the request it emits (URL, auth headers
 * and JSON body: model, token limit, sampling, stream options, ...) is compared with a snapshot
 * recorded from the pre-refactor code ({@code llm-factory-wire-snapshot.txt}). The snapshot was
 * captured BEFORE the OpenAiCompatModelBuilder / AnthropicModelBuilder refactor, so a green run
 * proves the builders produce the same requests as the old inline construction.
 *
 * <p>Run with {@code -Dwire.snapshot.write=true} to (re)generate the snapshot; never do that to
 * make a refactor pass.
 */
class LlmFactoryWireParityTest {

    private static final ObjectMapper JSON = JsonMappers.plain();
    private static final ObjectMapper SORTED = JsonMappers.sortedMapKeys();
    private static final String OPENAI_REPLY =
        "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    private static final String ANTHROPIC_REPLY =
        "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\",\"content\":[{\"type\":\"text\","
            + "\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final List<String> HEADERS = List.of(
        "Authorization", "api-key", "api-subscription-key", "x-api-key", "anthropic-version", "Content-Type");

    private final List<String> captured = new ArrayList<>();
    private final Map<String, String> snapshot = new LinkedHashMap<>();
    private String reply = OPENAI_REPLY;

    private final ResilientToolCallingManagerFactory tools = new ResilientToolCallingManagerFactory(JsonMappers.plain());
    private final LlmCallMetricsService metrics = mock(LlmCallMetricsService.class);
    private final LlmCallLogService logs = mock(LlmCallLogService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final LlmPricingCatalog pricing = mock(LlmPricingCatalog.class);

    @SuppressWarnings("unchecked")
    private LlmHttpClientFactory http() {
        LlmHttpClientFactory http = mock(LlmHttpClientFactory.class);
        when(http.restClientBuilder()).thenAnswer(i -> restBuilder());
        when(http.guardedRestClientBuilder()).thenAnswer(i -> restBuilder());
        when(http.webClientBuilder()).thenAnswer(i -> WebClient.builder());
        when(http.guardedWebClientBuilder()).thenAnswer(i -> WebClient.builder());
        when(http.readTimeout()).thenReturn(Duration.ofSeconds(30));
        return http;
    }

    private RestClient.Builder restBuilder() {
        ClientHttpRequestFactory factory = (uri, method) -> new MockClientHttpRequest(method, uri) {
            @Override
            protected ClientHttpResponse executeInternal() {
                capture(this);
                MockClientHttpResponse response = new MockClientHttpResponse(
                    reply.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return response;
            }
        };
        return RestClient.builder().requestFactory(factory);
    }

    private void capture(MockClientHttpRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.getMethod()).append(' ').append(request.getURI()).append('\n');
        HttpHeaders headers = request.getHeaders();
        for (String name : HEADERS) {
            if (headers.containsKey(name)) {
                sb.append("  ").append(name).append(": ").append(String.join(",", headers.get(name))).append('\n');
            }
        }
        try {
            JsonNode body = JSON.readTree(request.getBodyAsString());
            Object tree = JSON.treeToValue(body, Object.class);
            sb.append("  body: ").append(SORTED.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsString(tree)).append('\n');
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        captured.add(sb.toString());
    }

    @SuppressWarnings("unchecked")
    private LlmModelSupport support() {
        ObjectProvider<ObservationRegistry> obs = mock(ObjectProvider.class);
        when(obs.getIfAvailable(any())).thenReturn(ObservationRegistry.NOOP);
        ObjectProvider<DefaultAgentAdvisor> adv = mock(ObjectProvider.class);
        when(adv.orderedStream()).thenAnswer(i -> java.util.stream.Stream.<DefaultAgentAdvisor>empty());
        return new LlmModelSupport(obs, adv, 2, Duration.ofMillis(1), Duration.ofMillis(2), true, "MISTRAL", "low",
            16, Duration.ofMinutes(30), false);
    }

    private void run(String label, ChatClient client) {
        captured.clear();
        client.prompt().user("hi").call().content();
        StringBuilder sb = new StringBuilder("### " + label + "\n");
        captured.forEach(sb::append);
        snapshot.put(label, sb.toString());
    }

    @Test
    void everyFactoryEmitsTheSameRequestAsBeforeTheBuilderRefactor() throws Exception {
        LlmHttpClientFactory http = http();
        LlmModelSupport support = support();
        SamplingOptions full = new SamplingOptions(40, List.of("END"), 0.4, 0.3, 7);
        Map<String, Object> sampling = Map.of(SamplingOptions.OPTION_KEY, full);

        run("deepseek", new DeepSeekAgentChatClientFactory(metrics, logs, meters, pricing, http, tools, support)
            .create("sk-ds", "deepseek-chat", 512, 0.7, 0.9, sampling));
        run("groq", new GroqAgentChatClientFactory(metrics, logs, meters, pricing, http, tools, support)
            .create("sk-groq", "llama-3.3-70b-versatile", 512, 0.7, 0.9, sampling));
        run("grok", new GrokAgentChatClientFactory(metrics, logs, meters, pricing, http, tools, support)
            .create("sk-grok", "grok-4", 512, 0.7, 0.9, sampling));
        run("meta", new MetaAgentChatClientFactory(metrics, logs, meters, pricing, http, tools, support)
            .create("sk-meta", "muse-spark", 512, 0.7, 0.9, sampling));
        run("mistral", new MistralAgentChatClientFactory(metrics, logs, meters, pricing, http, tools,
            new MistralProviderCachingStrategy(true), support).create("sk-mi", "mistral-large", 512, 0.7, 0.9, sampling));
        run("openai", new OpenAIAgentChatClientFactory(metrics, logs, meters, pricing, http, tools,
            new OpenAIProviderCachingStrategy(true), support).create("sk-oa", "gpt-4o", 512, 0.7, 0.9, sampling));
        run("openai-reasoning", new OpenAIAgentChatClientFactory(metrics, logs, meters, pricing, http, tools,
            new OpenAIProviderCachingStrategy(true), support).create("sk-oa", "gpt-5", 512, 0.7, 0.9, sampling));
        run("openrouter", new OpenRouterAgentChatClientFactory(metrics, logs, meters, pricing, http, tools, support)
            .create("sk-or", "openai/gpt-4o", 512, 0.7, 0.9, sampling));
        run("sarvam", new SarvamAgentChatClientFactory(metrics, logs, meters, pricing, tools, 4096, http, support)
            .create("sk-sv", "sarvam-m", 8192, 0.7, 0.9, sampling));
        run("azure", new AzureOpenAIAgentChatClientFactory(metrics, logs, meters, pricing, http, tools,
            new AzureOpenAIProviderCachingStrategy(true), support).create(
            new AzureOpenAiCredential("https://res.openai.azure.com", "az-key", "dep1", null).encode(),
            "gpt-4o", 512, 0.7, 0.9, sampling));
        run("ollama-key", new OllamaAgentChatClientFactory(metrics, logs, meters, pricing, http, tools, support,
                32768, 0)
            .create(new OllamaCredential("http://ollama.example:11434/", "olk").encode(), "llama3", 512, 0.7, 0.9,
                sampling));
        run("ollama-nokey", new OllamaAgentChatClientFactory(metrics, logs, meters, pricing, http, tools, support,
                32768, 0)
            .create(new OllamaCredential("http://ollama.example:11434", null).encode(), "llama3", 512, 0.7, 0.9,
                sampling));

        HostedLlmChatClientFactory hosted = new HostedLlmChatClientFactory(
            metrics, logs, meters, pricing, http, tools, null, support);
        run("hosted-default-path", hosted.create(
            new PlatformHostedLlmProvider("t", "https://api.together.xyz/v1", "llama-x", "enc", true, 1, null, null),
            " hosted-key\n", 512, 0.7, 0.9));
        run("hosted-custom-path", hosted.create(
            new PlatformHostedLlmProvider("t", "https://generativelanguage.googleapis.com", "gemini-2.5-flash", "enc",
                true, 1, null, "/v1beta/openai/chat/completions"),
            "gk", 512, 0.7, 0.9, "low", full));

        reply = ANTHROPIC_REPLY;
        run("anthropic-temp", new AnthropicAgentChatClientFactory(metrics, logs, support,
            new AnthropicProviderCachingStrategy(true, "SYSTEM_AND_TOOLS"), meters, pricing, http, tools, true, "")
            .create("sk-an", "claude-sonnet-4", 512, 0.7, 1.0, Map.of(SamplingOptions.OPTION_KEY, full)));
        run("anthropic-topp", new AnthropicAgentChatClientFactory(metrics, logs, support,
            new AnthropicProviderCachingStrategy(true, "SYSTEM_AND_TOOLS"), meters, pricing, http, tools, true, "")
            .create("sk-an", "claude-sonnet-5", 512, 0.7, 0.8, Map.of()));

        StringBuilder actual = new StringBuilder();
        snapshot.values().forEach(actual::append);
        if (Boolean.getBoolean("wire.snapshot.write")) {
            Files.writeString(Path.of("src/test/resources/llm-factory-wire-snapshot.txt"), actual.toString(),
                StandardCharsets.UTF_8);
            return;
        }
        String expected;
        try (InputStream in = getClass().getResourceAsStream("/llm-factory-wire-snapshot.txt")) {
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(expected.replace("\r\n", "\n"), actual.toString());
    }

    @Test
    void ollamaSendsReasoningEffortOnlyWhenConfigured() {
        OllamaAgentChatClientFactory plain = new OllamaAgentChatClientFactory(
            metrics, logs, meters, pricing, http(), tools, support(), 32768, 0);
        run("ollama-default", plain.create(
            new OllamaCredential("http://ollama.example:11434", null).encode(), "qwen3.5:2b", 512, 0.7, 0.9, java.util.Map.of()));
        assertFalse(snapshot.get("ollama-default").contains("reasoning_effort"));

        OllamaAgentChatClientFactory fast = new OllamaAgentChatClientFactory(
            metrics, logs, meters, pricing, http(), tools, support(), 32768, 0);
        org.springframework.test.util.ReflectionTestUtils.setField(fast, "reasoningEffort", "none");
        run("ollama-none", fast.create(
            new OllamaCredential("http://ollama.example:11434", null).encode(), "qwen3.5:2b", 512, 0.7, 0.9, java.util.Map.of()));
        assertTrue(snapshot.get("ollama-none").contains("\"reasoning_effort\":\"none\""), snapshot.get("ollama-none"));
    }
}
