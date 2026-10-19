package com.agenticai.core.tenancy.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.tenancy.replica.BoundedRedisCall.RedisCallUnavailableException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BoundedRedisCallTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final CountDownLatch neverReleased = new CountDownLatch(1);
    private final BoundedRedisCall bounded = new BoundedRedisCall("bounded-redis-test", 2, 5_000L, nanos::get);

    @AfterEach
    void stop() {
        neverReleased.countDown();
        bounded.shutdown();
    }

    @Test
    void returnsTheActionsResult() {
        assertThat(bounded.call(1_000L, () -> "ok")).isEqualTo("ok");
    }

    @Test
    void anActionFailureIsRethrownAsIs() {
        assertThatThrownBy(() -> bounded.call(1_000L, () -> {
            throw new IllegalStateException("redis down");
        })).isInstanceOf(IllegalStateException.class).hasMessage("redis down");
    }

    @Test
    void aHangingActionTimesOutQuicklyAndTheBreakerAnswersImmediatelyUntilItCloses() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> bounded.call(100L, () -> {
            neverReleased.await();
            return "never";
        })).isInstanceOf(RedisCallUnavailableException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5_000L);

        // Breaker open: the action is not even started.
        AtomicLong runs = new AtomicLong();
        assertThatThrownBy(() -> bounded.call(100L, () -> runs.incrementAndGet()))
            .isInstanceOf(RedisCallUnavailableException.class);
        assertThat(runs.get()).isZero();

        // Once the breaker window has passed, calls go through again.
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(6));
        assertThat(bounded.call(1_000L, () -> "back")).isEqualTo("back");
    }

    @Test
    void aSaturatedPoolRejectsInsteadOfQueueing() throws Exception {
        // Two workers (the pool maximum) both stuck on hung commands: a third caller is rejected, not queued.
        BoundedRedisCall noBreaker = new BoundedRedisCall("bounded-redis-saturated", 2, 0L, nanos::get);
        try {
            CountDownLatch hold = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(2);
            for (int i = 0; i < 2; i++) {
                Thread filler = new Thread(() -> {
                    try {
                        noBreaker.call(5_000L, () -> {
                            started.countDown();
                            hold.await();
                            return "x";
                        });
                    } catch (RuntimeException ignored) {
                        // released at the end of the test
                    }
                });
                filler.setDaemon(true);
                filler.start();
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> noBreaker.call(50L, () -> "late"))
                .isInstanceOf(RedisCallUnavailableException.class)
                .hasMessageContaining("busy");
            hold.countDown();
        } finally {
            noBreaker.shutdown();
        }
    }
}
