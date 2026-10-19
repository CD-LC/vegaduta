package com.agenticai.core.agents.hosted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.quota.FreeTierUsageLimiterService;
import com.agenticai.core.chat.HostedLlmChatClientFactory;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * V484 "free means free": a pool made only of zero-cost rows reserves and meters nothing; a mixed
 * pool still reserves, and its zero-cost rows sort first in the failover order.
 */
class FreeTierChatClientServiceZeroCostTest {

    private static final String TENANT_ID = "tenant_free";

    private FreeTierUsageLimiterService usageLimiterService;
    private PlatformHostedLlmProviderService pool;
    private PerUserBudgetService perUserBudgetService;
    private HostedLlmChatClientFactory factory;
    private final List<Object> attachedAdvisors = new ArrayList<>();
    private PlatformHostedLlmProvider localRow;
    private PlatformHostedLlmProvider paidRow;

    @BeforeEach
    void setUp() {
        usageLimiterService = mock(FreeTierUsageLimiterService.class);
        pool = mock(PlatformHostedLlmProviderService.class);
        perUserBudgetService = mock(PerUserBudgetService.class);
        factory = mock(HostedLlmChatClientFactory.class);

        localRow = row(1L, "Local model (Ollama)", "qwen3.5:4b", 10, true);
        paidRow = row(2L, "Gemini", "gemini-2.0-flash", 1, false);

        when(usageLimiterService.checkAndReserveTokenBudget(eq(TENANT_ID), any(), anyInt())).thenReturn(2512L);
        when(perUserBudgetService.checkAndReserveBudget(eq(TENANT_ID), anyString(), any(), anyLong(), any()))
                .thenReturn(900L);

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.defaultAdvisors(any(Advisor[].class))).thenAnswer(invocation -> {
            for (Object arg : invocation.getArguments()) {
                if (arg instanceof Object[] array) {
                    attachedAdvisors.addAll(List.of(array));
                } else {
                    attachedAdvisors.add(arg);
                }
            }
            return builder;
        });
        when(builder.build()).thenReturn(mock(ChatClient.class));
        ChatClient rawClient = mock(ChatClient.class);
        when(rawClient.mutate()).thenReturn(builder);
        when(factory.create(any(PlatformHostedLlmProvider.class), anyString(), anyInt(), anyDouble(), anyDouble(),
                any(), any(com.agenticai.core.chat.SamplingOptions.class)))
                .thenReturn(rawClient);
    }

    private FreeTierChatClientService service(boolean meterZeroCostRows) {
        return new FreeTierChatClientService(
                usageLimiterService, pool, factory, perUserBudgetService, new LlmPricingCatalog(),
                new HostedProviderHealth(3, 120, 180), mock(PlatformSettingsService.class), true, 10_000L,
                meterZeroCostRows);
    }

    @Test
    void allZeroCostPoolReservesNothingAndAttachesNoTokenAdvisor() {
        when(pool.allEnabledRowsZeroCost()).thenReturn(true);
        when(pool.resolveEnabledChain()).thenReturn(List.of(new HostedProviderSelection(localRow, "ollama")));

        List<HostedChainLink> chain = service(false)
                .buildChainForFreeTenant(TENANT_ID, TenantPlan.FREE, 512, 0.7, 1.0, "user-1", Modality.TEXT);

        assertEquals(1, chain.size());
        assertEquals(0L, chain.get(0).reservedTokens());
        assertEquals(0L, chain.get(0).reservedMicroCents());
        verify(usageLimiterService, never()).checkAndReserveTokenBudget(any(), any(), anyInt());
        verify(perUserBudgetService, never()).checkAndReserveBudget(any(), any(), any(), anyLong(), any());
        assertTrue(attachedAdvisors.stream().noneMatch(a -> a instanceof FreeTierTokenTrackingAdvisor),
                "no token advisor on an unmetered turn: " + attachedAdvisors);
    }

    @Test
    void meterZeroCostRowsSwitchRestoresTheTokenQuota() {
        when(pool.allEnabledRowsZeroCost()).thenReturn(true);
        when(pool.resolveEnabledChain()).thenReturn(List.of(new HostedProviderSelection(localRow, "ollama")));

        List<HostedChainLink> chain = service(true)
                .buildChainForFreeTenant(TENANT_ID, TenantPlan.FREE, 512, 0.7, 1.0, null, null);

        assertEquals(2512L, chain.get(0).reservedTokens());
        verify(usageLimiterService).checkAndReserveTokenBudget(TENANT_ID, TenantPlan.FREE, 512);
        assertTrue(attachedAdvisors.stream().anyMatch(a -> a instanceof FreeTierTokenTrackingAdvisor));
    }

    @Test
    void mixedPoolStillReservesAndPutsTheZeroCostRowFirst() {
        when(pool.allEnabledRowsZeroCost()).thenReturn(false);
        when(pool.resolveEnabledChain()).thenReturn(List.of(
                new HostedProviderSelection(paidRow, "key"),
                new HostedProviderSelection(localRow, "ollama")));

        List<HostedChainLink> chain = service(false)
                .buildChainForFreeTenant(TENANT_ID, TenantPlan.FREE, 512, 0.7, 1.0, "user-1", Modality.TEXT);

        verify(usageLimiterService).checkAndReserveTokenBudget(TENANT_ID, TenantPlan.FREE, 512);
        verify(perUserBudgetService).checkAndReserveBudget(eq(TENANT_ID), eq("user-1"), eq(Modality.TEXT), eq(2512L), any());
        assertEquals(2, chain.size());
        assertEquals("Local model (Ollama)", chain.get(0).label(), "zero-cost first despite priority 10 vs 1");
        assertEquals("Gemini", chain.get(1).label());
    }

    private static PlatformHostedLlmProvider row(long id, String label, String model, int priority, boolean zeroCost) {
        PlatformHostedLlmProvider provider = mock(PlatformHostedLlmProvider.class);
        when(provider.getId()).thenReturn(id);
        when(provider.getLabel()).thenReturn(label);
        when(provider.getModel()).thenReturn(model);
        when(provider.getPriority()).thenReturn(priority);
        when(provider.isZeroCost()).thenReturn(zeroCost);
        when(provider.getBaseUrl()).thenReturn("https://example.invalid/" + id);
        return provider;
    }
}
