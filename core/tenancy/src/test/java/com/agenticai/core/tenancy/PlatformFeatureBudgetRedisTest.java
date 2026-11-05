package com.agenticai.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.SpendControlsFailMode;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Smart-AI budget against a real Redis (skipped without redis-server), plus fail-mode against a
 * real connection failure. Settling or releasing a reservation after its window expired must not
 * create a negative counter.
 */
class PlatformFeatureBudgetRedisTest {

    private static final String FEATURE_KEY = "agentic:smart-ai-budget:feature:copilot";
    private static final String MASTER_KEY = "agentic:smart-ai-budget:__master__";
    private static LocalRedis local;
    private static StringRedisTemplate redis;
    private PlatformSettingsService settings;
    private PlatformFeatureBudgetService service;

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
    void setUp() {
        local.flush();
        settings = mock(PlatformSettingsService.class);
        when(settings.getString(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        service = new PlatformFeatureBudgetService(redis, settings, 0L);
    }

    @Test
    void aReservationHoldsTheEstimateWithAnExpiryAndSettlesToTheActualCost() {
        PlatformFeatureBudgetService.Reservation r =
                service.reserve("copilot", new BigDecimal("0.01"));

        assertThat(r.allowed()).isTrue();
        assertThat(redis.opsForValue().get(FEATURE_KEY)).isEqualTo("1000000");
        assertThat(redis.getExpire(FEATURE_KEY)).isPositive();

        service.settle(r, new BigDecimal("0.004"));

        assertThat(redis.opsForValue().get(FEATURE_KEY)).isEqualTo("400000");
        assertThat(redis.opsForValue().get(MASTER_KEY)).isEqualTo("400000");
    }

    @Test
    void releasingAfterTheWindowExpiredCreatesNoNegativeCounter() {
        PlatformFeatureBudgetService.Reservation r =
                service.reserve("copilot", new BigDecimal("0.01"));
        redis.delete(
                java.util.List.of(
                        FEATURE_KEY, MASTER_KEY)); // the daily window rolled over mid-call

        service.release(r);

        assertThat(redis.hasKey(FEATURE_KEY)).isFalse();
        assertThat(redis.hasKey(MASTER_KEY)).isFalse();
        // the new window starts at zero: the whole feature cap is still available
        assertThat(service.checkBudget("copilot").allowed()).isTrue();
    }

    @Test
    void aSpendRecordRepairsACounterThatLostItsExpiry() {
        service.recordSpend("copilot", new BigDecimal("0.001"));
        redis.persist(FEATURE_KEY);

        service.recordSpend("copilot", new BigDecimal("0.001"));

        assertThat(redis.getExpire(FEATURE_KEY)).isPositive();
    }

    @Test
    void failModeAgainstARealConnectionFailure() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory("127.0.0.1", 1);
        dead.afterPropertiesSet();
        PlatformFeatureBudgetService down =
                new PlatformFeatureBudgetService(new StringRedisTemplate(dead), settings, 0L);

        assertThat(down.reserve("copilot", new BigDecimal("0.01")).allowed())
                .as("open: fail open")
                .isTrue();
        assertThat(down.checkBudget("copilot").allowed()).isTrue();

        down.useSpendControlsFailMode(SpendControlsFailMode.closed());
        PlatformFeatureBudgetService.Reservation refused =
                down.reserve("copilot", new BigDecimal("0.01"));
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.reason())
                .isEqualTo(PlatformFeatureBudgetService.REASON_STORE_UNAVAILABLE);
        assertThat(down.checkBudget("copilot").reason())
                .isEqualTo(PlatformFeatureBudgetService.REASON_STORE_UNAVAILABLE);
        down.release(refused); // never throws
    }
}
