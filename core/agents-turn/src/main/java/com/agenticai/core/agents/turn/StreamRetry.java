package com.agenticai.core.agents.turn;

import com.agenticai.core.chat.LlmRetryPolicy;
import com.agenticai.core.security.TenantScope;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Retries a model stream that failed BEFORE its first token, under the same {@link LlmRetryPolicy} as the
 * non-streaming calls (HTTP 429 and 5xx, bounded attempts, exponential backoff with jitter, the provider's
 * Retry-After). Spring AI retries a blocking call per HTTP round trip but not a stream, so a transient
 * failure at the start of a streaming turn went straight to the provider fallback.
 *
 * <p>It retries only while a retry is invisible and harmless: no token has been sent to the person, and
 * {@code mayRetry} still holds. The caller passes "no tool has run this turn" for {@code mayRetry}, because
 * the stream is the whole tool loop and a retry would run its tools again.
 */
final class StreamRetry {

    private StreamRetry() {}

    /** Told about each retry as it is scheduled: the retry number (1 for the first), the wait, the failure. */
    @FunctionalInterface
    interface Listener {
        void retrying(int retryNumber, Duration wait, Throwable failure);
    }

    /**
     * @param first the first attempt, already assembled by the caller (so an assembly-time failure
     *     surfaces exactly where it did before this operator existed, and the operator chain the caller
     *     built on {@code first} is the one that continues)
     * @param retry builds a fresh request for each retry; never re-subscribes to {@code first}
     * @param scope the turn's tenant, in force while a retry's request is built and subscribed
     */
    static Flux<String> apply(
            Flux<String> first,
            Supplier<Flux<String>> retry,
            LlmRetryPolicy policy,
            BooleanSupplier mayRetry,
            TenantScope scope,
            Scheduler scheduler,
            Listener listener) {
        AtomicBoolean tokenSent = new AtomicBoolean();
        return first.doOnNext(token -> tokenSent.set(true))
                .onErrorResume(failure -> recover(1, failure, tokenSent, retry, policy, mayRetry, scope, scheduler, listener));
    }

    /** After {@code attemptsMade} failed attempts: wait and try again, or give the failure up. */
    private static Flux<String> recover(
            int attemptsMade,
            Throwable failure,
            AtomicBoolean tokenSent,
            Supplier<Flux<String>> retry,
            LlmRetryPolicy policy,
            BooleanSupplier mayRetry,
            TenantScope scope,
            Scheduler scheduler,
            Listener listener) {
        if (tokenSent.get()
                || attemptsMade >= policy.maxAttempts(scope)
                || !policy.isRetryable(failure)
                || !mayRetry.getAsBoolean()) {
            return Flux.error(failure);
        }
        Duration wait = policy.backoff(attemptsMade, failure);
        listener.retrying(attemptsMade, wait, failure);
        return Mono.delay(wait, scheduler)
                .thenMany(
                        // The retry runs on the timer thread: it builds and subscribes its request in the
                        // turn's tenant scope (advisors and request assembly read the tenant).
                        TenantScopedFlux.subscribeInScope(scope, retry)
                                .doOnNext(token -> tokenSent.set(true))
                                .onErrorResume(
                                        next ->
                                                recover(
                                                        attemptsMade + 1,
                                                        next,
                                                        tokenSent,
                                                        retry,
                                                        policy,
                                                        mayRetry,
                                                        scope,
                                                        scheduler,
                                                        listener)));
    }
}
