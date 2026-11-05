package com.agenticai.core.agents.budget;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.security.SpendControlsFailMode;
import com.agenticai.core.security.SpendControlsUnavailableException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@code app.governance.spend-controls.fail-mode}: Redis down refuses a gated call when closed, and
 * only then.
 */
class PerUserBudgetFailModeTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private PerUserBudgetService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getString(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.plan(anyString())).thenReturn(TenantPlan.FREE);
        service =
                new PerUserBudgetService(
                        redis,
                        settings,
                        mock(LlmPricingCatalog.class),
                        0L,
                        limits,
                        2,
                        3,
                        mock(ObjectProvider.class));
        when(ops.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        SpendCounterMocks.stubAllThrow(redis, new IllegalStateException("redis down"));
    }

    @Test
    void openIsTheDefaultAndLetsTheCallThrough() {
        assertThatCode(() -> service.checkBudget("t", "u", Modality.TEXT))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.checkAndReserveBudget("t", "u", Modality.TEXT, 1000L, null))
                .doesNotThrowAnyException();
    }

    @Test
    void closedRefusesTheCheckWith503NotAnNpe() {
        service.useSpendControlsFailMode(SpendControlsFailMode.closed());

        assertThatThrownBy(() -> service.checkBudget("t", "u", Modality.TEXT))
                .isInstanceOf(SpendControlsUnavailableException.class);
    }

    @Test
    void closedRefusesTheReservation() {
        service.useSpendControlsFailMode(SpendControlsFailMode.closed());

        assertThatThrownBy(
                        () -> service.checkAndReserveBudget("t", "u", Modality.TEXT, 1000L, null))
                .isInstanceOf(SpendControlsUnavailableException.class);
    }

    @Test
    void closedNeverThrowsFromAPostCallRecordOrRefund() {
        service.useSpendControlsFailMode(SpendControlsFailMode.closed());

        // The money is already spent / the reservation is being given back: nothing left to refuse.
        assertThatCode(() -> service.recordSpend("t", "u", Modality.TEXT, new BigDecimal("0.01")))
                .doesNotThrowAnyException();
    }
}
