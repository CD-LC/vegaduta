package com.agenticai.core.agents.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.SpendCounters;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The shared window-counter scripts against a real Redis (skipped without {@code redis-server}). These are the
 * behaviours a mocked template cannot show: whether the TTL is really attached in the same step as the add, and whether
 * a refund can ever leave a negative counter.
 */
class SpendCountersRedisTest {

    private static LocalRedis local;
    private static StringRedisTemplate redis;
    private static final Duration WINDOW = Duration.ofHours(1);

    @BeforeAll
    static void start() throws Exception {
        local = LocalRedis.start();
        redis = local.template();
    }

    @AfterAll
    static void stop() throws InterruptedException {
        if (local != null) {
            local.stop();
        }
    }

    @BeforeEach
    void clean() {
        local.flush();
    }

    @Test
    void theFirstAddCreatesTheKeyWithTheWindowExpiry() {
        assertThat(SpendCounters.add(redis, "k", WINDOW, 500L)).isEqualTo(500L);

        assertThat(redis.getExpire("k", java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(1L, 3_600_000L);
    }

    @Test
    void aCounterThatLostItsExpiryGetsOneOnTheNextAdd() {
        // What a crash between INCR and EXPIRE used to leave behind: a value that never resets.
        redis.opsForValue().set("immortal", "700");
        assertThat(redis.getExpire("immortal")).isEqualTo(-1L);

        assertThat(SpendCounters.add(redis, "immortal", WINDOW, 100L)).isEqualTo(800L);

        assertThat(redis.getExpire("immortal")).as("the add must repair the missing TTL").isPositive();
    }

    @Test
    void anAddNeverExtendsAWindowThatIsAlreadyRunning() {
        SpendCounters.add(redis, "k", WINDOW, 10L);
        redis.expire("k", Duration.ofSeconds(100));

        SpendCounters.add(redis, "k", WINDOW, 10L);

        assertThat(redis.getExpire("k")).isLessThanOrEqualTo(100L);
    }

    @Test
    void aCounterReturningToExactlyTheAmountAddedDoesNotRestartItsWindow() {
        // The old rule "set the TTL when the result equals the amount added" restarted the window here.
        SpendCounters.add(redis, "k", WINDOW, 400L);
        redis.expire("k", Duration.ofSeconds(100));
        SpendCounters.add(redis, "k", WINDOW, 300L);
        SpendCounters.add(redis, "k", WINDOW, -300L);

        Long total = SpendCounters.add(redis, "k", WINDOW, 300L);

        assertThat(total).isEqualTo(700L);
        assertThat(redis.getExpire("k")).as("the original window keeps running").isLessThanOrEqualTo(100L);
    }

    @Test
    void aRefundAgainstAnExpiredWindowCreatesNothing() {
        assertThat(SpendCounters.add(redis, "gone", WINDOW, -5_000L)).isZero();

        assertThat(redis.hasKey("gone")).isFalse();
    }

    @Test
    void aRefundLargerThanTheCounterStopsAtZeroAndKeepsTheExpiry() {
        SpendCounters.add(redis, "k", WINDOW, 300L);

        assertThat(SpendCounters.add(redis, "k", WINDOW, -5_000L)).isZero();

        assertThat(redis.opsForValue().get("k")).isEqualTo("0");
        assertThat(redis.getExpire("k")).isPositive();
    }

    @Test
    void thePerUserCountersGetTheSameAtomicTtlOnThePositivePath() {
        PerUserBudgetCounters counters = new PerUserBudgetCounters(redis, org.mockito.Mockito.mock(
            org.springframework.beans.factory.ObjectProvider.class));
        redis.opsForValue().set("pu", "10");

        counters.incrementByAndGet("pu", WINDOW, 5L);

        assertThat(redis.opsForValue().get("pu")).isEqualTo("15");
        assertThat(redis.getExpire("pu")).isPositive();
    }
}
