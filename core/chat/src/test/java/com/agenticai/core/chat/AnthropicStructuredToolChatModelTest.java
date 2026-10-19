package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.chat.structured.StructuredOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;

/**
 * {@link AnthropicStructuredToolChatModel}: stub-level routing (forced / pass-through / unwrap) and a
 * wire-level proof against a real {@link AnthropicChatModel} that the Messages request is valid -
 * in particular that a forced tool_choice is NEVER sent together with extended thinking.
 */
class AnthropicStructuredToolChatModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCHEMA =
        "{\"type\":\"object\",\"properties\":{\"verdict\":{\"type\":\"string\"}},\"required\":[\"verdict\"]}";
    private static final JsonNode INPUT_SCHEMA = StructuredOutput.anthropicToolInputSchema(SCHEMA);

    // ---------------------------------------------------------------- stub level

    private static final class Stub implements ChatModel {
        final ChatResponse response;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger streams = new AtomicInteger();
        final AtomicReference<Prompt> last = new AtomicReference<>();
        final ChatOptions defaults;

        Stub(ChatResponse response, ChatOptions defaults) {
            this.response = response;
            this.defaults = defaults;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            last.set(prompt);
            return response;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streams.incrementAndGet();
            last.set(prompt);
            return Flux.just(response);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return defaults;
        }
    }

    private static ChatResponse toolUse(String args) {
        return new ChatResponse(List.of(new Generation(
            AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("t1", "function", "verdict", args))).build(),
            ChatGenerationMetadata.builder().finishReason("tool_use").build())));
    }

    private static ChatResponse textReply(String t) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(t).build())));
    }

    private static Prompt prompt(ChatOptions options) {
        return new Prompt(List.of(new UserMessage("judge it")), options);
    }

    private static AnthropicChatOptions plainOptions() {
        return AnthropicChatOptions.builder().model("claude-sonnet-4-5").maxTokens(512).temperature(0.2).build();
    }

    @Test
    void forcesTheToolOnTheRequestAndReturnsTheToolInputAsText() throws Exception {
        Stub stub = new Stub(toolUse("{\"verdict\":\"pass\"}"), plainOptions());
        AnthropicStructuredToolChatModel model = new AnthropicStructuredToolChatModel(stub, "verdict", INPUT_SCHEMA);

        ChatResponse out = model.call(prompt(plainOptions()));

        AnthropicChatOptions sent = (AnthropicChatOptions) stub.last.get().getOptions();
        assertThat(((AnthropicApi.ToolChoiceTool) sent.getToolChoice()).name()).isEqualTo("verdict");
        assertThat(sent.getInternalToolExecutionEnabled()).isFalse();
        assertThat(sent.getToolCallbacks()).hasSize(1);
        assertThat(MAPPER.readTree(out.getResult().getOutput().getText()).get("verdict").asText()).isEqualTo("pass");
        assertThat(stub.calls.get()).isEqualTo(1);
    }

    @Test
    void modelAnsweringInTextFallsBackToThatText() {
        Stub stub = new Stub(textReply("{\"verdict\":\"pass\"}"), plainOptions());
        ChatResponse out = new AnthropicStructuredToolChatModel(stub, "verdict", INPUT_SCHEMA).call(prompt(plainOptions()));
        assertThat(out).isSameAs(stub.response);
    }

    @Test
    void thinkingOnPassesTheOriginalPromptThroughUnchanged() {
        AnthropicChatOptions thinking = AnthropicChatOptions.builder().model("claude-sonnet-4-5").maxTokens(4096)
            .thinking(AnthropicApi.ThinkingType.ENABLED, 2048).build();
        Stub stub = new Stub(textReply("plain"), thinking);
        Prompt p = prompt(thinking);

        ChatResponse out = new AnthropicStructuredToolChatModel(stub, "verdict", INPUT_SCHEMA).call(p);

        assertThat(stub.last.get()).isSameAs(p);
        assertThat(((AnthropicChatOptions) stub.last.get().getOptions()).getToolChoice()).isNull();
        assertThat(out).isSameAs(stub.response);
    }

    @Test
    void aRequestThatAlreadyHasToolsPassesThroughUnchanged() {
        AnthropicChatOptions withTool = AnthropicChatOptions.builder().model("claude-sonnet-4-5").maxTokens(512)
            .toolCallbacks(List.of(FunctionToolCallback.<Map<String, Object>, String>builder("real", in -> "ok")
                .description("d").inputType(Map.class).build())).build();
        Stub stub = new Stub(textReply("plain"), withTool);
        Prompt p = prompt(withTool);
        new AnthropicStructuredToolChatModel(stub, "verdict", INPUT_SCHEMA).call(p);
        assertThat(stub.last.get()).isSameAs(p);
    }

    @Test
    void nonAnthropicOptionsAndNullPromptPassThrough() {
        Stub stub = new Stub(textReply("x"), plainOptions());
        AnthropicStructuredToolChatModel model = new AnthropicStructuredToolChatModel(stub, "verdict", INPUT_SCHEMA);
        Prompt generic = new Prompt(List.of(new UserMessage("hi")));
        model.call(generic);
        assertThat(stub.last.get()).isSameAs(generic);
        model.call((Prompt) null);
        assertThat(stub.calls.get()).isEqualTo(2);
    }

    @Test
    void streamAndDefaultOptionsAreDelegatedUntouched() {
        AnthropicChatOptions defaults = plainOptions();
        Stub stub = new Stub(textReply("x"), defaults);
        AnthropicStructuredToolChatModel model = new AnthropicStructuredToolChatModel(stub, "verdict", INPUT_SCHEMA);
        Prompt p = prompt(plainOptions());
        assertThat(model.stream(p).collectList().block()).hasSize(1);
        assertThat(stub.last.get()).isSameAs(p);
        assertThat(stub.streams.get()).isEqualTo(1);
        assertThat(model.getDefaultOptions()).isSameAs(defaults);
    }

    // ---------------------------------------------------------------- wire level

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile String reply;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
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

    private static final String TOOL_USE_REPLY = """
        {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
         "content":[{"type":"text","text":"Calling it now."},
                    {"type":"tool_use","id":"toolu_1","name":"verdict","input":{"verdict":"pass"}}],
         "stop_reason":"tool_use","usage":{"input_tokens":5,"output_tokens":7}}""";

    private static final String TEXT_REPLY = """
        {"id":"msg_2","type":"message","role":"assistant","model":"claude-sonnet-4-5",
         "content":[{"type":"text","text":"{\\"verdict\\":\\"fail\\"}"}],
         "stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":7}}""";

    private AnthropicChatModel realModel(AnthropicChatOptions defaults) {
        AnthropicApi api = AnthropicApi.builder()
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .apiKey("test-key")
            .build();
        return AnthropicChatModel.builder().anthropicApi(api).defaultOptions(defaults).build();
    }

    private ChatClient client(ChatModel model) {
        return ChatClient.builder(model).build();
    }

    @Test
    void wireRequestForcesTheToolAndTheReplyIsTheToolInputJson() throws Exception {
        reply = TOOL_USE_REPLY;
        ChatModel model = new AnthropicStructuredToolChatModel(realModel(plainOptions()), "verdict", INPUT_SCHEMA);

        String content = client(model).prompt().user("judge it").call().content();

        assertThat(MAPPER.readTree(content).get("verdict").asText()).isEqualTo("pass");
        assertThat(requests).hasSize(1); // the pseudo tool was never "executed" and no follow-up turn happened
        JsonNode sent = MAPPER.readTree(requests.get(0));
        assertThat(sent.get("tool_choice").get("type").asText()).isEqualTo("tool");
        assertThat(sent.get("tool_choice").get("name").asText()).isEqualTo("verdict");
        assertThat(sent.get("tools")).hasSize(1);
        assertThat(sent.get("tools").get(0).get("name").asText()).isEqualTo("verdict");
        assertThat(sent.get("tools").get(0).get("input_schema").get("type").asText()).isEqualTo("object");
        assertThat(sent.get("tools").get(0).get("input_schema").get("properties").has("verdict")).isTrue();
        assertThat(sent.has("thinking")).isFalse();
    }

    @Test
    void wireModelReturningTextInsteadOfTheToolFallsBackToThatText() throws Exception {
        reply = TEXT_REPLY;
        ChatModel model = new AnthropicStructuredToolChatModel(realModel(plainOptions()), "verdict", INPUT_SCHEMA);

        String content = client(model).prompt().user("judge it").call().content();

        assertThat(MAPPER.readTree(content).get("verdict").asText()).isEqualTo("fail");
        assertThat(requests).hasSize(1);
    }

    @Test
    void wireThinkingRequestNeverCarriesAForcedToolChoice() throws Exception {
        reply = TEXT_REPLY;
        AnthropicChatOptions thinking = AnthropicChatOptions.builder().model("claude-sonnet-4-5").maxTokens(4096)
            .thinking(AnthropicApi.ThinkingType.ENABLED, 2048).build();
        ChatModel model = new AnthropicStructuredToolChatModel(realModel(thinking), "verdict", INPUT_SCHEMA);

        client(model).prompt().user("judge it").call().content();

        JsonNode sent = MAPPER.readTree(requests.get(0));
        assertThat(sent.get("thinking").get("type").asText()).isEqualTo("enabled");
        assertThat(sent.has("tool_choice")).isFalse();
        assertThat(sent.has("tools")).isFalse();
    }

    @Test
    void wireRequestWithRealToolsKeepsThemAndForcesNothing() throws Exception {
        reply = TEXT_REPLY;
        ChatModel model = new AnthropicStructuredToolChatModel(realModel(plainOptions()), "verdict", INPUT_SCHEMA);
        var real = FunctionToolCallback.<Map<String, Object>, String>builder("real_tool", in -> "ok")
            .description("a real tool").inputType(Map.class).build();

        client(model).prompt().user("judge it").toolCallbacks(real).call().content();

        JsonNode sent = MAPPER.readTree(requests.get(0));
        assertThat(sent.has("tool_choice")).isFalse();
        assertThat(sent.get("tools")).hasSize(1);
        assertThat(sent.get("tools").get(0).get("name").asText()).isEqualTo("real_tool");
    }
}
