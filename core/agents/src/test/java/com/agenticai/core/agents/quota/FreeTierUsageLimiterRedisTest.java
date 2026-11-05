package com.agenticai.core.agents.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.SpendControlsFailMode;
import com.agenticai.core.security.SpendControlsUnavailableException;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The hosted-pool token quota against a real Redis (skipped without redis-server): a reservation
 * rolled back or reconciled after its month window expired must not leave a negative counter, which
 * is free quota for the next window.
 */
class FreeTierUsageLimiterRedisTest {

    private static final String KEY = "free_tier:monthly-tokens:t1";
    private static LocalRedis local;
    private static StringRedisTemplate redis;
    private FreeTierUsageLimiterService limiter;

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
    @SuppressWarnings("unchecked")
    void setUp() {
        local.flush();
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
        limiter =
                new FreeTierUsageLimiterService(
                        redis,
                        settings,
                        10_000L,
                        2,
                        3,
                        new SimpleMeterRegistry(),
                        2_000L,
                        0L,
                        mock(ObjectProvider.class));
    }

    private String key() {
        return redis.keys("*monthly-tokens*").stream().findFirst().orElse(null);
    }

    @Test
    void aReservationGetsTheMonthlyExpiryInTheSameStepAndReconcilesToTheRealTotal() {
        long reserved = limiter.checkAndReserveTokenBudget("t1", TenantPlan.FREE, 1000);
        assertThat(reserved).isEqualTo(3000L);
        assertThat(redis.getExpire(key())).isPositive();

        limiter.reconcileTokenReservation("t1", reserved, 1200L);

        assertThat(redis.opsForValue().get(key())).isEqualTo("1200");
    }

    @Test
    void reconcilingAfterTheWindowExpiredCreatesNoNegativeCounter() {
        long reserved = limiter.checkAndReserveTokenBudget("t1", TenantPlan.FREE, 1000);
        redis.delete(key()); // the month rolled over while the turn was still streaming

        limiter.reconcileTokenReservation(
                "t1", reserved, 0L); // failed turn: release the whole reservation

        assertThat(redis.keys("*monthly-tokens*"))
                .as("a refund must not create a negative counter")
                .isEmpty();
        // and the next window starts from zero, not below it
        limiter.recordTokenUsage("t1", 500L);
        assertThat(redis.opsForValue().get(key())).isEqualTo("500");
    }

    @Test
    void aRejectedReservationIsUndoneToExactlyWhatWasThere() {
        limiter.recordTokenUsage("t1", 9_000L);

        assertThatThrownBy(() -> limiter.checkAndReserveTokenBudget("t1", TenantPlan.FREE, 1000))
                .isInstanceOf(TenantLimitExceededException.class);

        assertThat(redis.opsForValue().get(key())).isEqualTo("9000");
        assertThat(redis.getExpire(key())).isPositive();
    }

    @Test
    void aUsageRecordRepairsACounterThatLostItsExpiry() {
        limiter.recordTokenUsage("t1", 100L);
        redis.persist(key()); // what a crash between INCRBY and EXPIRE used to leave

        limiter.recordTokenUsage("t1", 100L);

        assertThat(redis.getExpire(key())).isPositive();
    }

    @Test
    void closedModeOverARealOutageRefusesWith503() {
        StringRedisTemplate broken =
                new StringRedisTemplate(
                        new org.springframework.data.redis.connection.lettuce
                                .LettuceConnectionFactory("127.0.0.1", 1));
        ((org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory)
                        broken.getConnectionFactory())
                .afterPropertiesSet();
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
        FreeTierUsageLimiterService down =
                new FreeTierUsageLimiterService(
                        broken,
                        settings,
                        10_000L,
                        2,
                        3,
                        new SimpleMeterRegistry(),
                        2_000L,
                        0L,
                        mock(ObjectProvider.class));

        assertThat(down.checkAndReserveTokenBudget("t1", TenantPlan.FREE, 1000))
                .as("open: fail open")
                .isZero();

        down.useSpendControlsFailMode(SpendControlsFailMode.closed());
        assertThatThrownBy(() -> down.checkAndReserveTokenBudget("t1", TenantPlan.FREE, 1000))
                .isInstanceOf(SpendControlsUnavailableException.class);
        // a release after the fact must never throw, even closed
        down.reconcileTokenReservation("t1", 3000L, 0L);
    }
}
