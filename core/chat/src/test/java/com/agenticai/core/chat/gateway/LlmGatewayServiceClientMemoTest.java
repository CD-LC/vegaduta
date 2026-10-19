package com.agenticai.core.chat.gateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.ChatClientRegistry;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.tenancy.TenantAiModelPreferenceService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/** The memo may skip client construction only; budget and rate-limit gates run on every call either way. */
class LlmGatewayServiceClientMemoTest {

    private final ChatClientRegistry registry = mock(ChatClientRegistry.class);
    private final LlmGatewayBudgetService budget = mock(LlmGatewayBudgetService.class);
    private final LlmGatewayRateLimiterService rate = mock(LlmGatewayRateLimiterService.class);
    private final TenantAiModelPreferenceService prefs = mock(TenantAiModelPreferenceService.class);
    private final LlmGatewayService service = new LlmGatewayService(registry, budget, rate, prefs, List.of());
    private final GatewayCallSpec spec =
        new GatewayCallSpec(ProviderName.ANTHROPIC, "sk-1", "claude", 1024, 0.7, 1.0);

    private void stubRegistry() {
        when(registry.createClient(any(), anyString(), anyString(), anyInt(), anyDouble(), anyDouble(), any(Map.class)))
            .thenAnswer(i -> mock(ChatClient.class));
    }

    @Test
    void withoutMemoEveryCallBuildsAClient() {
        stubRegistry();
        service.resolveClient("acme", spec);
        service.resolveClient("acme", spec);
        verify(registry, times(2)).createClient(
            eq(ProviderName.ANTHROPIC), eq("sk-1"), eq("claude"), eq(1024), eq(0.7), eq(1.0), any(Map.class));
        verify(budget, times(2)).assertWithinBudget("acme");
        verify(rate, times(2)).assertWithinRateLimit("acme");
    }

    @Test
    void withMemoClientIsBuiltOnceButGatesStillRunEveryCall() {
        stubRegistry();
        service.setClientMemo(new TtlGatewayClientMemo(Duration.ofSeconds(30), 10, Set.of(ProviderName.ANTHROPIC)));
        ChatClient a = service.resolveClient("acme", spec);
        ChatClient b = service.resolveClient("acme", spec);
        org.assertj.core.api.Assertions.assertThat(a).isSameAs(b);
        verify(registry, times(1)).createClient(
            any(), anyString(), anyString(), anyInt(), anyDouble(), anyDouble(), any(Map.class));
        verify(budget, times(2)).assertWithinBudget("acme");
        verify(rate, times(2)).assertWithinRateLimit("acme");
    }

    @Test
    void withMemoDisabledByZeroTtlBehavesLikeNoMemo() {
        stubRegistry();
        service.setClientMemo(new TtlGatewayClientMemo(Duration.ZERO, 10, Set.of(ProviderName.ANTHROPIC)));
        service.resolveClient("acme", spec);
        service.resolveClient("acme", spec);
        verify(registry, times(2)).createClient(
            any(), anyString(), anyString(), anyInt(), anyDouble(), anyDouble(), any(Map.class));
    }
}
