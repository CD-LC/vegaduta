package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.providers.ProviderName;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;

/**
 * What the Spring AI 1.x WebClient/RestClient filters did for Ollama, now through the real Spring AI
 * 2 OpenAI model on the shared transport against a local server speaking Ollama's dialect:
 *
 * <ul>
 *   <li>a model's thinking text arrives in a {@code reasoning} field (not {@code reasoning_content});
 *       the SDK model must surface it as {@code reasoningContent} metadata, blocking and streamed,
 *       because {@code ReasoningEmitAdvisor} turns that into {@code thinking} step events;
 *   <li>HTTP 500 "XML syntax error ..." for a malformed model-written tool call is replayed (the
 *       platform retry policy covers blocking and streamed requests alike) and the second answer
 *       wins.
 * </ul>
 */
class OllamaWireBehaviourTest {

    private static final String COMPLETION_WITH_REASONING =
            "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"qwen\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hi\","
                    + "\"reasoning\":\"let me think\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    private static final String STREAM_WITH_REASONING =
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"qwen\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning\":\"let me think\"}}]}\n\n"
                    + "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"qwen\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";
    private static final String PARSE_ERROR =
            "{\"error\":{\"message\":\"XML syntax error on line 3: element <function> closed by"
                    + " </parameter>\",\"type\":\"api_error\"}}";

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean failFirstWithParseError;
    private volatile String blockingReply = COMPLETION_WITH_REASONING;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    String body =
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    bodies.add(body);
                    boolean stream = body.contains("\"stream\":true");
                    int n = calls.incrementAndGet();
                    if (failFirstWithParseError && n == 1) {
                        byte[] err = PARSE_ERROR.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(500, err.length);
                        exchange.getResponseBody().write(err);
                        exchange.close();
                        return;
                    }
                    byte[] out =
                            (stream ? STREAM_WITH_REASONING : blockingReply)
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .add("Content-Type", stream ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(200, out.length);
                    exchange.getResponseBody().write(out);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @SuppressWarnings("unchecked")
    private OpenAiChatModel model() {
        ObjectProvider<ObservationRegistry> obs = mock(ObjectProvider.class);
        when(obs.getIfAvailable(any())).thenReturn(ObservationRegistry.NOOP);
        ObjectProvider<DefaultAgentAdvisor> adv = mock(ObjectProvider.class);
        // 3 attempts, 1 ms backoff: the retry policy the platform applies to every provider call
        LlmModelSupport support =
                new LlmModelSupport(
                        obs,
                        adv,
                        3,
                        Duration.ofMillis(1),
                        Duration.ofMillis(2),
                        true,
                        "MISTRAL",
                        "low",
                        16,
                        Duration.ofMinutes(30),
                        false);
        LlmHttpClientFactory http = mock(LlmHttpClientFactory.class);
        OkHttpClient plain = new OkHttpClient();
        when(http.transport(anyBoolean()))
                .thenReturn((Interceptor) chain -> plain.newCall(chain.request()).execute());
        OpenAiCompatModelBuilder builder =
                new OpenAiCompatModelBuilder(
                        http, new ResilientToolCallingManagerFactory(JsonMappers.plain()), support);
        return builder.chatModel(
                builder.api("http://127.0.0.1:" + server.getAddress().getPort()).noApiKey(),
                builder.options(ProviderName.OLLAMA, "qwen").build());
    }

    private static Object reasoning(ChatResponse response) {
        return response.getResult().getOutput().getMetadata().get("reasoningContent");
    }

    @Test
    void aBlockingReplysReasoningFieldSurfacesAsReasoningContent() {
        ChatResponse response = model().call(new Prompt("hi"));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("hi");
        assertThat(reasoning(response)).isEqualTo("let me think");
    }

    @Test
    void aStreamedReplysReasoningDeltasSurfaceAsReasoningContent() {
        List<ChatResponse> chunks = model().stream(new Prompt("hi")).collectList().block();

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).anySatisfy(c -> assertThat(reasoning(c)).isEqualTo("let me think"));
        assertThat(
                        chunks.stream()
                                .map(c -> c.getResult().getOutput().getText())
                                .filter(t -> t != null)
                                .reduce("", String::concat))
                .isEqualTo("hi");
    }

    @Test
    void aMalformedToolCallRejectedWith500IsReplayedForTheBlockingCall() {
        failFirstWithParseError = true;

        ChatResponse response = model().call(new Prompt("hi"));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(response.getResult().getOutput().getText()).isEqualTo("hi");
    }

    @Test
    void aMalformedToolCallRejectedWith500IsReplayedForTheStreamedCallToo() {
        failFirstWithParseError = true;

        List<ChatResponse> chunks = model().stream(new Prompt("hi")).collectList().block();

        assertThat(calls.get()).isEqualTo(2);
        assertThat(chunks).isNotEmpty();
    }
}
