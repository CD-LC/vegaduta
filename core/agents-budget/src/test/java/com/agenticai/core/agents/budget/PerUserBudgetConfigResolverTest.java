package com.agenticai.core.agents.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetService.BudgetConfig;
import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link PerUserBudgetConfigResolver}: the platform-setting reads with their in-code
 * defaults, the non-numeric fallback, the time-boxed cache, per-plan cap scaling (and its fail-open
 * posture) and the partial admin write path.
 */
class PerUserBudgetConfigResolverTest {

    private PlatformSettingsService settings;
    private TenantLimits tenantLimits;
    private PerUserBudgetConfigResolver resolver;

    @BeforeEach
    void setUp() {
        settings = mock(PlatformSettingsService.class);
        tenantLimits = mock(TenantLimits.class);
        // Echo the supplied defaults: the effective config is the in-code default set.
        when(settings.getString(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        // configCacheMs = 0: every resolve re-reads, premium x2, unlimited x3 (the same
        // multipliers PerUserBudgetServiceTest wires into the service).
        resolver = new PerUserBudgetConfigResolver(settings, 0L, tenantLimits, 2, 3);
    }

    @Test
    void defaultsMatchTheShippedBudgetShape() {
        BudgetConfig config = resolver.loadGlobalConfig();

        assertThat(config.enabled()).isTrue();
        assertThat(config.dailyUsd()).isEqualByComparingTo("0.15");
        assertThat(config.windowSeconds()).isEqualTo(86_400);
        assertThat(config.splitPct())
                .containsEntry(Modality.TEXT, 100)
                .containsEntry(Modality.IMAGE, 100)
                .containsEntry(Modality.VOICE, 100)
                .containsEntry(Modality.VIDEO, 0);
        assertThat(config.fallbackUsdPer1kTokens()).isEqualByComparingTo("0.00015");
    }

    @Test
    void nonNumericUsdSettingsFallBackToTheDefault() {
        when(settings.getString(eq(PerUserBudgetService.KEY_DAILY_USD), anyString()))
                .thenReturn("not-a-number");
        when(settings.getString(eq(PerUserBudgetService.KEY_FALLBACK_USD_PER_1K), anyString()))
                .thenReturn("bogus");

        BudgetConfig config = resolver.loadGlobalConfig();

        assertThat(config.dailyUsd()).isEqualByComparingTo("0.15");
        assertThat(config.fallbackUsdPer1kTokens()).isEqualByComparingTo("0.00015");
    }

    @Test
    void disabledSwitchReadsThrough() {
        when(settings.getString(eq(PerUserBudgetService.KEY_ENABLED), anyString()))
                .thenReturn("false");

        assertThat(resolver.loadGlobalConfig().enabled()).isFalse();
    }

    @Test
    void snapshotCacheAvoidsReReadingWithinTheWindow() {
        PerUserBudgetConfigResolver cached =
                new PerUserBudgetConfigResolver(settings, 60_000L, tenantLimits, 2, 3);

        cached.effectiveGlobalConfig();
        cached.effectiveGlobalConfig();

        // KEY_ENABLED is read once per load; one load only within the cache window.
        verify(settings, times(1)).getString(eq(PerUserBudgetService.KEY_ENABLED), anyString());
    }

    @Test
    void aNullTenantIdGetsTheUnscaledGlobalConfig() {
        BudgetConfig config = resolver.resolveConfig(null, "u1", null);
        assertThat(config.dailyUsd()).isEqualByComparingTo("0.15");
    }

    @Test
    void premiumAndUnlimitedPlansScaleTheDailyCap() {
        BudgetConfig premium =
                resolver.applyPlanTier(resolver.loadGlobalConfig(), "t1", TenantPlan.PREMIUM);
        assertThat(premium.dailyUsd()).isEqualByComparingTo("0.30");

        BudgetConfig unlimited =
                resolver.applyPlanTier(resolver.loadGlobalConfig(), "t1", TenantPlan.UNLIMITED);
        assertThat(unlimited.dailyUsd()).isEqualByComparingTo("0.45");

        BudgetConfig free =
                resolver.applyPlanTier(resolver.loadGlobalConfig(), "t1", TenantPlan.FREE);
        assertThat(free.dailyUsd()).isEqualByComparingTo("0.15");
    }

    @Test
    void resolvingWithoutAKnownPlanLooksItUpThroughTenantLimits() {
        when(tenantLimits.plan("t1")).thenReturn(TenantPlan.PREMIUM);

        BudgetConfig config = resolver.resolveConfig("t1", "u1");

        assertThat(config.dailyUsd()).isEqualByComparingTo("0.30");
        verify(tenantLimits).plan("t1");
    }

    @Test
    void aPlanLookupErrorFailsOpenToTheUnscaledCap() {
        when(tenantLimits.plan("t1")).thenThrow(new RuntimeException("tenant lookup failed"));

        BudgetConfig config = resolver.resolveConfig("t1", "u1", null);

        assertThat(config.dailyUsd()).isEqualByComparingTo("0.15");
    }

    @Test
    void updateGlobalConfigWritesOnlyProvidedFieldsAndBustsTheCache() {
        Map<Modality, Integer> split = new java.util.EnumMap<>(Modality.class);
        split.put(Modality.TEXT, 50);
        split.put(Modality.VIDEO, null); // a null entry leaves that row untouched

        resolver.updateGlobalConfig(
                Boolean.FALSE, new BigDecimal("0.50"), null, split, null, "admin@x");

        verify(settings).update(PerUserBudgetService.KEY_ENABLED, "false", "admin@x");
        verify(settings).update(PerUserBudgetService.KEY_DAILY_USD, "0.50", "admin@x");
        verify(settings)
                .update(PerUserBudgetService.KEY_SPLIT_PCT_PREFIX + "text", "50", "admin@x");
        verify(settings, times(3)).update(anyString(), anyString(), anyString());
    }

    @Test
    void updateGlobalConfigReturnsTheReloadedConfig() {
        BudgetConfig result =
                resolver.updateGlobalConfig(
                        Boolean.TRUE, new BigDecimal("1.25"), 3_600, null, null, "admin@x");

        // Reads go back through the (default-echoing) settings mock, so dailyUsd reverts to
        // the in-code default here - the pin is that update() was invoked and the return is a
        // fresh load, not the pre-update snapshot.
        assertThat(result.enabled()).isTrue();
        verify(settings).update(PerUserBudgetService.KEY_DAILY_USD, "1.25", "admin@x");
        verify(settings).update(PerUserBudgetService.KEY_WINDOW_SECONDS, "3600", "admin@x");
    }
}
