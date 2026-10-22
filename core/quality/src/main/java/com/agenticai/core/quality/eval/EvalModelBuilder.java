package com.agenticai.core.quality.eval;

import com.openai.core.Timeout;
import java.time.Duration;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The single place the eval harness builds its bare (no Spring context) OpenAI-compatible and
 * Anthropic models (ADR-0012 P1b, on the official vendor SDKs since P3). Same role as core/chat's
 * {@code OpenAiCompatModelBuilder}/{@code AnthropicModelBuilder}, kept separate on purpose: this
 * module deliberately depends on no other core module (see the module description), so it cannot
 * share the chat builders. Option values are exactly what {@code EvalRunner} always set.
 *
 * <p>Timeouts: the SDK models sit on OkHttp, so the connect timeout is OkHttp's and the read
 * timeout is its per-read inactivity timeout (a slow but working completion is never cut off; a
 * provider that goes silent is). The overall call deadline is disabled. The SDKs' own retry loop
 * keeps its default (2 retries), replacing Spring AI 1.x's much longer default retry template.
 */
final class EvalModelBuilder {

    private EvalModelBuilder() {}

    /**
     * Any OpenAI-compatible endpoint: Ollama serves /v1/chat/completions under its root base URL,
     * so the SDK (which appends {@code chat/completions} to its base URL) is given {@code baseUrl +
     * "/v1"} - the same URL the old {@code completionsPath("/v1/chat/completions")} produced.
     */
    static OpenAiChatModel openAiCompat(
            String baseUrl,
            String apiKey,
            String model,
            int maxTokens,
            Duration connectTimeout,
            Duration readTimeout) {
        OpenAiChatOptions options =
                OpenAiChatOptions.builder()
                        .baseUrl(trimSlash(baseUrl) + "/v1")
                        .apiKey(apiKey)
                        .model(model)
                        .maxTokens(maxTokens)
                        .timeout(Duration.ZERO)
                        .build();
        return OpenAiChatModel.builder()
                .options(options)
                .httpClientBuilderCustomizer(
                        builder ->
                                builder.timeout(
                                        Timeout.builder()
                                                .connect(connectTimeout)
                                                .read(readTimeout)
                                                .write(readTimeout)
                                                .request(Duration.ZERO)
                                                .build()))
                .build();
    }

    /**
     * No temperature/topP: works across every model, including ones that reject {@code
     * temperature}.
     */
    static AnthropicChatModel anthropic(
            String apiKey,
            String model,
            int maxTokens,
            Duration connectTimeout,
            Duration readTimeout) {
        AnthropicChatOptions options =
                AnthropicChatOptions.builder()
                        .apiKey(apiKey)
                        .model(model)
                        .maxTokens(maxTokens)
                        .timeout(Duration.ZERO)
                        .build();
        return AnthropicChatModel.builder()
                .options(options)
                .httpClientBuilderCustomizer(
                        builder ->
                                builder.timeout(
                                        com.anthropic.core.Timeout.builder()
                                                .connect(connectTimeout)
                                                .read(readTimeout)
                                                .write(readTimeout)
                                                .request(Duration.ZERO)
                                                .build()))
                .build();
    }

    private static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
