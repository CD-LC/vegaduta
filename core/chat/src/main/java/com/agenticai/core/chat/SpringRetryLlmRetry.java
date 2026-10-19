package com.agenticai.core.chat;

import com.google.genai.errors.ServerException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

/**
 * {@link LlmRetry} on spring-retry: the exact policy {@code LlmModelSupport} used to build inline
 * (moved verbatim in ADR-0012 P1b). Which failures are worth a second attempt is built on Spring AI's
 * own classification ({@code RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER} maps HTTP 5xx to {@link
 * TransientAiException} and 4xx - including 429 - to {@code NonTransientAiException}), plus two
 * additions:
 * <ul>
 *   <li>A read timeout is NEVER retried. It already cost the full {@code app.llm.http.read-timeout};
 *       retrying doubles the stall while the provider fallback waits. Connect timeouts and other I/O
 *       failures before a response ({@link ResourceAccessException}) are cheap and are retried.</li>
 *   <li>google-genai's {@code ServerException} (Gemini/Vertex 5xx), which {@code GoogleGenAiChatModel}
 *       wraps in a plain RuntimeException, is treated like Spring AI's own transient type.</li>
 * </ul>
 * 429 is deliberately not retried: rate limits are exactly what the caller's provider fallback exists
 * to route around.
 */
final class SpringRetryLlmRetry implements LlmRetry {

    private static final Logger log = LoggerFactory.getLogger(SpringRetryLlmRetry.class);

    private final RetryTemplate template;
    private final int maxAttempts;

    SpringRetryLlmRetry(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
        // RetryTemplateBuilder asserts initial >= 1ms and max > initial.
        Duration initial = initialBackoff == null || initialBackoff.toMillis() < 1 ? Duration.ofMillis(1) : initialBackoff;
        Duration max = maxBackoff == null || maxBackoff.compareTo(initial) <= 0 ? initial.plusMillis(1) : maxBackoff;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.template = RetryTemplate.builder()
            .maxAttempts(this.maxAttempts)
            .retryOn(SpringRetryLlmRetry::isTransient)
            .exponentialBackoff(initial, 2.0, max)
            .withListener(new RetryListener() {
                @Override
                public <T, E extends Throwable> void onError(
                    RetryContext context, RetryCallback<T, E> callback, Throwable throwable
                ) {
                    // Only transient failures are interesting here; a 4xx is not
                    // retried and is reported by whoever handles it upstream.
                    if (isTransient(throwable)) {
                        log.warn("LLM call attempt {} failed transiently: {}", context.getRetryCount(), throwable.toString());
                    }
                }
            })
            .build();
    }

    @Override
    public <T> T execute(Supplier<T> call) {
        return template.execute(ctx -> call.get());
    }

    @Override
    public int maxAttempts() {
        return maxAttempts;
    }

    @Override
    public RetryTemplate forSpringAi() {
        return template;
    }

    /** Which failures are worth a second attempt - see the class javadoc. */
    static boolean isTransient(Throwable failure) {
        boolean transientSeen = false;
        int depth = 0;
        for (Throwable t = failure; t != null && depth++ < 16; t = t.getCause() == t ? null : t.getCause()) {
            if (isReadTimeout(t) || t instanceof LlmHttpClientFactory.ModelServerUrlNotAllowedException) {
                return false;
            }
            if (t instanceof TransientAiException
                || t instanceof ResourceAccessException
                || t instanceof ServerException) {
                transientSeen = true;
            }
        }
        return transientSeen;
    }

    private static boolean isReadTimeout(Throwable t) {
        if (t instanceof HttpConnectTimeoutException) {
            return false;
        }
        if (t instanceof HttpTimeoutException) {
            return true; // JDK HttpClient's per-request (response) timeout
        }
        if (t instanceof SocketTimeoutException) {
            // Apache HttpClient 5's read timeout. Its connect timeout is a
            // ConnectTimeoutException - not a SocketTimeoutException - and stays
            // retryable; a plain-socket "Connect timed out" is excluded here.
            String message = t.getMessage();
            return message == null || !message.toLowerCase(Locale.ROOT).contains("connect");
        }
        return false;
    }
}
