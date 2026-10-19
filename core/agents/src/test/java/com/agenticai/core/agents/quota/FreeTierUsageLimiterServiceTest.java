package com.agenticai.core.agents.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Covers the single rolling-30-day token window (post-2026-09-26
 * simplification - the message-count and daily-token windows were removed;
 * see the service's class javadoc): {@link
 * FreeTierUsageLimiterService#checkAndReserveTokenBudget} atomically reserves
 * a conservative token estimate ({@code maxTokens + tokenReservationPromptEstimate})
 * against the monthly window before comparing against the cap - undoing the
 * reservation before throwing if the window would go over - and {@link
 * FreeTierUsageLimiterService#reconcileTokenReservation} corrects it to the
 * real total once known.
 */
class FreeTierUsageLimiterServiceTest {

    private static final String TENANT = "tenant-a";
    private static final long MONTHLY_TOKEN_LIMIT = 400_000L;
    private static final long PROMPT_ESTIMATE = 2_000L;

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private PlatformSettingsService settings;
    private FreeTierUsageLimiterService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        settings = mock(PlatformSettingsService.class);
        // No platform_setting rows configured - every getter falls back to the
        // caller-supplied default, so the effective config equals the in-code
        // defaults passed to the constructor below. Same mocking idiom as
        // PerUserBudgetServiceTest#setUp.
        when(settings.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        service = new FreeTierUsageLimiterService(
            redisTemplate, settings, MONTHLY_TOKEN_LIMIT, 2, 3,
            new SimpleMeterRegistry(), PROMPT_ESTIMATE, 0L, mock(ObjectProvider.class));
    }

    @Test
    void reservesMaxTokensPlusPromptEstimateAtomicallyWhenUnderCap() {
        // maxTokens=1000 + promptEstimate=2000 = 3000 reservation, well under the cap.
        when(valueOps.increment(anyString(), eq(3000L))).thenReturn(3000L);

        long reserved = service.checkAndReserveTokenBudget(TENANT, TenantPlan.FREE, 1000);

        assertThat(reserved).isEqualTo(3000L);
        verify(valueOps).increment(contains("monthly-tokens"), eq(3000L));
        // First write to the window - expiry set once, on the write that created the key.
        verify(redisTemplate).expire(contains("monthly-tokens"), eq(java.time.Duration.ofDays(30)));
    }

    @Test
    void undoesTheReservationAndThrowsWhenTheMonthlyCapWouldBeExceeded() {
        long reservation = 3000L;
        when(valueOps.increment(contains("monthly-tokens"), eq(reservation))).thenReturn(MONTHLY_TOKEN_LIMIT + 5000);

        assertThatThrownBy(() -> service.checkAndReserveTokenBudget(TENANT, TenantPlan.FREE, 1000))
            .isInstanceOf(TenantLimitExceededException.class)
            .hasMessageContaining("monthly token limit");

        // The reservation is undone - a rejected call must not be left stuck
        // against the cap.
        verify(valueOps).increment(contains("monthly-tokens"), eq(-reservation));
    }

    @Test
    void premiumPlanDoublesTheEffectiveMonthlyTokenLimit() {
        long reservation = 3000L;
        // Over the FREE cap (400,000) but under PREMIUM's doubled 800,000 cap.
        when(valueOps.increment(anyString(), eq(reservation))).thenReturn(500_000L);

        long reserved = service.checkAndReserveTokenBudget(TENANT, TenantPlan.PREMIUM, 1000);

        assertThat(reserved).isEqualTo(reservation);
    }

    @Test
    void unlimitedPlanTriplesTheEffectiveMonthlyTokenLimit() {
        long reservation = 3000L;
        // Over PREMIUM's 800,000 but under UNLIMITED's tripled 1,200,000 cap.
        when(valueOps.increment(anyString(), eq(reservation))).thenReturn(900_000L);

        long reserved = service.checkAndReserveTokenBudget(TENANT, TenantPlan.UNLIMITED, 1000);

        assertThat(reserved).isEqualTo(reservation);
    }

    @Test
    void failsOpenAndReservesNothingOnARedisOutage() {
        when(valueOps.increment(anyString(), anyLong())).thenReturn(null);

        long reserved = service.checkAndReserveTokenBudget(TENANT, TenantPlan.FREE, 1000);

        assertThat(reserved).isZero();
    }

    @Test
    void zeroReservationIsANoOpThatNeverTouchesRedis() {
        // A service configured with no prompt-estimate buffer and a caller
        // requesting maxTokens=0 - nothing to reserve.
        FreeTierUsageLimiterService zeroEstimateService = new FreeTierUsageLimiterService(
            redisTemplate, settings, MONTHLY_TOKEN_LIMIT, 2, 3,
            new SimpleMeterRegistry(), 0L, 0L, mock(ObjectProvider.class));

        long reserved = zeroEstimateService.checkAndReserveTokenBudget(TENANT, TenantPlan.FREE, 0);

        assertThat(reserved).isZero();
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    // ---- reconcileTokenReservation ------------------------------------------

    @Test
    void reconcileAddsTheSignedDeltaToTheWindowWhenAReservationExists() {
        // Reserved 3000, actual usage only 1200 - a -1800 delta releases the
        // over-reservation back.
        service.reconcileTokenReservation(TENANT, 3000L, 1200L);

        verify(valueOps).increment(contains("monthly-tokens"), eq(-1800L));
    }

    @Test
    void reconcileIsANoOpWhenActualMatchesReservedExactly() {
        service.reconcileTokenReservation(TENANT, 3000L, 3000L);

        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void reconcileFallsBackToPlainAdditiveRecordWhenNothingWasReserved() {
        // reservedTokens <= 0 (Redis outage at reserve time, or a caller that
        // never reserved - e.g. a self-healed retry link) - real usage must
        // still be recorded via the plain additive path, not silently dropped.
        service.reconcileTokenReservation(TENANT, 0L, 1500L);

        verify(valueOps).increment(contains("monthly-tokens"), eq(1500L));
    }

    @Test
    void reconcileWithNothingReservedAndZeroActualTokensDoesNothing() {
        service.reconcileTokenReservation(TENANT, 0L, 0L);

        verify(valueOps, never()).increment(anyString(), anyLong());
    }
}
