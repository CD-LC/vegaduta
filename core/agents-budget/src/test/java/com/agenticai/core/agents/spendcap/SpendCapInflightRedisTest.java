package com.agenticai.core.agents.spendcap;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.budget.LocalRedis;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The in-flight reservation scripts of the spend cap against a real Redis (skipped without
 * redis-server).
 */
class SpendCapInflightRedisTest {

    private static LocalRedis local;
    private static StringRedisTemplate redis;
    private static final List<String> KEY = List.of("spendcap:inflight:t");

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
    void reservationsAccumulateAndTheKeyExpires() {
        assertThat(redis.execute(SpendCapService.RESERVE_INFLIGHT, KEY, "1.50", "120000"))
                .isEqualTo("1.5");
        assertThat(redis.execute(SpendCapService.RESERVE_INFLIGHT, KEY, "1.50", "120000"))
                .isEqualTo("3");

        assertThat(redis.getExpire(KEY.get(0))).isBetween(1L, 120L);
    }

    @Test
    void aReleaseTakesItsAmountBackAndNeverGoesBelowZero() {
        redis.execute(SpendCapService.RESERVE_INFLIGHT, KEY, "2.00", "120000");

        redis.execute(SpendCapService.RELEASE_INFLIGHT, KEY, "-0.50");
        assertThat(Double.parseDouble(redis.opsForValue().get(KEY.get(0)))).isEqualTo(1.5);

        redis.execute(SpendCapService.RELEASE_INFLIGHT, KEY, "-9.00");
        assertThat(Double.parseDouble(redis.opsForValue().get(KEY.get(0)))).isZero();
        assertThat(redis.getExpire(KEY.get(0))).as("the clamp keeps the expiry").isPositive();
    }

    @Test
    void aReleaseWithNothingInFlightCreatesNothing() {
        redis.execute(SpendCapService.RELEASE_INFLIGHT, KEY, "-1.00");

        assertThat(redis.hasKey(KEY.get(0))).isFalse();
    }
}
