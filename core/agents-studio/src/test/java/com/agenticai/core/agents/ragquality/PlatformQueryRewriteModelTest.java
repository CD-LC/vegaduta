package com.agenticai.core.agents.ragquality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.spendcap.SpendCapService;
import com.agenticai.core.chat.HostedLlmChatClientFactory;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import com.agenticai.core.security.ImpersonationContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.tenancy.PlatformFeatureBudgetService;
import com.agenticai.core.tenancy.PlatformFeatureBudgetService.BudgetDecision;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;

/** Every early exit of the platform-pool query rewriter, and how a real call is metered. */
class PlatformQueryRewriteModelTest {

    private PlatformFeatureBudgetService budget;
    private PlatformHostedLlmProviderService providers;
    private HostedLlmChatClientFactory factory;
    private LlmPricingCatalog pricing;
    private TenantLimits limits;
    private ChatClient client;
    private PlatformHostedLlmProvider row;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private PlatformQueryRewriteModel model(boolean allowByok) {
        return new PlatformQueryRewriteModel(
                provider(budget),
                provider(providers),
                provider(factory),
                provider(pricing),
                provider(limits),
                allowByok);
    }

    @BeforeEach
    void setUp() {
        budget = mock(PlatformFeatureBudgetService.class);
        providers = mock(PlatformHostedLlmProviderService.class);
        factory = mock(HostedLlmChatClientFactory.class);
        pricing = mock(LlmPricingCatalog.class);
        limits = mock(TenantLimits.class);
        client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        row = mock(PlatformHostedLlmProvider.class);
        when(row.getModel()).thenReturn("cheap-1");
        when(limits.usesPlatformPool("acme")).thenReturn(true);
        when(budget.checkBudget(PlatformQueryRewriteModel.FEATURE_KEY))
                .thenReturn(new BudgetDecision(true, null));
        when(providers.resolveTopPriorityEnabled())
                .thenReturn(Optional.of(new HostedProviderSelection(row, "key")));
        when(factory.create(eq(row), eq("key"), anyInt(), anyDouble(), anyDouble()))
                .thenReturn(client);
        TenantContext.set(TenantContext.of("acme", "u1", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        ImpersonationContext.clear();
    }

    private void answer(String text, Integer prompt, Integer completion) {
        ChatResponseMetadata.Builder meta = ChatResponseMetadata.builder();
        if (prompt != null) {
            meta.usage(new DefaultUsage(prompt, completion));
        }
        ChatResponse response =
                new ChatResponse(List.of(new Generation(new AssistantMessage(text))), meta.build());
        when(client.prompt()
                        .system(any(String.class))
                        .user(any(String.class))
                        .call()
                        .chatResponse())
                .thenReturn(response);
    }

    @Test
    void aTenantWithItsOwnKeyIsNeverSentToThePlatformPool() {
        when(limits.usesPlatformPool("acme")).thenReturn(false);

        assertThat(model(false).complete("s", "q", 64)).isEmpty();
        verify(budget, never()).checkBudget(any());
    }

    @Test
    void withoutATenantOrLimitsTheRewriteFailsClosedUnlessByokIsAllowed() {
        TenantContext.clear();
        assertThat(model(false).complete("s", "q", 64)).isEmpty();

        answer("rewritten", 10, 5);
        assertThat(model(true).complete("s", "q", 64)).contains("rewritten");
    }

    @Test
    void aViewAsSessionNeverCallsTheModel() {
        ImpersonationContext.set(
                new ImpersonationContext.Session(UUID.randomUUID(), "admin", "a@x", "acme", "u1"));

        assertThat(model(false).complete("s", "q", 64)).isEmpty();
        verify(budget, never()).checkBudget(any());
    }

    @Test
    void aTenantAtItsMonthlySpendCapSkipsTheRewrite() {
        SpendCapService cap = mock(SpendCapService.class);
        when(cap.isBlocked("acme")).thenReturn(true);
        PlatformQueryRewriteModel capped = model(false);
        capped.setSpendCapService(cap);

        assertThat(capped.complete("s", "q", 64)).isEmpty();
        verify(budget, never()).checkBudget(any());
    }

    @Test
    void aMissingCollaboratorDegradesToNoRewrite() {
        factory = null;
        assertThat(model(false).complete("s", "q", 64)).isEmpty();
    }

    @Test
    void anExhaustedBudgetOrAnEmptyPoolSkipsTheCall() {
        when(budget.checkBudget(PlatformQueryRewriteModel.FEATURE_KEY))
                .thenReturn(new BudgetDecision(false, "daily cap"));
        assertThat(model(false).complete("s", "q", 64)).isEmpty();

        when(budget.checkBudget(PlatformQueryRewriteModel.FEATURE_KEY))
                .thenReturn(new BudgetDecision(true, null));
        when(providers.resolveTopPriorityEnabled()).thenReturn(Optional.empty());
        assertThat(model(false).complete("s", "q", 64)).isEmpty();
        verify(budget, never()).recordSpend(any(), any());
    }

    @Test
    void aSuccessfulCallIsMeteredAtTheCatalogCost() {
        answer("better query", 120, 20);
        when(pricing.estimateCostUsd("cheap-1", 120L, 20L)).thenReturn(new BigDecimal("0.0004"));

        assertThat(model(false).complete("s", "q", 64)).contains("better query");
        verify(budget).recordSpend(PlatformQueryRewriteModel.FEATURE_KEY, new BigDecimal("0.0004"));
    }

    @Test
    void withoutUsageOrPricingTheFlatEstimateIsCharged() {
        answer("better query", null, null);
        when(pricing.estimateCostUsd(any(), any(), any())).thenReturn(null);

        assertThat(model(false).complete("s", "q", 64)).contains("better query");
        verify(budget)
                .recordSpend(
                        PlatformQueryRewriteModel.FEATURE_KEY,
                        PlatformQueryRewriteModel.FLAT_SPEND_ESTIMATE_USD);
    }

    @Test
    void aBlankAnswerIsStillMeteredButYieldsNothing() {
        answer("   ", 5, 1);
        when(pricing.estimateCostUsd(any(), any(), any())).thenReturn(BigDecimal.ONE);

        assertThat(model(false).complete("s", "q", 64)).isEmpty();
        verify(budget).recordSpend(PlatformQueryRewriteModel.FEATURE_KEY, BigDecimal.ONE);
    }

    @Test
    void aNullResponseYieldsNothingAndIsNotMetered() {
        when(client.prompt()
                        .system(any(String.class))
                        .user(any(String.class))
                        .call()
                        .chatResponse())
                .thenReturn(null);

        assertThat(model(false).complete("s", "q", 64)).isEmpty();
        verify(budget, never()).recordSpend(any(), any());
    }
}
