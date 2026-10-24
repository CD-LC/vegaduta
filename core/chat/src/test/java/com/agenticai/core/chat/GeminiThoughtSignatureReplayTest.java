package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Gemini rejects the request that follows a tool call ("Function call is missing a thought_signature")
 * unless the model's own {@code functionCall} part is sent back carrying the signature Gemini put on
 * THAT part. Spring AI 1.1.8 stores the signatures of every part of a reply in one list and, when it
 * rebuilds the model's turn, hands them out in order to the function-call parts only. So the list must
 * contain the function-call parts' signatures and nothing else: a signature on a lead-in text part used
 * to shift the list by one and give the function call somebody else's signature.
 *
 * <p>These tests drive the real {@link GeminiMixedContentToolCallChatModel} and Spring AI's real
 * request conversion against a local HTTP stub that plays Gemini, and read the request the second
 * model round actually puts on the wire.
 */
class GeminiThoughtSignatureReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final byte[] TEXT_SIGNATURE = "signature-of-the-lead-in-text".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CALL_SIGNATURE = "signature-of-the-function-call".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SECOND_CALL_SIGNATURE = "signature-of-the-second-call".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> replies = new CopyOnWriteArrayList<>();
    private final AtomicInteger served = new AtomicInteger();

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            String body = replies.get(Math.min(served.getAndIncrement(), replies.size() - 1));
            boolean streaming = exchange.getRequestURI().getPath().contains("streamGenerateContent");
            byte[] bytes = (streaming ? "data: " + body + "\r\n\r\n" : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private static String reply(Content content) throws Exception {
        Candidate candidate = Candidate.builder()
            .content(content)
            .finishReason(FinishReason.Known.STOP)
            .index(0)
            .build();
        Map<String, Object> body = Map.of(
            "candidates", List.of(JSON.readValue(candidate.toJson(), Map.class)),
            "modelVersion", "gemini-3-pro-preview",
            "usageMetadata", Map.of("promptTokenCount", 3, "candidatesTokenCount", 2, "totalTokenCount", 5));
        return JSON.writeValueAsString(body);
    }

    private static Part signed(Part part, byte[] signature) {
        return part.toBuilder().thoughtSignature(signature).build();
    }

    private ChatClient client() {
        Client genai = Client.builder()
            .apiKey("test-key")
            .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build())
            .build();
        GeminiMixedContentToolCallChatModel model = new GeminiMixedContentToolCallChatModel(
            genai,
            GoogleGenAiChatOptions.builder().model("gemini-3-pro-preview").build(),
            ToolCallingManager.builder().build(),
            ObservationRegistry.NOOP);
        return ChatClient.builder(model).build();
    }

    private static ToolCallback lookupTool(List<String> calls) {
        ToolDefinition definition = ToolDefinition.builder()
            .name("lookup")
            .description("Looks something up")
            .inputSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}")
            .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                calls.add(toolInput);
                return "{\"found\":true}";
            }
        };
    }

    /** The model-role {@code functionCall} parts of the request the second round sent. */
    private List<JsonNode> replayedFunctionCallParts() {
        assertThat(requests).as("the stub saw the tool round and the follow-up request").hasSize(2);
        List<JsonNode> parts = new ArrayList<>();
        for (JsonNode content : requests.get(1).path("contents")) {
            if (!"model".equals(content.path("role").asText())) {
                continue;
            }
            for (JsonNode part : content.path("parts")) {
                if (part.has("functionCall")) {
                    parts.add(part);
                }
            }
        }
        return parts;
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void aLeadInTextPartWithItsOwnSignatureDoesNotStealTheFunctionCallsSignature() throws Exception {
        replies.add(reply(Content.fromParts(
            signed(Part.fromText("Let me look that up."), TEXT_SIGNATURE),
            signed(Part.fromFunctionCall("lookup", Map.of("q", "x")), CALL_SIGNATURE))));
        replies.add(reply(Content.fromParts(Part.fromText("Found it."))));
        List<String> executed = new CopyOnWriteArrayList<>();

        String answer = client().prompt().user("find x").toolCallbacks(lookupTool(executed)).call().content();

        assertThat(answer).isEqualTo("Found it.");
        assertThat(executed).as("the tool ran once").hasSize(1);
        List<JsonNode> calls = replayedFunctionCallParts();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).path("thoughtSignature").asText())
            .as("the function call carries the signature Gemini put on the function call")
            .isEqualTo(b64(CALL_SIGNATURE));
    }

    @Test
    void theFollowUpRequestStillOffersTheToolsSoTheTurnKeepsWorking() throws Exception {
        replies.add(reply(Content.fromParts(
            signed(Part.fromText("Let me look that up."), TEXT_SIGNATURE),
            signed(Part.fromFunctionCall("lookup", Map.of("q", "x")), CALL_SIGNATURE))));
        replies.add(reply(Content.fromParts(Part.fromText("Found it."))));

        client().prompt().user("find x").toolCallbacks(lookupTool(new CopyOnWriteArrayList<>())).call().content();

        assertThat(requests).hasSize(2);
        for (JsonNode request : requests) {
            assertThat(request.path("tools").path(0).path("functionDeclarations").path(0).path("name").asText())
                .isEqualTo("lookup");
        }
    }

    @Test
    void onlyTheFirstOfSeveralParallelCallsIsSignedAndItKeepsItsOwnSignature() throws Exception {
        replies.add(reply(Content.fromParts(
            signed(Part.fromText("Checking two things."), TEXT_SIGNATURE),
            signed(Part.fromFunctionCall("lookup", Map.of("q", "a")), CALL_SIGNATURE),
            Part.fromFunctionCall("lookup", Map.of("q", "b")))));
        replies.add(reply(Content.fromParts(Part.fromText("Both found."))));

        client().prompt().user("find a and b").toolCallbacks(lookupTool(new CopyOnWriteArrayList<>())).call().content();

        List<JsonNode> calls = replayedFunctionCallParts();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).path("thoughtSignature").asText()).isEqualTo(b64(CALL_SIGNATURE));
        assertThat(calls.get(1).has("thoughtSignature")).as("Gemini signs only the first call of a parallel batch").isFalse();
    }

    @Test
    void aLaterCallsSignatureIsNeverHandedToAnEarlierUnsignedCall() throws Exception {
        replies.add(reply(Content.fromParts(
            Part.fromFunctionCall("lookup", Map.of("q", "a")),
            signed(Part.fromFunctionCall("lookup", Map.of("q", "b")), SECOND_CALL_SIGNATURE))));
        replies.add(reply(Content.fromParts(Part.fromText("Done."))));

        client().prompt().user("find a and b").toolCallbacks(lookupTool(new CopyOnWriteArrayList<>())).call().content();

        List<JsonNode> calls = replayedFunctionCallParts();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).has("thoughtSignature"))
            .as("the first call was not signed by Gemini, so it must not be given the second call's signature")
            .isFalse();
    }

    @Test
    void theStreamingToolLoopReplaysTheFunctionCallsOwnSignatureToo() throws Exception {
        replies.add(reply(Content.fromParts(
            signed(Part.fromText("Let me look that up."), TEXT_SIGNATURE),
            signed(Part.fromFunctionCall("lookup", Map.of("q", "x")), CALL_SIGNATURE))));
        replies.add(reply(Content.fromParts(Part.fromText("Found it."))));
        List<String> executed = new CopyOnWriteArrayList<>();

        String answer = String.join("", client().prompt().user("find x").toolCallbacks(lookupTool(executed))
            .stream().content().collectList().block(java.time.Duration.ofSeconds(30)));

        assertThat(answer).contains("Found it.");
        assertThat(executed).hasSize(1);
        List<JsonNode> calls = replayedFunctionCallParts();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).path("thoughtSignature").asText()).isEqualTo(b64(CALL_SIGNATURE));
    }
}
