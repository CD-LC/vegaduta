package com.agenticai.core.chat;

import com.google.genai.errors.ServerException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.web.client.ResourceAccessException;

/**
 * {@link LlmRetry} on Spring Framework 7's core retry ({@code org.springframework.core.retry},
 * ADR-0012 P3; it was spring-retry until Spring AI 2). Which failures are worth a second attempt is
 * built on Spring AI's own classification ({@link TransientAiException} for HTTP 5xx) plus these
 * additions:
 *
 * <ul>
 *   <li>A read timeout is NEVER retried. It already cost the full {@code
 *       app.llm.http.read-timeout}; retrying doubles the stall while the provider fallback waits.
 *       Connect timeouts and other I/O failures before a response are cheap and are retried.
 *   <li>google-genai's {@code ServerException} (Gemini/Vertex 5xx), which {@code
 *       GoogleGenAiChatModel} wraps in a plain RuntimeException, is treated like Spring AI's own
 *       transient type.
 * </ul>
 *
 * 429 is deliberately not retried: rate limits are exactly what the caller's provider fallback
 * exists to route around. (The SDKs' own retry loop, which does retry 408/409/429/5xx, is switched
 * OFF - see {@link OpenAiCompatModelBuilder} - so this policy is the only one.)
 *
 * <p>Attempt semantics are unchanged from the spring-retry version: {@code maxAttempts} counts the
 * first call, every failed attempt that is transient is logged once, and the LAST failure is
 * rethrown unchanged (never wrapped in {@link RetryException}).
 */
final class SpringRetryLlmRetry implements LlmRetry {

    private static final Logger log = LoggerFactory.getLogger(SpringRetryLlmRetry.class);

    private final RetryTemplate template;
    private final int maxAttempts;
    private final Duration initial;
    private final Duration max;

    SpringRetryLlmRetry(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
        // Clamp like the spring-retry builder needed: initial >= 1ms and max > initial.
        this.initial =
                initialBackoff == null || initialBackoff.toMillis() < 1
                        ? Duration.ofMillis(1)
                        : initialBackoff;
        this.max =
                maxBackoff == null || maxBackoff.compareTo(initial) <= 0
                        ? initial.plusMillis(1)
                        : maxBackoff;
        this.maxAttempts = Math.max(1, maxAttempts);
        RetryPolicy policy =
                RetryPolicy.builder()
                        .maxRetries(this.maxAttempts - 1L)
                        .predicate(SpringRetryLlmRetry::isTransientFailure)
                        .delay(initial)
                        .multiplier(2.0)
                        .maxDelay(max)
                        .build();
        this.template = new RetryTemplate(policy);
        this.template.setRetryListener(
                new RetryListener() {
                    @Override
                    public void onRetryableExecution(
                            RetryPolicy retryPolicy, Retryable<?> retryable, RetryState state) {
                        // Called after EVERY execution (the first included), like the old
                        // per-attempt listener.
                        // Only transient failures are interesting here; a 4xx is not
                        // retried and is reported by whoever handles it upstream.
                        Throwable last = state.getLastException();
                        if (!state.isSuccessful() && last != null && isTransientFailure(last)) {
                            log.warn(
                                    "LLM call attempt {} failed transiently: {}",
                                    state.getRetryCount() + 1,
                                    last.toString());
                        }
                    }
                });
    }

    @Override
    public <T> T execute(Supplier<T> call) {
        return template.invoke(call);
    }

    @Override
    public int maxAttempts() {
        return maxAttempts;
    }

    @Override
    public Duration backoff(int retryNumber) {
        long millis = initial.toMillis();
        for (int i = 1; i < retryNumber && millis < max.toMillis(); i++) {
            millis *= 2;
        }
        return Duration.ofMillis(Math.min(millis, max.toMillis()));
    }

    @Override
    public boolean isTransient(Throwable failure) {
        return SpringRetryLlmRetry.isTransientFailure(failure);
    }

    @Override
    public RetryTemplate forSpringAi() {
        return template;
    }

    /** Which failures are worth a second attempt - see the class javadoc. */
    static boolean isTransientFailure(Throwable failure) {
        boolean transientSeen = false;
        int depth = 0;
        for (Throwable t = failure;
                t != null && depth++ < 16;
                t = t.getCause() == t ? null : t.getCause()) {
            if (isReadTimeout(t)
                    || t instanceof LlmHttpClientFactory.ModelServerUrlNotAllowedException) {
                return false;
            }
            if (t instanceof TransientAiException
                    || t instanceof ResourceAccessException
                    || t instanceof ServerException
                    || t instanceof IOException) {
                transientSeen = true;
            }
        }
        return transientSeen;
    }

    private static boolean isConnectTimeout(Throwable t) {
        String message = t.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("connect");
    }

    private static boolean isReadTimeout(Throwable t) {
        if (t instanceof HttpConnectTimeoutException) {
            return false;
        }
        if (t instanceof HttpTimeoutException) {
            return true; // JDK HttpClient's per-request (response) timeout
        }
        if (t instanceof InterruptedIOException) {
            // SocketTimeoutException (a read timeout; a connect timeout says "connect" in its
            // message and
            // stays retryable) and OkHttp's bare InterruptedIOException("timeout") for call/read
            // deadlines.
            return !isConnectTimeout(t);
        }
        return false;
    }
}
