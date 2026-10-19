package com.agenticai.core.chat;

import java.util.function.Supplier;
import org.springframework.retry.support.RetryTemplate;

/**
 * The bounded retry policy for LLM calls, behind an internal interface (ADR-0012 P1b) so that the
 * {@code spring-retry} to Spring Framework 7 core-retry move (Boot 4 / Spring AI 2) touches the
 * implementation ({@link SpringRetryLlmRetry}) and {@link #forSpringAi()} only, not every model
 * builder and {@link GeminiMixedContentToolCallChatModel}.
 *
 * <p>The policy itself lives in {@link LlmModelSupport} docs: few attempts, short capped exponential
 * backoff, transient errors only, never after a read timeout; each transient failure is logged.
 */
public interface LlmRetry {

    /** Runs {@code call}, retrying transient failures per the policy; rethrows the last failure. */
    <T> T execute(Supplier<T> call);

    /** Maximum number of attempts (first call included). */
    int maxAttempts();

    /**
     * Adapter for Spring AI 1.x model builders, which take a spring-retry {@link RetryTemplate}.
     * The only method that exposes a spring-retry type; it goes away with Spring AI 1.x.
     */
    RetryTemplate forSpringAi();
}
