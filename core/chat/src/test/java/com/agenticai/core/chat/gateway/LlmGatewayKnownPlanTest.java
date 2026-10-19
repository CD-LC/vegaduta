package com.agenticai.core.chat.gateway;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.ChatClientRegistry;
import com.agenticai.core.chat.LlmSpendReader;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.TenantAiModelPreferenceService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The per-turn plan count at the gateway. Before, every {@code resolveClient} read the tenant's
 * plan twice (once for the monthly budget, once for the rate limit), and a turn resolves more than
 * one client. A caller that already resolved the plan hands it down and the gateway reads it zero
 * times; a caller that did not still gets exactly the old behaviour.
 */
class LlmGatewayKnownPlanTest {

    private final GatewayCallSpec spec =
            new GatewayCallSpec(ProviderName.ANTHROPIC, "sk-1", "claude", 1024, 0.7, 1.0);

    @Test
    void theGatewayHandsTheKnownPlanToBothGovernanceChecks() {
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        LlmGatewayBudgetService budget = mock(LlmGatewayBudgetService.class);
        LlmGatewayRateLimiterService rate = mock(LlmGatewayRateLimiterService.class);
        TenantAiModelPreferenceService prefs = mock(TenantAiModelPreferenceService.class);
        when(registry.createClient(
                        any(),
                        anyString(),
                        anyString(),
                        anyInt(),
                        anyDouble(),
                        anyDouble(),
                        any(Map.class)))
                .thenAnswer(i -> mock(ChatClient.class));
        LlmGatewayService service = new LlmGatewayService(registry, budget, rate, prefs, List.of());

        service.resolveClient("acme", spec, TenantPlan.PREMIUM);
        service.resolveClient("acme", spec, TenantPlan.PREMIUM);

        verify(budget, times(2)).assertWithinBudget("acme", TenantPlan.PREMIUM);
        verify(rate, times(2)).assertWithinRateLimit("acme", TenantPlan.PREMIUM);
        verify(budget, never()).assertWithinBudget("acme");
        verify(rate, never()).assertWithinRateLimit("acme");
    }

    @Test
    void withoutAPlanTheGatewayStillAsksTheOldWay() {
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        LlmGatewayBudgetService budget = mock(LlmGatewayBudgetService.class);
        LlmGatewayRateLimiterService rate = mock(LlmGatewayRateLimiterService.class);
        LlmGatewayService service =
                new LlmGatewayService(
                        registry,
                        budget,
                        rate,
                        mock(TenantAiModelPreferenceService.class),
                        List.of());
        when(registry.createClient(
                        any(),
                        anyString(),
                        anyString(),
                        anyInt(),
                        anyDouble(),
                        anyDouble(),
                        any(Map.class)))
                .thenAnswer(i -> mock(ChatClient.class));

        service.resolveClient("acme", spec);

        verify(budget).assertWithinBudget("acme");
        verify(rate).assertWithinRateLimit("acme");
    }

    @Test
    void budgetCheckWithAKnownPlanReadsNoPlan() {
        LlmSpendReader repository = mock(LlmSpendReader.class);
        TenantLimits limits = mock(TenantLimits.class);
        LlmGatewayBudgetService budget =
                new LlmGatewayBudgetService(
                        repository, limits, "200", "0", new SimpleMeterRegistry());
        when(repository.costSince(any())).thenReturn(BigDecimal.ZERO);

        budget.assertWithinBudget("acme", TenantPlan.PREMIUM);

        verifyNoInteractions(limits);
    }

    @Test
    void budgetCheckWithAKnownPlanStillEnforcesThatPlansCap() {
        LlmSpendReader repository = mock(LlmSpendReader.class);
        TenantLimits limits = mock(TenantLimits.class);
        LlmGatewayBudgetService budget =
                new LlmGatewayBudgetService(
                        repository, limits, "200", "0", new SimpleMeterRegistry());
        when(repository.costSince(any())).thenReturn(new BigDecimal("250"));

        assertThrows(
                LlmGatewayLimitExceededException.class,
                () -> budget.assertWithinBudget("acme", TenantPlan.PREMIUM));
        // UNLIMITED has no cap, whatever was spent.
        budget.assertWithinBudget("acme", TenantPlan.UNLIMITED);
        verifyNoInteractions(limits);
    }

    @Test
    void budgetCheckWithoutAPlanReadsItOnce() {
        LlmSpendReader repository = mock(LlmSpendReader.class);
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.plan("acme")).thenReturn(TenantPlan.UNLIMITED);
        LlmGatewayBudgetService budget =
                new LlmGatewayBudgetService(
                        repository, limits, "200", "0", new SimpleMeterRegistry());

        budget.assertWithinBudget("acme");

        verify(limits, times(1)).plan("acme");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rateLimitWithAKnownPlanReadsNoPlan() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(1L);
        TenantLimits limits = mock(TenantLimits.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        LlmGatewayRateLimiterService limiter =
                new LlmGatewayRateLimiterService(
                        redis, limits, 10, 10, 10, meters, new GovernanceDegradationSignal(meters));

        limiter.assertWithinRateLimit("acme", TenantPlan.PREMIUM);

        verifyNoInteractions(limits);
    }
}
