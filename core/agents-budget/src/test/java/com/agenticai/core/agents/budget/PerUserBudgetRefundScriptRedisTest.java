package com.agenticai.core.agents.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The refund script run against a real Redis: a mocked template cannot tell whether a take-back creates a negative
 * counter in an expired window or drives a live one below zero, which is where a user gets budget they never had.
 * Skipped when no {@code redis-server} binary is on the PATH.
 */
class PerUserBudgetRefundScriptRedisTest {

    private static Process server;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private PerUserBudgetCounters counters;

    @BeforeAll
    static void startRedis() throws Exception {
        assumeTrue(onPath("redis-server"), "redis-server is not installed");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = new ProcessBuilder("redis-server", "--port", Integer.toString(port), "--save", "", "--appendonly", "no",
            "--bind", "127.0.0.1").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        for (int i = 0; i < 50; i++) {
            try {
                redis.getConnectionFactory().getConnection().ping();
                return;
            } catch (RuntimeException notUpYet) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("redis-server did not start");
    }

    @AfterAll
    static void stopRedis() throws InterruptedException {
        if (factory != null) {
            factory.destroy();
        }
        if (server != null) {
            server.destroy();
            server.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        counters = new PerUserBudgetCounters(redis, mock());
    }

    @SuppressWarnings("unchecked")
    private static <T> T mock() {
        return (T) org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    }

    private static boolean onPath(String binary) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (new java.io.File(dir, binary).canExecute()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aRefundTakesItsAmountBackOutOfALiveWindow() {
        counters.incrementByAndGet("k", Duration.ofHours(1), 1_000L);

        Long after = counters.incrementByAndGet("k", Duration.ofHours(1), -400L);

        assertThat(after).isEqualTo(600L);
        assertThat(redis.opsForValue().get("k")).isEqualTo("600");
    }

    @Test
    void aRefundAgainstAnExpiredWindowCreatesNothing() {
        // The window's key is gone (its TTL ran out while the turn was still streaming).
        Long after = counters.incrementByAndGet("gone", Duration.ofHours(1), -5_000L);

        assertThat(after).isZero();
        assertThat(redis.hasKey("gone")).as("a refund must not create a negative counter").isFalse();
    }

    @Test
    void aRefundLargerThanWhatIsLeftStopsAtZeroAndKeepsTheWindowsExpiry() {
        counters.incrementByAndGet("k", Duration.ofHours(1), 300L);

        Long after = counters.incrementByAndGet("k", Duration.ofHours(1), -5_000L);

        assertThat(after).isZero();
        assertThat(redis.opsForValue().get("k")).isEqualTo("0");
        assertThat(redis.getExpire("k")).as("the clamp must not drop the window's expiry").isPositive();
    }

    @Test
    void aRefundThenARealSpendNeverStartsBelowZero() {
        counters.incrementByAndGet("k", Duration.ofHours(1), -9_000L);

        counters.incrementByAndGet("k", Duration.ofHours(1), 700L);

        assertThat(redis.opsForValue().get("k")).isEqualTo("700");
        assertThat(redis.getExpire("k")).isPositive();
    }

    @Test
    void aSpendStillStartsTheWindowWithItsExpiry() {
        Long after = counters.incrementByAndGet("fresh", Duration.ofMinutes(10), 123L);

        assertThat(after).isEqualTo(123L);
        assertThat(redis.getExpire("fresh")).isBetween(1L, 600L);
        assertThat(List.of(after)).hasSize(1);
    }
}
