package com.agenticai.core.chat;

import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

/**
 * Replays a streaming chat request once when Ollama rejects the model's own tool call.
 *
 * <p>Seen live with qwen3.5:4b on Ollama: the model sometimes writes a tool call whose XML is malformed
 * ({@code <function>} closed by {@code </parameter>}). Ollama parses that output itself and answers the
 * request with HTTP 500 {@code "XML syntax error on line 3: element <function> closed by </parameter>"}
 * (or "expected element type &lt;function&gt; but have &lt;parameter&gt;"), so the whole reply failed with
 * "every model failed" although nothing was wrong with the install. Sampling is not deterministic, so the
 * same request usually comes back well-formed.
 *
 * <p>Replaying at the HTTP level is safe: the failed response is the answer to ONE request that carries
 * the whole conversation, no tool ran because of it, and nothing was streamed. The blocking path already
 * gets this from {@link SpringRetryLlmRetry} (a 500 is transient there); the streaming path did not.
 * Exactly one replay, only for that parse error: any other 500 passes through unchanged, and a second
 * failure is reported as it came.
 */
final class OllamaToolCallParseRetry {

    private static final Logger log = LoggerFactory.getLogger(OllamaToolCallParseRetry.class);

    static final String METRIC = "llm.ollama.toolcall_parse_retry";

    private OllamaToolCallParseRetry() {}

    static ExchangeFilterFunction filter() {
        return (request, next) ->
                next.exchange(request)
                        .flatMap(
                                response -> {
                                    if (response.statusCode().value() != 500) {
                                        return Mono.just(response);
                                    }
                                    return response.bodyToMono(String.class)
                                            .defaultIfEmpty("")
                                            .flatMap(
                                                    body -> {
                                                        if (!isMalformedToolCall(body)) {
                                                            // Not ours: hand the response back with its body intact.
                                                            return Mono.just(response.mutate().body(body).build());
                                                        }
                                                        log.warn(
                                                                "Ollama rejected the model's tool call as malformed ({}); replaying the request once",
                                                                abbreviate(body));
                                                        try {
                                                            Metrics.counter(METRIC).increment();
                                                        } catch (RuntimeException ignored) {
                                                            // metrics never change the outcome
                                                        }
                                                        return next.exchange(request);
                                                    });
                                });
    }

    /** True for the two wordings Ollama uses when it cannot parse a model-written tool call. */
    static boolean isMalformedToolCall(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        return body.contains("XML syntax error") || body.contains("expected element type <function>");
    }

    private static String abbreviate(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }
}
