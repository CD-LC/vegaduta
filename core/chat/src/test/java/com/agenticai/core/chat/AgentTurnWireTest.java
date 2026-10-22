package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.chat.AgentTurn.Message;
import com.agenticai.core.chat.AgentTurn.Tool;
import com.agenticai.core.chat.AgentTurn.ToolCall;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The seam test in {@link AgentTurnServiceTest} stubs the provider call, so it cannot prove the
 * claim this feature rests on: that handing Spring AI tool DEFINITIONS with {@link
 * CallerRunsToolLoop} through {@code ChatClient#options} returns the model's tool calls as data
 * instead of running them, without dropping the model-level defaults (model id, max tokens) {@link
 * HostedLlmChatClientFactory} sets. This drives the real {@code OpenAiChatModel} + {@code
 * ChatClient} against a local OpenAI-compatible HTTP server.
 */
class AgentTurnWireTest {

    private HttpServer server;
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private volatile String responseBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    requestBodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
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

    /** Only callModel is exercised, so the collaborators are inert. */
    @SuppressWarnings("unchecked")
    private static AgentTurnService service() {
        return new AgentTurnService(
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
                null,
                null,
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
                2048,
                4096,
                0.2,
                1);
    }

    /**
     * The production seat of the tool loop: the cancellation-aware ToolCallingAdvisor around a real
     * SDK model.
     */
    private ChatClient client() {
        OpenAiChatModel model =
                OpenAiChatModel.builder()
                        .options(
                                OpenAiChatOptions.builder()
                                        .baseUrl(
                                                "http://127.0.0.1:"
                                                        + server.getAddress().getPort()
                                                        + "/v1")
                                        .apiKey("test-key")
                                        .maxRetries(0)
                                        .model("pool-model")
                                        .maxTokens(777)
                                        .temperature(0.2)
                                        .build())
                        .toolCallingManager(ToolCallingManager.builder().build())
                        .build();
        return ChatClient.builder(
                        model,
                        io.micrometer.observation.ObservationRegistry.NOOP,
                        null,
                        null,
                        CancellationAwareToolCallingAdvisor.builder()
                                .toolCallingManager(ToolCallingManager.builder().build()))
                .build();
    }

    private static final String TOOL_CALL_REPLY =
            """
{"id":"c1","object":"chat.completion","created":1,"model":"pool-model",
 "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
   "tool_calls":[{"id":"call_9","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"a.ts\\"}"}}]}}],
 "usage":{"prompt_tokens":321,"completion_tokens":12,"total_tokens":333}}\
""";

    @Test
    void theModelsToolCallComesBackAsDataAndIsNeverExecuted() {
        responseBody = TOOL_CALL_REPLY;
        AgentTurnService service = service();

        var messages =
                AgentTurnService.toSpringMessages(
                        List.of(
                                new Message("system", "be useful", null, null, null),
                                new Message("user", "read a.ts", null, null, null)));
        var options =
                AgentTurnService.toolOptions(
                        List.of(
                                new Tool(
                                        "read_file",
                                        "Read a file",
                                        Map.of(
                                                "type",
                                                "object",
                                                "properties",
                                                Map.of("path", Map.of("type", "string"))))));

        ChatResponse response = service.callModel(client(), messages, options);

        // If Spring AI had executed the definition-only callback it would have thrown
        // ("executed by the client") and looped; instead we get the call back untouched.
        assertTrue(response.getResult().getOutput().hasToolCalls());
        assertEquals("read_file", response.getResult().getOutput().getToolCalls().get(0).name());
        assertEquals(
                1, requestBodies.size(), "exactly one provider round trip - no execute-and-loop");

        String sent = requestBodies.get(0);
        assertTrue(
                sent.contains("\"read_file\""),
                "the tool definition must reach the provider: " + sent);
        assertTrue(
                sent.contains("pool-model"),
                "the model-level default model id must survive runtime options: " + sent);
        assertTrue(
                sent.contains("777"),
                "the model-level default max tokens must survive runtime options: " + sent);
    }

    @Test
    void aFullRoundTripWithToolResultsSerialisesAssistantCallsAndToolMessages() {
        responseBody =
                """
{"id":"c2","object":"chat.completion","created":1,"model":"pool-model",
 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"done"}}],
 "usage":{"prompt_tokens":50,"completion_tokens":2,"total_tokens":52}}\
""";
        AgentTurnService service = service();
        var messages =
                AgentTurnService.toSpringMessages(
                        List.of(
                                new Message("user", "read a.ts", null, null, null),
                                new Message(
                                        "assistant",
                                        "",
                                        List.of(
                                                new ToolCall(
                                                        "call_9",
                                                        "read_file",
                                                        Map.of("path", "a.ts"))),
                                        null,
                                        null),
                                new Message(
                                        "tool", "1\tconst a = 1", null, "call_9", "read_file")));

        ChatResponse response =
                service.callModel(
                        client(),
                        messages,
                        AgentTurnService.toolOptions(
                                List.of(new Tool("read_file", "d", Map.of()))));

        assertEquals("done", response.getResult().getOutput().getText());
        String sent = requestBodies.get(0);
        assertTrue(sent.contains("\"tool_call_id\":\"call_9\""), sent);
        assertTrue(sent.contains("const a = 1"), sent);
        assertTrue(
                sent.contains("\"tool_calls\""),
                "the assistant's earlier call must be replayed: " + sent);
        assertFalse(
                sent.contains("executed by the client"),
                "no callback should ever have been invoked");
    }
}
