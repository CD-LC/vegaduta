package com.agenticai.core.chat.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmSpendReader;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * {@link LlmGatewayBudgetService#monthlyCapUsd} is what the ROI page's budget bar reads, so it must
 * report the same cap the check enforces per plan.
 */
class LlmGatewayBudgetServiceMonthlyCapTest {

    private final TenantLimits tenantLimits = mock(TenantLimits.class);
    private final LlmGatewayBudgetService service =
            new LlmGatewayBudgetService(
                    mock(LlmSpendReader.class),
                    tenantLimits,
                    "200",
                    "0",
                    new SimpleMeterRegistry());

    @Test
    void reportsThePlanCapAndNoneForUnlimited() {
        when(tenantLimits.plan("premium")).thenReturn(TenantPlan.PREMIUM);
        when(tenantLimits.plan("free")).thenReturn(TenantPlan.FREE);
        when(tenantLimits.plan("unlimited")).thenReturn(TenantPlan.UNLIMITED);

        assertEquals(new BigDecimal("200"), service.monthlyCapUsd("premium").orElseThrow());
        assertEquals(new BigDecimal("0"), service.monthlyCapUsd("free").orElseThrow());
        assertTrue(service.monthlyCapUsd("unlimited").isEmpty());
    }
}
