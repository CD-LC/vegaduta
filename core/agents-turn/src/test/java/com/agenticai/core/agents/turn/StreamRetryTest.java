package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.chat.LlmRetryPolicy;
import com.agenticai.core.chat.LlmRetryProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

/**
 * A streaming turn that fails before its first token is retried like any other call: bounded,
 * exponential backoff with jitter, the provider's Retry-After honoured. It never retries once a token
 * was sent or a tool ran (the retry would replay both). Time is virtual ({@link ManualScheduler}).
 */
class StreamRetryTest {

    private final ManualScheduler time = new ManualScheduler();
    private final AtomicInteger attempts = new AtomicInteger();
    private final List<String> received = new ArrayList<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicBoolean toolsRan = new AtomicBoolean();

    private static LlmRetryPolicy policy(int maxAttempts) {
        LlmRetryProperties p = new LlmRetryProperties();
        p.setMaxAttempts(maxAttempts);
        p.setInitialBackoff(Duration.ofSeconds(1));
        p.setMaxBackoff(Duration.ofSeconds(10));
        p.setJitter(0.0);
        p.setMaxRetryAfter(Duration.ofSeconds(30));
        return new LlmRetryPolicy(p, () -> 0.0);
    }

    private void run(LlmRetryPolicy policy, java.util.function.IntFunction<Flux<String>> attemptN) {
        StreamRetry.apply(
                        attemptN.apply(attempts.incrementAndGet()),
                        () -> attemptN.apply(attempts.incrementAndGet()),
                        policy,
                        () -> !toolsRan.get(),
                        com.agenticai.core.security.TenantScope.of((com.agenticai.core.security.TenantContext) null),
                        time,
                        (n, wait, failure) -> {})
                .subscribe(received::add, error::set, () -> completed.set(true));
    }

    private static WebClientResponseException http(int status, String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.add("Retry-After", retryAfter);
        }
        return WebClientResponseException.create(status, "status", headers, new byte[0], StandardCharsets.UTF_8);
    }

    @Test
    void aFailureBeforeTheFirstTokenIsRetriedAfterTheBackoffNotBefore() {
        run(policy(3), n -> n < 3 ? Flux.error(new TransientAiException("503 - overloaded")) : Flux.just("ok"));

        assertThat(attempts).hasValue(1);
        time.advance(Duration.ofMillis(900));
        assertThat(attempts).as("still inside the first 1 s backoff").hasValue(1);
        time.advance(Duration.ofMillis(200));
        assertThat(attempts).hasValue(2);
        time.advance(Duration.ofSeconds(1));
        assertThat(attempts).as("the second backoff is 2 s").hasValue(2);
        time.advance(Duration.ofSeconds(1));
        assertThat(attempts).hasValue(3);
        assertThat(received).containsExactly("ok");
        assertThat(completed).isTrue();
        assertThat(error.get()).isNull();
    }

    @Test
    void theProvidersRetryAfterIsWaitedOutInFull() {
        run(policy(2), n -> n == 1 ? Flux.error(http(429, "7")) : Flux.just("ok"));

        time.advance(Duration.ofSeconds(6));
        assertThat(attempts).hasValue(1);
        time.advance(Duration.ofSeconds(2));

        assertThat(attempts).hasValue(2);
        assertThat(received).containsExactly("ok");
    }

    @Test
    void theLastFailureIsReportedWhenTheAttemptsRunOut() {
        TransientAiException last = new TransientAiException("503 - still overloaded");
        run(policy(2), n -> Flux.error(n == 1 ? new TransientAiException("503 - overloaded") : last));

        time.advance(Duration.ofMinutes(1));

        assertThat(attempts).hasValue(2);
        assertThat(error.get()).isSameAs(last);
        assertThat(completed).isFalse();
    }

    @Test
    void aClientErrorOrALongRetryAfterIsNotRetried() {
        run(policy(3), n -> Flux.error(new NonTransientAiException("400 - bad request")));
        time.advance(Duration.ofMinutes(1));
        assertThat(attempts).hasValue(1);
        assertThat(error.get()).isInstanceOf(NonTransientAiException.class);

        attempts.set(0);
        error.set(null);
        run(policy(3), n -> Flux.error(http(429, "120")));
        time.advance(Duration.ofMinutes(5));
        assertThat(attempts).as("120 s is past max-retry-after: let the fallback provider take over").hasValue(1);
    }

    @Test
    void aStreamThatAlreadySentATokenIsNeverRetried() {
        IllegalStateException cut = new IllegalStateException("dropped mid-stream");
        run(policy(3), n -> Flux.concat(Flux.just("partial"), Flux.error(new TransientAiException("503 - dropped"))));

        time.advance(Duration.ofMinutes(1));

        assertThat(attempts).hasValue(1);
        assertThat(received).containsExactly("partial");
        assertThat(error.get()).isInstanceOf(TransientAiException.class);
        assertThat(cut).isNotNull();
    }

    @Test
    void noRetryOnceAToolHasRunBecauseTheRetryWouldRunItAgain() {
        toolsRan.set(true);

        run(policy(3), n -> Flux.error(new TransientAiException("503 - overloaded")));
        time.advance(Duration.ofMinutes(1));

        assertThat(attempts).hasValue(1);
        assertThat(error.get()).isInstanceOf(TransientAiException.class);
    }

    @Test
    void aSuccessfulFirstAttemptIsPassedThroughUntouched() {
        run(policy(3), n -> Flux.just("a", "b"));

        assertThat(received).containsExactly("a", "b");
        assertThat(completed).isTrue();
        assertThat(attempts).hasValue(1);
    }

    @Test
    void everyRetryIsReportedWithItsWait() {
        List<String> seen = new ArrayList<>();
        java.util.function.Supplier<Flux<String>> attempt =
                () -> attempts.incrementAndGet() == 1
                        ? Flux.<String>error(new TransientAiException("503 - overloaded"))
                        : Flux.just("ok");
        StreamRetry.apply(
                        attempt.get(),
                        attempt,
                        policy(3),
                        () -> true,
                        com.agenticai.core.security.TenantScope.of((com.agenticai.core.security.TenantContext) null),
                        time,
                        (n, wait, failure) -> seen.add(n + ":" + wait.toMillis()))
                .subscribe();
        time.advance(Duration.ofSeconds(5));

        assertThat(seen).containsExactly("1:1000");
    }
}
