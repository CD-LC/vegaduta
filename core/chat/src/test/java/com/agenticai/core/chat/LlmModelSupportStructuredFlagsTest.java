package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.structured.StructuredSchemaSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The wave-E flags on {@link LlmModelSupport}: every one is the identity when off, and shapes the
 * request when on.
 */
class LlmModelSupportStructuredFlagsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"verdict\":{\"type\":\"string\"}},\"required\":[\"verdict\"]}";

    @SuppressWarnings("unchecked")
    private static LlmModelSupport support() {
        ObjectProvider<ObservationRegistry> registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable(any())).thenReturn(ObservationRegistry.NOOP);
        ObjectProvider<DefaultAgentAdvisor> advisorProvider = mock(ObjectProvider.class);
        return new LlmModelSupport(
                registryProvider,
                advisorProvider,
                2,
                Duration.ofMillis(1),
                Duration.ofMillis(2),
                true,
                "MISTRAL",
                "low",
                16,
                Duration.ofMinutes(30),
                false);
    }

    private static Map<String, Object> spec(String schema) {
        return StructuredSchemaSpec.options("verdict", schema);
    }

    // ------------------------------------------------- Anthropic tool forcing

    @Test
    void anthropicToolForcingIsOffByDefaultAndReturnsTheSameModel() {
        LlmModelSupport s = support();
        ChatModel model = mock(ChatModel.class);
        assertThat(s.anthropicToolForcing).isFalse();
        assertThat(
                        s.applyAnthropicStructuredSchema(
                                model, "claude-sonnet-4-5", false, spec(SCHEMA)))
                .isSameAs(model);
    }

    @Test
    void anthropicToolForcingWrapsOnlyWhenEnabledAndASchemaWasRequested() {
        LlmModelSupport s = support();
        s.anthropicToolForcing = true;
        ChatModel model = mock(ChatModel.class);

        assertThat(
                        s.applyAnthropicStructuredSchema(
                                model, "claude-sonnet-4-5", false, spec(SCHEMA)))
                .isInstanceOf(AnthropicStructuredToolChatModel.class);
        // no schema requested
        assertThat(s.applyAnthropicStructuredSchema(model, "claude-sonnet-4-5", false, Map.of()))
                .isSameAs(model);
        assertThat(s.applyAnthropicStructuredSchema(model, "claude-sonnet-4-5", false, null))
                .isSameAs(model);
    }

    @Test
    void anthropicToolForcingFallsBackWhenThinkingModelOrSchemaDoNotQualify() {
        LlmModelSupport s = support();
        s.anthropicToolForcing = true;
        ChatModel model = mock(ChatModel.class);

        // extended thinking on for this client -> never build a forced-tool_choice request
        assertThat(s.applyAnthropicStructuredSchema(model, "claude-sonnet-4-5", true, spec(SCHEMA)))
                .isSameAs(model);
        // model without forced tool choice support
        assertThat(s.applyAnthropicStructuredSchema(model, "claude-2.1", false, spec(SCHEMA)))
                .isSameAs(model);
        assertThat(s.applyAnthropicStructuredSchema(model, null, false, spec(SCHEMA)))
                .isSameAs(model);
        // schema not representable as a tool input schema (array root, union at the root)
        assertThat(
                        s.applyAnthropicStructuredSchema(
                                model,
                                "claude-sonnet-4-5",
                                false,
                                spec("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}")))
                .isSameAs(model);
        assertThat(
                        s.applyAnthropicStructuredSchema(
                                model,
                                "claude-sonnet-4-5",
                                false,
                                spec("{\"anyOf\":[{\"type\":\"object\"}]}")))
                .isSameAs(model);
    }

    // ----------------------------------------------------------- Ollama format

    @Test
    void ollamaFormatIsOffByDefault() {
        LlmModelSupport s = support();
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("llama3.1").build();
        assertThat(s.ollamaFormat).isFalse();
        assertThat(s.applyOllamaStructuredSchema(options, spec(SCHEMA))).isSameAs(options);
    }

    @Test
    void ollamaFormatAppliesJsonSchemaOnlyWhenEnabledAndRequested() {
        LlmModelSupport s = support();
        s.ollamaFormat = true;
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("llama3.1").build();

        OpenAiChatOptions out = s.applyOllamaStructuredSchema(options, spec(SCHEMA));
        assertThat(out).isNotSameAs(options);
        assertThat(out.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_SCHEMA);
        assertThat(options.getResponseFormat()).isNull();

        assertThat(s.applyOllamaStructuredSchema(options, Map.of())).isSameAs(options);
        assertThat(s.applyOllamaStructuredSchema(options, spec("not json"))).isSameAs(options);
    }

    // ------------------------------------------------------------- tool strict

    @Test
    void toolStrictIsOffByDefaultAndTheBuildersAreReturnedUntouched() {
        LlmModelSupport s = support();
        RestClient.Builder rest = mock(RestClient.Builder.class);
        WebClient.Builder web = mock(WebClient.Builder.class);
        assertThat(s.strictTools).isFalse();
        assertThat(s.withToolStrict(rest)).isSameAs(rest);
        assertThat(s.withToolStrict(web)).isSameAs(web);
        org.mockito.Mockito.verifyNoInteractions(rest, web);
    }

    // -- wire level: a real OpenAiChatModel against a local OpenAI-compatible server

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    requests.add(body);
                    boolean stream = body.contains("\"stream\":true");
                    String payload =
                            stream
                                    ? "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o\","
                                            + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}\n\n"
                                            + "data: [DONE]\n\n"
                                    : "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-4o\","
                                            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"finish_reason\":\"stop\"}],"
                                            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
                    byte[] out = payload.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .add("Content-Type", stream ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(200, out.length);
                    exchange.getResponseBody().write(out);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private record SchemaTool(String name, String schema) implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description("d").inputSchema(schema).build();
        }

        @Override
        public String call(String toolInput) {
            return "{}";
        }
    }

    private static final String STRICT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},"
                    + "\"required\":[\"city\"],\"additionalProperties\":false}";
    private static final String OPEN_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}";

    private ChatClient openAiClient(LlmModelSupport s) {
        OpenAiApi api =
                OpenAiApi.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .apiKey("test-key")
                        .restClientBuilder(s.withToolStrict(RestClient.builder()))
                        .webClientBuilder(s.withToolStrict(WebClient.builder()))
                        .build();
        OpenAiChatModel model =
                OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(OpenAiChatOptions.builder().model("gpt-4o").build())
                        .build();
        return ChatClient.builder(model).build();
    }

    private void callAndStream(ChatClient client) {
        // internal tool execution off: the tool definitions go out, nothing is run
        ChatOptions options =
                OpenAiChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .toolCallbacks(
                                List.of(
                                        new SchemaTool("closed", STRICT_SCHEMA),
                                        new SchemaTool("open", OPEN_SCHEMA)))
                        .build();
        client.prompt().user("hi").options(options).call().content();
        client.prompt().user("hi").options(options).stream().content().collectList().block();
    }

    @Test
    void withTheFlagOffTheOpenAiRequestsCarryNoStrictField() throws Exception {
        callAndStream(openAiClient(support()));

        assertThat(requests).hasSize(2);
        for (String body : requests) {
            assertThat(body).doesNotContain("\"strict\"");
        }
    }

    @Test
    void withTheFlagOnOnlyTheEligibleToolIsStrictOnBothTheBlockingAndStreamingPaths()
            throws Exception {
        LlmModelSupport s = support();
        s.strictTools = true;

        callAndStream(openAiClient(s));

        assertThat(requests).hasSize(2);
        for (String body : requests) {
            JsonNode tools = MAPPER.readTree(body).get("tools");
            assertThat(tools).hasSize(2);
            JsonNode closed = tools.get(0).get("function");
            JsonNode open = tools.get(1).get("function");
            assertThat(closed.get("name").asText()).isEqualTo("closed");
            assertThat(closed.get("strict").asBoolean()).isTrue();
            assertThat(open.get("name").asText()).isEqualTo("open");
            assertThat(open.has("strict")).isFalse();
        }
    }

    @Test
    void thinkingEffortNormalizesToTheThreeSupportedLevels() {
        assertThat(ThinkingEffort.normalize(" High ")).isEqualTo("high");
        assertThat(ThinkingEffort.normalize("low")).isEqualTo("low");
        assertThat(ThinkingEffort.normalize("MEDIUM")).isEqualTo("medium");
        assertThat(ThinkingEffort.normalize("none")).isNull();
        assertThat(ThinkingEffort.normalize("")).isNull();
        assertThat(ThinkingEffort.normalize(null)).isNull();
        assertThat(ThinkingEffort.normalize("ultra")).isNull();
        assertThat(ThinkingEffort.OPTION_KEY).isEqualTo(AnthropicThinking.OPTION_KEY);
    }

    @Test
    void anAgentsThinkingEffortOverridesTheOperatorDefaultAndBlankInheritsIt() {
        assertThat(
                        AnthropicThinking.resolveEffort(
                                Map.of(ThinkingEffort.OPTION_KEY, "high"), "low"))
                .isEqualTo("high");
        assertThat(AnthropicThinking.resolveEffort(Map.of(), "low")).isEqualTo("low");
        assertThat(AnthropicThinking.resolveEffort(null, "")).isEqualTo("");
        // per-agent effort turns thinking on where the operator default is blank ...
        assertThat(
                        AnthropicThinking.budgetFor(
                                "claude-sonnet-4-5",
                                AnthropicThinking.resolveEffort(
                                        Map.of(ThinkingEffort.OPTION_KEY, "medium"), ""),
                                20000))
                .isEqualTo(8192);
        // ... and stays off for an unsupported model or no effort at all
        assertThat(
                        AnthropicThinking.budgetFor(
                                "claude-sonnet-5",
                                AnthropicThinking.resolveEffort(
                                        Map.of(ThinkingEffort.OPTION_KEY, "medium"), ""),
                                16000))
                .isNull();
        assertThat(
                        AnthropicThinking.budgetFor(
                                "claude-sonnet-4-5",
                                AnthropicThinking.resolveEffort(Map.of(), ""),
                                16000))
                .isNull();
        // keep the import honest: a thinking-configured options object is what the factory builds
        // from a budget
        AnthropicChatOptions built =
                AnthropicAgentChatClientFactory.samplingBuilder(
                                "claude-sonnet-4-5", 16000, 0.2, 1.0, SamplingOptions.NONE, 8192)
                        .build();
        assertThat(built.getThinking()).isNotNull();
        assertThat(built.getTemperature()).isNull();
    }
}
