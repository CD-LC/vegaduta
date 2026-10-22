package com.agenticai.core.chat;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the platform's bounded {@link LlmRetry} policy around the terminal transport interceptor
 * ({@link LlmHttpClientFactory#transport}) of an OpenAI/Anthropic SDK model (ADR-0012 P3).
 *
 * <p>Why here: Spring AI 1.x wrapped the model call in a {@code RetryTemplate} that treated HTTP
 * 5xx as {@code TransientAiException}; Spring AI 2's SDK-based models take no retry template, and
 * the SDKs' own retry loop has a different policy (it also retries 408/409/429 and read timeouts).
 * The SDK loop is disabled ({@code maxRetries(0)}) and the platform policy is re-applied at the
 * HTTP layer, where it covers the blocking and the streaming call alike:
 *
 * <ul>
 *   <li>HTTP 5xx and connection failures (connect timeout, refused, reset) are retried up to {@link
 *       LlmRetry#maxAttempts()} with {@link LlmRetry#backoff} between attempts;
 *   <li>a read timeout is never retried, and 4xx (including 429) are returned to the SDK untouched
 *       so the caller's provider fallback sees them;
 *   <li>after the last attempt the final response (or failure) is handed back unchanged.
 * </ul>
 *
 * Each transient attempt is logged once at WARN, as the old {@code RetryListener} did.
 */
final class LlmRetryInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(LlmRetryInterceptor.class);

    private final Interceptor terminal;
    private final LlmRetry retry;

    LlmRetryInterceptor(Interceptor terminal, LlmRetry retry) {
        this.terminal = terminal;
        this.retry = retry;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        int attempts = Math.max(1, retry.maxAttempts());
        for (int attempt = 1; ; attempt++) {
            boolean last = attempt >= attempts;
            Response response;
            try {
                response = terminal.intercept(chain);
            } catch (IOException e) {
                if (last || !retry.isTransient(e)) {
                    throw e;
                }
                log.warn("LLM call attempt {} failed transiently: {}", attempt, e.toString());
                pause(attempt);
                continue;
            }
            if (response.code() < 500 || last) {
                return response;
            }
            log.warn("LLM call attempt {} failed transiently: HTTP {}", attempt, response.code());
            response.close();
            pause(attempt);
        }
    }

    private void pause(int retryNumber) throws IOException {
        try {
            Thread.sleep(retry.backoff(retryNumber).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("interrupted while backing off an LLM retry");
        }
    }
}
