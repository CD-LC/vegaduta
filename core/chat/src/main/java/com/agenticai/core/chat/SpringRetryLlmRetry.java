package com.agenticai.core.chat;

import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.context.RetryContextSupport;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.Sleeper;
import org.springframework.retry.backoff.ThreadWaitSleeper;
import org.springframework.retry.support.RetryTemplate;

/**
 * {@link LlmRetry} on spring-retry, with the failure classification and backoff of {@link LlmRetryPolicy}.
 * Which failures are worth another attempt is built on Spring AI's own classification ({@code
 * RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER} maps HTTP 5xx to {@code TransientAiException} and 4xx to
 * {@code NonTransientAiException}, carrying only {@code "<status> - <body>"}), plus: HTTP 429 is retried
 * after the provider's Retry-After; a read timeout is NEVER retried (it already cost the full {@code
 * app.llm.http.read-timeout}); connect timeouts and other I/O failures before a response are retried;
 * google-genai's {@code ServerException} (Gemini/Vertex 5xx) counts like Spring AI's own transient type.
 */
final class SpringRetryLlmRetry implements LlmRetry {

    private static final Logger log = LoggerFactory.getLogger(SpringRetryLlmRetry.class);

    private final RetryTemplate template;
    private final LlmRetryPolicy policy;

    /** The attempts and backoff of the three classic settings, with the other policy values at their defaults. */
    SpringRetryLlmRetry(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
        this(policyOf(maxAttempts, initialBackoff, maxBackoff), new ThreadWaitSleeper());
    }

    SpringRetryLlmRetry(LlmRetryPolicy policy, Sleeper sleeper) {
        this.policy = policy;
        // The attempts are asked per call (not fixed at construction): the tenant of the calling thread may have its
        // own chat.retry.maxAttempts.
        this.template = RetryTemplate.builder()
            .customPolicy(new AttemptsRetryPolicy(policy))
            .customBackoff(new PolicyBackOff(policy, sleeper))
            .withListener(new RetryListener() {
                @Override
                public <T, E extends Throwable> void onError(
                    RetryContext context, RetryCallback<T, E> callback, Throwable throwable
                ) {
                    // Only retryable failures are interesting here; a 4xx is not retried and is
                    // reported by whoever handles it upstream.
                    if (policy.isRetryable(throwable)) {
                        log.warn("LLM call attempt {} failed transiently: {}", context.getRetryCount(), throwable.toString());
                    }
                }
            })
            .build();
    }

    static LlmRetryPolicy policyOf(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
        LlmRetryProperties props = new LlmRetryProperties();
        props.setMaxAttempts(maxAttempts);
        // The backoff builder needs initial >= 1ms and max > initial.
        Duration initial = initialBackoff == null || initialBackoff.toMillis() < 1 ? Duration.ofMillis(1) : initialBackoff;
        Duration max = maxBackoff == null || maxBackoff.compareTo(initial) <= 0 ? initial.plusMillis(1) : maxBackoff;
        props.setInitialBackoff(initial);
        props.setMaxBackoff(max);
        return new LlmRetryPolicy(props);
    }

    @Override
    public <T> T execute(Supplier<T> call) {
        return template.execute(ctx -> call.get());
    }

    @Override
    public int maxAttempts() {
        return policy.maxAttempts();
    }

    @Override
    public RetryTemplate forSpringAi() {
        return template;
    }

    /**
     * Retries a failure {@link LlmRetryPolicy#isRetryable} accepts while fewer than {@link LlmRetryPolicy#maxAttempts()}
     * attempts have been made; the limit is read at each decision, on the calling thread, so a tenant's override applies.
     */
    private static final class AttemptsRetryPolicy implements RetryPolicy {

        private static final long serialVersionUID = 1L;

        private final transient LlmRetryPolicy policy;

        AttemptsRetryPolicy(LlmRetryPolicy policy) {
            this.policy = policy;
        }

        @Override
        public boolean canRetry(RetryContext context) {
            Throwable last = context.getLastThrowable();
            return (last == null || policy.isRetryable(last)) && context.getRetryCount() < policy.maxAttempts();
        }

        @Override
        public RetryContext open(RetryContext parent) {
            return new RetryContextSupport(parent);
        }

        @Override
        public void close(RetryContext context) {
            // nothing to release
        }

        @Override
        public void registerThrowable(RetryContext context, Throwable throwable) {
            ((RetryContextSupport) context).registerThrowable(throwable);
        }

        @Override
        public int getMaxAttempts() {
            return policy.maxAttempts();
        }
    }

    /** Which failures are worth another attempt, under the shipped defaults - see {@link LlmRetryPolicy}. */
    static boolean isTransient(Throwable failure) {
        return LlmRetryPolicy.defaults().isRetryable(failure);
    }

    /** Waits {@link LlmRetryPolicy#backoff} (exponential, jittered, at least the provider's Retry-After) between attempts. */
    private static final class PolicyBackOff implements BackOffPolicy {

        private final LlmRetryPolicy policy;
        private final Sleeper sleeper;

        PolicyBackOff(LlmRetryPolicy policy, Sleeper sleeper) {
            this.policy = policy;
            this.sleeper = sleeper;
        }

        @Override
        public BackOffContext start(RetryContext context) {
            return new Context(context);
        }

        @Override
        public void backOff(BackOffContext backOffContext) throws BackOffInterruptedException {
            RetryContext retry = ((Context) backOffContext).retry;
            long millis = policy.backoff(retry.getRetryCount(), retry.getLastThrowable()).toMillis();
            try {
                sleeper.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BackOffInterruptedException("Interrupted while backing off an LLM call", e);
            }
        }

        private record Context(RetryContext retry) implements BackOffContext {}
    }
}
