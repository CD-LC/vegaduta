package com.agenticai.core.agents.budget;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Covers {@link PerUserBudgetService}'s P1 Definition of Done: the per-user dollar gate blocks
 * at/over cap and allows under it, per-modality caps are independent (video excluded by its 0%
 * default), spend is recorded in micro-cents with a one-shot window expiry, any Redis error fails
 * OPEN (never blocks a turn), and the master switch disables metering. {@code configCacheMs=0} so
 * every call re-reads the (mocked) settings fresh.
 */
abstract class PerUserBudgetServiceTestBase {

    StringRedisTemplate redisTemplate;
    ValueOperations<String, String> valueOps;
    PlatformSettingsService settings;
    LlmPricingCatalog pricingCatalog;
    TenantLimits tenantLimits;
    PerUserBudgetService service;

    static final String TENANT = "tenant-a";
    static final String USER = "user-1";
    // daily-usd 0.15, text 100% => 0.15 * 1e8 micro-cents.
    static final long TEXT_CAP_MICRO_CENTS = 15_000_000L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        settings = mock(PlatformSettingsService.class);
        pricingCatalog = mock(LlmPricingCatalog.class);
        tenantLimits = mock(TenantLimits.class);
        // FREE by default (multiplier 1) so every pre-existing assertion below
        // (all computed against the unscaled global default) keeps holding.
        when(tenantLimits.plan(anyString())).thenReturn(TenantPlan.FREE);
        // Return each getter's own supplied default -> the effective config is the in-code
        // defaults.
        when(settings.getString(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        service =
                new PerUserBudgetService(
                        redisTemplate,
                        settings,
                        pricingCatalog,
                        0L,
                        tenantLimits,
                        2,
                        3,
                        mock(ObjectProvider.class));
    }

    /**
     * A take-back goes through the atomic refund script (never below zero, never into an expired
     * window).
     */
    @SuppressWarnings("unchecked")
    void verifyRefunded(long microCents) {
        verify(redisTemplate)
                .execute(
                        eq(com.agenticai.core.security.SpendCounters.REFUND_IF_LIVE),
                        org.mockito.ArgumentMatchers.anyList(),
                        eq(Long.toString(-microCents)));
    }
}
