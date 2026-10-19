package com.agenticai.core.quality.eval;

import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The {@link EvalRunner}'s LLM HTTP transport: blocking and streaming client builders bounded by the same
 * connect/read timeout env vars core/app's {@code app.llm.http.*} block reads. Split out of the runner.
 */
final class EvalLlmHttp {

    private static final Logger log = LoggerFactory.getLogger(EvalLlmHttp.class);

    /**
     * LLM HTTP timeouts - the SAME env vars and defaults core/app's {@code
     * app.llm.http.*} block reads for {@code LlmHttpClientFactory} (core/chat),
     * mirrored here rather than imported because this module deliberately has
     * no core/chat dependency (see class javadoc). Before this, both
     * {@code AnthropicApi} and {@code OpenAiApi} were built on Spring AI's
     * default {@code RestClient}/{@code WebClient}, which carry NO connect and
     * NO read timeout: a provider or an {@code EVAL_BASE_URL} Ollama that
     * accepted the connection and then went silent hung the CI job until the
     * workflow-level timeout killed it, with no per-case error. The read
     * timeout is time-to-first-response (headers), not time-to-last-token, so
     * the generous 10m default never cuts off a slow but working completion.
     */
    static final String LLM_CONNECT_TIMEOUT_ENV_VAR = "LLM_HTTP_CONNECT_TIMEOUT";
    static final String LLM_READ_TIMEOUT_ENV_VAR = "LLM_HTTP_READ_TIMEOUT";
    static final Duration DEFAULT_LLM_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEFAULT_LLM_READ_TIMEOUT = Duration.ofMinutes(10);

    /**
     * Blocking-path client for Spring AI's {@code *Api} builders, bounded by
     * the LLM connect/read timeouts (see {@link #LLM_CONNECT_TIMEOUT_ENV_VAR}).
     * Same transport shape as {@code LlmHttpClientFactory#restClientBuilder}:
     * the JDK HttpClient carries the connect timeout, the request factory arms
     * the read timeout per request until response headers arrive. A fresh
     * builder per call because Spring AI's {@code *Api} builders mutate it.
     */
    static RestClient.Builder llmRestClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(llmTimeout(LLM_CONNECT_TIMEOUT_ENV_VAR, DEFAULT_LLM_CONNECT_TIMEOUT))
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(llmTimeout(LLM_READ_TIMEOUT_ENV_VAR, DEFAULT_LLM_READ_TIMEOUT));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * Streaming-path counterpart of {@link #llmRestClientBuilder()} - same
     * connector {@code LlmHttpClientFactory#webClientBuilder} uses. This runner
     * only ever calls {@code call()}, but Spring AI's {@code *Api} builders
     * take both, and leaving this one on the unbounded default would silently
     * reintroduce the hang the moment anyone switches a case to {@code stream()}.
     */
    static WebClient.Builder llmWebClientBuilder() {
        JdkClientHttpConnector connector = new JdkClientHttpConnector(HttpClient.newBuilder()
            .connectTimeout(llmTimeout(LLM_CONNECT_TIMEOUT_ENV_VAR, DEFAULT_LLM_CONNECT_TIMEOUT))
            .build());
        connector.setReadTimeout(llmTimeout(LLM_READ_TIMEOUT_ENV_VAR, DEFAULT_LLM_READ_TIMEOUT));
        return WebClient.builder().clientConnector(connector);
    }

    private static Duration llmTimeout(String envVar, Duration fallback) {
        return parseTimeout(System.getenv(envVar), fallback);
    }

    /**
     * Parses a timeout in the same formats Spring Boot accepts for {@code
     * app.llm.http.*} ({@code "10s"}, {@code "10m"}, ISO-8601 {@code
     * "PT10S"}, or bare milliseconds), so one env var value means the same
     * thing to core/app and to this runner. Unset, blank, unparseable, zero or
     * negative falls back rather than throwing - a malformed env var must not
     * crash the run, and a non-positive timeout would mean "no timeout", the
     * exact bug this exists to close. Package-private for {@code
     * EvalRunnerHttpTimeoutTest}.
     */
    static Duration parseTimeout(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            Duration parsed = DurationStyle.detectAndParse(raw.trim());
            if (parsed.isZero() || parsed.isNegative()) {
                log.warn("[eval] LLM HTTP timeout '{}' is not positive - falling back to {}", raw, fallback);
                return fallback;
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            log.warn("[eval] LLM HTTP timeout '{}' is not a valid duration - falling back to {}", raw, fallback);
            return fallback;
        }
    }

    private EvalLlmHttp() {
    }
}
