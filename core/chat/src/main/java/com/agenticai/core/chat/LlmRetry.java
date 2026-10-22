package com.agenticai.core.chat;

import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.core.retry.RetryTemplate;

/**
 * The bounded retry policy for LLM calls, behind an internal interface (ADR-0012 P1b). Since P3 it
 * is built on Spring Framework 7's {@code org.springframework.core.retry.RetryTemplate}
 * (spring-retry is gone in Spring AI 2). It is applied at two levels: {@link #execute} / {@link
 * #forSpringAi()} for models that still take a retry template (google-genai), and - for the
 * OpenAI/Anthropic SDK models, which have no retry template - inside the shared OkHttp transport
 * ({@link LlmRetryInterceptor}) using {@link #maxAttempts()}, {@link #backoff(int)} and {@link
 * #isTransient(Throwable)}.
 *
 * <p>The policy itself lives in {@link LlmModelSupport} docs: few attempts, short capped
 * exponential backoff, transient errors only, never after a read timeout; each transient failure is
 * logged.
 */
public interface LlmRetry {

    /** Runs {@code call}, retrying transient failures per the policy; rethrows the last failure. */
    <T> T execute(Supplier<T> call);

    /** Maximum number of attempts (first call included). */
    int maxAttempts();

    /**
     * Delay to wait before retry number {@code retryNumber} (1 = the first retry), exponential and
     * capped.
     */
    Duration backoff(int retryNumber);

    /** Whether {@code failure} is worth another attempt under this policy. */
    boolean isTransient(Throwable failure);

    /**
     * The same policy as a Spring Framework 7 {@link RetryTemplate}, for Spring AI models that take
     * one.
     */
    RetryTemplate forSpringAi();
}
