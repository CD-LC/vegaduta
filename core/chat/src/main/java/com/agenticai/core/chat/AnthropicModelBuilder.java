package com.agenticai.core.chat;

import java.time.Duration;
import okhttp3.Interceptor;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.http.okhttp.AnthropicHttpClientBuilderCustomizer;

/**
 * The single place the Anthropic chat factory builds its Spring AI transport and model (ADR-0012
 * P1b, re-based on the official Anthropic Java SDK in P3), mirroring {@link
 * OpenAiCompatModelBuilder}: every SDK client runs its requests on the pooled, timeout-bounded
 * shared client of {@link LlmHttpClientFactory} behind the platform retry policy ({@link
 * LlmRetryInterceptor}), and the model gets the shared tool-calling manager and observation
 * registry. The SDK's own retry loop is off ({@code maxRetries(0)}) so the platform policy (5xx and
 * connection failures only; never 429 or a read timeout) is the only one. (The eval module cannot
 * depend on core/chat; it has its own equivalent seam.)
 */
final class AnthropicModelBuilder {

    private final LlmHttpClientFactory httpClients;
    private final ResilientToolCallingManagerFactory toolCallingManagers;
    private final LlmModelSupport modelSupport;

    AnthropicModelBuilder(
            LlmHttpClientFactory httpClients,
            ResilientToolCallingManagerFactory toolCallingManagers,
            LlmModelSupport modelSupport) {
        this.httpClients = httpClients;
        this.toolCallingManagers = toolCallingManagers;
        this.modelSupport = modelSupport;
    }

    /**
     * Restores the two request-shape details the SDK model changed without meaning, so the wire is
     * byte-for-byte what Spring AI 1.x sent: {@code "stream": false} on a blocking call (the SDK
     * omits it) and a plain user/ assistant text message as a one-element {@code
     * [{"type":"text","text":...}]} block array (the SDK sends a bare string). Both spellings are
     * valid Messages API; a string content is only ever rewritten to the equivalent block, never
     * the other way, so cache_control blocks are untouched.
     */
    static byte[] legacyWire(byte[] body) {
        try {
            tools.jackson.databind.JsonNode root = JSON.readTree(body);
            if (!(root instanceof tools.jackson.databind.node.ObjectNode request)) {
                return body;
            }
            boolean changed = false;
            if (!request.has("stream")) {
                request.put("stream", false);
                changed = true;
            }
            if (request.get("messages") instanceof tools.jackson.databind.node.ArrayNode messages) {
                for (tools.jackson.databind.JsonNode message : messages) {
                    if (message instanceof tools.jackson.databind.node.ObjectNode m
                            && m.get("content")
                                    instanceof tools.jackson.databind.node.StringNode text) {
                        tools.jackson.databind.node.ArrayNode blocks = JSON.createArrayNode();
                        blocks.addObject().put("type", "text").put("text", text.stringValue());
                        m.set("content", blocks);
                        changed = true;
                    }
                }
            }
            return changed ? JSON.writeValueAsBytes(request) : body;
        } catch (RuntimeException e) {
            return body; // this hook must never be the reason a chat request fails
        }
    }

    private static final tools.jackson.databind.ObjectMapper JSON =
            com.agenticai.core.json.JsonMappers.plain();

    /**
     * The chat model for {@code apiKey}; {@code options} carry the model, sampling and cache
     * options.
     */
    AnthropicChatModel chatModel(String apiKey, AnthropicChatOptions options) {
        if (apiKey == null) {
            throw new IllegalArgumentException("An Anthropic API key is required");
        }
        AnthropicChatOptions configured =
                options.mutate()
                        .apiKey(apiKey)
                        // The real clocks live on the shared transport; the SDK-side shell client
                        // adds none.
                        .timeout(Duration.ZERO)
                        .maxRetries(0)
                        .build();
        Interceptor terminal =
                new LlmRetryInterceptor(httpClients.transport(false), modelSupport.retry());
        AnthropicHttpClientBuilderCustomizer customizer =
                builder -> {
                    builder.timeout(Duration.ZERO);
                    builder.interceptor(
                            OpenAiCompatModelBuilder.rewriteBody(
                                    AnthropicModelBuilder::legacyWire));
                    builder.interceptor(terminal);
                };
        return AnthropicChatModel.builder()
                .options(configured)
                .toolCallingManager(toolCallingManagers.create())
                .observationRegistry(modelSupport.observationRegistry())
                .httpClientBuilderCustomizer(customizer)
                .build();
    }
}
