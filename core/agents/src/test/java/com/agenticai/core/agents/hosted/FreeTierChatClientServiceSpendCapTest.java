package com.agenticai.core.agents.hosted;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.quota.FreeTierUsageLimiterService;
import com.agenticai.core.agents.spendcap.SpendCapReachedException;
import com.agenticai.core.agents.spendcap.SpendCapService;
import com.agenticai.core.chat.HostedLlmChatClientFactory;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.security.ImpersonationContext;
import com.agenticai.core.security.ImpersonationReadOnlyException;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The hosted-pool gate: spend cap and read-only view-as reject BEFORE any reservation or provider
 * work.
 */
class FreeTierChatClientServiceSpendCapTest {

    private FreeTierUsageLimiterService usageLimiter;
    private PlatformHostedLlmProviderService pool;
    private SpendCapService spendCap;
    private FreeTierChatClientService service;

    @BeforeEach
    void setUp() {
        usageLimiter = mock(FreeTierUsageLimiterService.class);
        pool = mock(PlatformHostedLlmProviderService.class);
        spendCap = mock(SpendCapService.class);
        service =
                new FreeTierChatClientService(
                        usageLimiter,
                        pool,
                        mock(HostedLlmChatClientFactory.class),
                        mock(PerUserBudgetService.class),
                        mock(LlmPricingCatalog.class),
                        new HostedProviderHealth(3, 120, 180),
                        mock(PlatformSettingsService.class),
                        true,
                        10_000L);
        service.setSpendCapService(spendCap);
    }

    @AfterEach
    void tearDown() {
        ImpersonationContext.clear();
    }

    @Test
    void cappedTenantIsRejectedBeforeAnyReservation() {
        doThrow(
                        new SpendCapReachedException(
                                new BigDecimal("10.00"),
                                new BigDecimal("10.5"),
                                Instant.parse("2026-11-01T00:00:00Z")))
                .when(spendCap)
                .assertWithinCap("tenant_a");

        assertThrows(
                SpendCapReachedException.class,
                () ->
                        service.buildChainForFreeTenant(
                                "tenant_a",
                                TenantPlan.FREE,
                                512,
                                0.7,
                                1.0,
                                "user-1",
                                PerUserBudgetService.Modality.TEXT));

        verify(usageLimiter, never()).checkAndReserveTokenBudget(any(), any(), anyInt());
        verifyNoInteractions(pool);
    }

    @Test
    void viewAsRequestNeverReachesTheHostedPool() {
        ImpersonationContext.set(
                new ImpersonationContext.Session(
                        UUID.randomUUID(), "admin", null, "tenant_a", "u"));

        assertThrows(
                ImpersonationReadOnlyException.class,
                () ->
                        service.buildChainForFreeTenant(
                                "tenant_a", TenantPlan.FREE, 512, 0.7, 1.0, null, null));

        verifyNoInteractions(spendCap, pool);
        verify(usageLimiter, never()).checkAndReserveTokenBudget(any(), any(), anyInt());
    }
}
