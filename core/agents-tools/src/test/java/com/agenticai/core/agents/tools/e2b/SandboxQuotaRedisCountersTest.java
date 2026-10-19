package com.agenticai.core.agents.tools.e2b;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The Redis-backed daily run counters ({@code app.sandbox.platform-e2b.quota-store=redis}) against
 * a tiny in-memory fake of INCR / DECR / GET shared by two "replicas". Not run against a real
 * Redis.
 */
class SandboxQuotaRedisCountersTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 4);

    private final Map<String, Long> store = new HashMap<>();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void fakeRedis() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString()))
                .thenAnswer(call -> store.merge(call.<String>getArgument(0), 1L, Long::sum));
        when(ops.decrement(anyString()))
                .thenAnswer(call -> store.merge(call.<String>getArgument(0), -1L, Long::sum));
        when(ops.get(anyString()))
                .thenAnswer(
                        call -> {
                            Long value = store.get(call.<String>getArgument(0));
                            return value == null ? null : String.valueOf(value);
                        });
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void tenantCapHoldsAndARefusedAttemptLeavesTheCounterUnchanged() {
        SandboxQuotaRedisCounters counters = new SandboxQuotaRedisCounters(redis);

        assertThat(counters.consume(DAY, "t1", 2, 100))
                .isEqualTo(SandboxQuotaRedisCounters.Outcome.ALLOWED);
        assertThat(counters.consume(DAY, "t1", 2, 100))
                .isEqualTo(SandboxQuotaRedisCounters.Outcome.ALLOWED);
        assertThat(counters.consume(DAY, "t1", 2, 100))
                .isEqualTo(SandboxQuotaRedisCounters.Outcome.TENANT_LIMIT);

        assertThat(counters.used(DAY, "t1")).isEqualTo(2);
        assertThat(store.get(SandboxQuotaRedisCounters.globalKey(DAY))).isEqualTo(2L);
    }

    @Test
    void globalCeilingHoldsAcrossTenantsAndRollsBackTheTenantIncrement() {
        SandboxQuotaRedisCounters counters = new SandboxQuotaRedisCounters(redis);

        assertThat(counters.consume(DAY, "a", 10, 2))
                .isEqualTo(SandboxQuotaRedisCounters.Outcome.ALLOWED);
        assertThat(counters.consume(DAY, "b", 10, 2))
                .isEqualTo(SandboxQuotaRedisCounters.Outcome.ALLOWED);
        assertThat(counters.consume(DAY, "c", 10, 2))
                .isEqualTo(SandboxQuotaRedisCounters.Outcome.GLOBAL_LIMIT);

        assertThat(counters.used(DAY, "c")).isZero();
        assertThat(store.get(SandboxQuotaRedisCounters.globalKey(DAY))).isEqualTo(2L);
    }

    @Test
    void twoReplicasShareOneCap() {
        SandboxQuotaRedisCounters replicaA = new SandboxQuotaRedisCounters(redis);
        SandboxQuotaRedisCounters replicaB = new SandboxQuotaRedisCounters(redis);

        assertThat(replicaA.consume(DAY, "t1", 3, 100))
                .isEqualTo(SandboxQuotaRedisCounters.Outcome.ALLOWED);
        assertThat(replicaB.consume(DAY, "t1", 3, 100))
                .isEqualTo(SandboxQuotaRedisCounters.Outcome.ALLOWED);
        assertThat(replicaA.consume(DAY, "t1", 3, 100))
                .isEqualTo(SandboxQuotaRedisCounters.Outcome.ALLOWED);
        assertThat(replicaB.consume(DAY, "t1", 3, 100))
                .isEqualTo(SandboxQuotaRedisCounters.Outcome.TENANT_LIMIT);
        assertThat(replicaA.consume(DAY, "t1", 3, 100))
                .isEqualTo(SandboxQuotaRedisCounters.Outcome.TENANT_LIMIT);
    }

    @Test
    void refundGivesOneRunBackAndNeverGoesNegative() {
        SandboxQuotaRedisCounters counters = new SandboxQuotaRedisCounters(redis);
        counters.consume(DAY, "t1", 5, 100);

        assertThat(counters.refund(DAY, "t1")).isTrue();
        assertThat(counters.used(DAY, "t1")).isZero();

        assertThat(counters.refund(DAY, "t1")).isTrue(); // nothing left to give back
        assertThat(counters.used(DAY, "t1")).isZero();
        assertThat(store.get(SandboxQuotaRedisCounters.globalKey(DAY))).isZero();
    }

    @Test
    void aRedisFailureIsReportedAsUnavailableSoTheCallerFallsBackToItsLocalCounters() {
        when(ops.increment(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(ops.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        SandboxQuotaRedisCounters counters = new SandboxQuotaRedisCounters(redis);

        assertThat(counters.consume(DAY, "t1", 5, 100))
                .isEqualTo(SandboxQuotaRedisCounters.Outcome.UNAVAILABLE);
        assertThat(counters.used(DAY, "t1")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void quotaUsesTheSharedCountersWhenWiredSoTwoInstancesSeeOneCap() {
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.storedPlan("t1")).thenReturn(TenantPlan.FREE);
        ObjectProvider<TenantLimits> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(limits);
        PlatformSandboxRunQuota replicaA =
                new PlatformSandboxRunQuota(provider, 2, 5, 30, () -> DAY);
        PlatformSandboxRunQuota replicaB =
                new PlatformSandboxRunQuota(provider, 2, 5, 30, () -> DAY);
        replicaA.useSharedCounters(new SandboxQuotaRedisCounters(redis));
        replicaB.useSharedCounters(new SandboxQuotaRedisCounters(redis));
        TenantContext.set(TenantContext.of("t1", "user", Set.of()));

        assertThat(replicaA.tryConsume()).isNull();
        assertThat(replicaB.tryConsume()).isNull();
        assertThat(replicaA.tryConsume()).contains("Daily analysis limit reached");
        assertThat(replicaB.tryConsume()).contains("Daily analysis limit reached");
        assertThat(replicaB.usedToday()).isEqualTo(2);

        replicaA.refund();
        assertThat(replicaB.usedToday()).isEqualTo(1);
        assertThat(replicaB.tryConsume()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void quotaFallsBackToItsLocalCountersWhenRedisIsDown() {
        when(ops.increment(anyString())).thenThrow(new IllegalStateException("redis down"));
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.storedPlan("t1")).thenReturn(TenantPlan.FREE);
        ObjectProvider<TenantLimits> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(limits);
        PlatformSandboxRunQuota quota = new PlatformSandboxRunQuota(provider, 1, 5, 30, () -> DAY);
        quota.useSharedCounters(new SandboxQuotaRedisCounters(redis));
        TenantContext.set(TenantContext.of("t1", "user", Set.of()));

        assertThat(quota.tryConsume()).isNull();
        assertThat(quota.tryConsume()).contains("Daily analysis limit reached");
    }

    @Test
    void aHangingRedisIsBoundedAndReportedAsUnavailable() {
        CountDownLatch neverReleased = new CountDownLatch(1);
        when(ops.increment(anyString()))
                .thenAnswer(
                        call -> {
                            neverReleased
                                    .await(); // black-holed connection: the real Lettuce call would
                            // wait 60 s
                            return 1L;
                        });
        SandboxQuotaRedisCounters counters = new SandboxQuotaRedisCounters(redis);

        long started = System.nanoTime();
        SandboxQuotaRedisCounters.Outcome outcome = counters.consume(DAY, "t1", 5, 100);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(outcome).isEqualTo(SandboxQuotaRedisCounters.Outcome.UNAVAILABLE);
        assertThat(elapsedMillis).isLessThan(5000L);
        // Further calls while Redis is still hanging answer immediately (breaker open) instead of
        // paying the wait again.
        long again = System.nanoTime();
        assertThat(counters.consume(DAY, "t1", 5, 100))
                .isEqualTo(SandboxQuotaRedisCounters.Outcome.UNAVAILABLE);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - again)).isLessThan(200L);
    }

    @Test
    void aFailureAfterTheTenantIncrementUndoesItSoTheRunIsNotCountedTwice() {
        doThrow(new IllegalStateException("redis down"))
                .when(ops)
                .increment(SandboxQuotaRedisCounters.globalKey(DAY));
        SandboxQuotaRedisCounters counters = new SandboxQuotaRedisCounters(redis);

        assertThat(counters.consume(DAY, "t1", 5, 100))
                .isEqualTo(SandboxQuotaRedisCounters.Outcome.UNAVAILABLE);

        assertThat(store.get(SandboxQuotaRedisCounters.tenantKey(DAY, "t1"))).isZero();
    }
}
