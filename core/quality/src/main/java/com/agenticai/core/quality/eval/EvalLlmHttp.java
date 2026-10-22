package com.agenticai.core.quality.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;

/**
 * The {@link EvalRunner}'s LLM HTTP transport: blocking and streaming client builders bounded by
 * the same connect/read timeout env vars core/app's {@code app.llm.http.*} block reads. Split out
 * of the runner.
 */
final class EvalLlmHttp {

    private static final Logger log = LoggerFactory.getLogger(EvalLlmHttp.class);

    /**
     * LLM HTTP timeouts - the SAME env vars and defaults core/app's {@code app.llm.http.*} block
     * reads for {@code LlmHttpClientFactory} (core/chat), mirrored here rather than imported
     * because this module deliberately has no core/chat dependency (see class javadoc). Before
     * this, both {@code AnthropicApi} and {@code OpenAiApi} were built on Spring AI's default
     * {@code RestClient}/{@code WebClient}, which carry NO connect and NO read timeout: a provider
     * or an {@code EVAL_BASE_URL} Ollama that accepted the connection and then went silent hung the
     * CI job until the workflow-level timeout killed it, with no per-case error. The read timeout
     * is time-to-first-response (headers), not time-to-last-token, so the generous 10m default
     * never cuts off a slow but working completion.
     */
    static final String LLM_CONNECT_TIMEOUT_ENV_VAR = "LLM_HTTP_CONNECT_TIMEOUT";

    static final String LLM_READ_TIMEOUT_ENV_VAR = "LLM_HTTP_READ_TIMEOUT";
    static final Duration DEFAULT_LLM_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEFAULT_LLM_READ_TIMEOUT = Duration.ofMinutes(10);

    /**
     * The connect timeout of the eval harness' LLM calls ({@link #LLM_CONNECT_TIMEOUT_ENV_VAR}).
     * Spring AI 2's models sit on the vendor SDKs' OkHttp client, so the bound is handed to {@link
     * EvalModelBuilder} as a {@link Duration} (the SDK's connect timeout) instead of as a blocking
     * and a streaming client builder.
     */
    static Duration connectTimeout() {
        return llmTimeout(LLM_CONNECT_TIMEOUT_ENV_VAR, DEFAULT_LLM_CONNECT_TIMEOUT);
    }

    /**
     * The read (inactivity) timeout of the eval harness' LLM calls ({@link
     * #LLM_READ_TIMEOUT_ENV_VAR}); the SDK applies it to reads and writes alike.
     */
    static Duration readTimeout() {
        return llmTimeout(LLM_READ_TIMEOUT_ENV_VAR, DEFAULT_LLM_READ_TIMEOUT);
    }

    private static Duration llmTimeout(String envVar, Duration fallback) {
        return parseTimeout(System.getenv(envVar), fallback);
    }

    /**
     * Parses a timeout in the same formats Spring Boot accepts for {@code app.llm.http.*} ({@code
     * "10s"}, {@code "10m"}, ISO-8601 {@code "PT10S"}, or bare milliseconds), so one env var value
     * means the same thing to core/app and to this runner. Unset, blank, unparseable, zero or
     * negative falls back rather than throwing - a malformed env var must not crash the run, and a
     * non-positive timeout would mean "no timeout", the exact bug this exists to close.
     * Package-private for {@code EvalRunnerHttpTimeoutTest}.
     */
    static Duration parseTimeout(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            Duration parsed = DurationStyle.detectAndParse(raw.trim());
            if (parsed.isZero() || parsed.isNegative()) {
                log.warn(
                        "[eval] LLM HTTP timeout '{}' is not positive - falling back to {}",
                        raw,
                        fallback);
                return fallback;
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            log.warn(
                    "[eval] LLM HTTP timeout '{}' is not a valid duration - falling back to {}",
                    raw,
                    fallback);
            return fallback;
        }
    }

    private EvalLlmHttp() {}
}
