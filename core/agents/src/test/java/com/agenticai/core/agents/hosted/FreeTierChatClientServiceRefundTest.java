package com.agenticai.core.agents.hosted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetExhaustedException;
import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.agents.budget.TurnReservationGuards;
import com.agenticai.core.agents.quota.FreeTierUsageLimiterService;
import com.agenticai.core.chat.HostedLlmChatClientFactory;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Regression coverage for {@link FreeTierChatClientService#buildChainForFreeTenant}'s reservation
 * release paths: after the atomic token-budget reservation, two more things can each still
 * reject/fail the turn before any provider is engaged - the atomic per-user dollar-budget
 * reservation and an empty enabled-provider pool. Each rejection path must release whichever
 * token/dollar reservation was already made, then rethrow unchanged.
 *
 * <p>The gates are the atomic reserve-then-check pair {@code checkAndReserveTokenBudget}/{@code
 * checkAndReserveBudget} (a plain read-then-record pair would let a burst of concurrent calls near
 * the cap all read "under budget" before any of their real usage landed), and the reserved amounts
 * are threaded into the built chain link for the advisors (and, on total chain failure, {@code
 * AgentChatService}) to reconcile/release later.
 *
 * <p>2026-09-26 simplification: the message-count quota (and its {@code checkAndConsume}/{@code
 * refundMessage} plumbing) was removed - the token reservation's own release path covers every
 * failure these tests exercise.
 */
class FreeTierChatClientServiceRefundTest {

    private static final String TENANT_ID = "tenant-1";

    private FreeTierUsageLimiterService usageLimiterService;
    private PlatformHostedLlmProviderService platformHostedLlmProviderService;
    private HostedLlmChatClientFactory hostedLlmChatClientFactory;
    private PerUserBudgetService perUserBudgetService;
    private FreeTierChatClientService service;

    @BeforeEach
    void setUp() {
        usageLimiterService = mock(FreeTierUsageLimiterService.class);
        platformHostedLlmProviderService = mock(PlatformHostedLlmProviderService.class);
        hostedLlmChatClientFactory = mock(HostedLlmChatClientFactory.class);
        perUserBudgetService = mock(PerUserBudgetService.class);
        LlmPricingCatalog pricingCatalog = mock(LlmPricingCatalog.class);
        HostedProviderHealth providerHealth = new HostedProviderHealth(3, 120, 180);
        // Unstubbed getString returns null -> the service falls back to the
        // default effort ("low"); these tests only care about refund paths.
        PlatformSettingsService platformSettingsService = mock(PlatformSettingsService.class);
        service =
                new FreeTierChatClientService(
                        usageLimiterService,
                        platformHostedLlmProviderService,
                        hostedLlmChatClientFactory,
                        perUserBudgetService,
                        pricingCatalog,
                        providerHealth,
                        platformSettingsService,
                        true,
                        10_000L);
    }

    @Test
    void releasesTheReservationWhenTheTokenBudgetGateRejects() {
        when(usageLimiterService.checkAndReserveTokenBudget(TENANT_ID, TenantPlan.FREE, 512))
                .thenThrow(
                        new TenantLimitExceededException("Free plan monthly token limit reached"));

        assertThrows(
                TenantLimitExceededException.class,
                () ->
                        service.buildChainForFreeTenant(
                                TENANT_ID, TenantPlan.FREE, 512, 0.7, 1.0, null, null));

        // Nothing was actually reserved (the reserve call itself threw before
        // returning) - reconcileTokenReservation still fires with 0, which is
        // a documented no-op on that method's <= 0 guard.
        verify(usageLimiterService).reconcileTokenReservation(TENANT_ID, 0L, 0L);
        // No end-user identity on this call - the per-user gate is skipped
        // entirely, never mind released.
        verify(perUserBudgetService, never())
                .releaseSpendReservation(any(), any(), any(), anyLong(), any());
    }

    @Test
    void releasesBothReservationsWhenThePerUserDollarBudgetGateRejects() {
        when(usageLimiterService.checkAndReserveTokenBudget(TENANT_ID, TenantPlan.FREE, 512))
                .thenReturn(2512L);
        when(perUserBudgetService.checkAndReserveBudget(
                        TENANT_ID, "subject-1", Modality.TEXT, 2512L, TenantPlan.FREE))
                .thenThrow(
                        new PerUserBudgetExhaustedException(
                                "Per-user text budget exhausted",
                                "text",
                                java.math.BigDecimal.ONE,
                                java.math.BigDecimal.TEN,
                                3600L));

        assertThrows(
                PerUserBudgetExhaustedException.class,
                () ->
                        service.buildChainForFreeTenant(
                                TENANT_ID,
                                TenantPlan.FREE,
                                512,
                                0.7,
                                1.0,
                                "subject-1",
                                Modality.TEXT));

        // The token reservation WAS successfully made (2512) before the
        // dollar gate rejected - it must be released in full.
        verify(usageLimiterService).reconcileTokenReservation(TENANT_ID, 2512L, 0L);
        // The dollar reservation itself never landed (its own reserve call
        // threw) - released as a documented no-op (0).
        verify(perUserBudgetService)
                .releaseSpendReservation(
                        TENANT_ID, "subject-1", Modality.TEXT, 0L, TenantPlan.FREE);
    }

    @Test
    void releasesTheTokenReservationWhenTheEnabledProviderPoolIsEmpty() {
        when(usageLimiterService.checkAndReserveTokenBudget(TENANT_ID, TenantPlan.FREE, 512))
                .thenReturn(2512L);
        when(platformHostedLlmProviderService.resolveEnabledChain()).thenReturn(List.of());

        assertThrows(
                FreeTierProviderPoolEmptyException.class,
                () ->
                        service.buildChainForFreeTenant(
                                TENANT_ID, TenantPlan.FREE, 512, 0.7, 1.0, null, null));

        verify(usageLimiterService).reconcileTokenReservation(TENANT_ID, 2512L, 0L);
    }

    @Test
    void anEmptyPoolIsRepairedInsideTheTurnWhenTheLocalBootstrapCanFixIt() {
        when(usageLimiterService.checkAndReserveTokenBudget(TENANT_ID, TenantPlan.FREE, 512))
                .thenReturn(2512L);
        PlatformHostedLlmProvider provider = mock(PlatformHostedLlmProvider.class);
        when(provider.getId()).thenReturn(1L);
        when(provider.getLabel()).thenReturn("Local model (Ollama)");
        when(provider.getModel()).thenReturn("qwen3.5:2b");
        when(provider.getPriority()).thenReturn(0);
        HostedProviderSelection selection = new HostedProviderSelection(provider, "ollama");
        when(platformHostedLlmProviderService.resolveEnabledChain())
                .thenReturn(List.of())
                .thenReturn(List.of(selection));
        com.agenticai.core.providers.LocalLlmPoolBootstrap bootstrap =
                mock(com.agenticai.core.providers.LocalLlmPoolBootstrap.class);
        when(bootstrap.repairOnDemand()).thenReturn(true);
        service.setLocalPoolBootstrap(bootstrap);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.defaultAdvisors(
                        any(org.springframework.ai.chat.client.advisor.api.Advisor[].class)))
                .thenReturn(builder);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        ChatClient rawClient = mock(ChatClient.class);
        when(rawClient.mutate()).thenReturn(builder);
        when(hostedLlmChatClientFactory.create(
                        eq(provider),
                        anyString(),
                        anyInt(),
                        anyDouble(),
                        anyDouble(),
                        anyString(),
                        any(com.agenticai.core.chat.SamplingOptions.class)))
                .thenReturn(rawClient);

        var chain =
                service.buildChainForFreeTenant(
                        TENANT_ID, TenantPlan.FREE, 512, 0.7, 1.0, null, null);

        org.junit.jupiter.api.Assertions.assertEquals(1, chain.size());
        verify(bootstrap).repairOnDemand();
        verify(usageLimiterService, never()).reconcileTokenReservation(TENANT_ID, 2512L, 0L);
    }

    @Test
    void neverRefundsOrReleasesWhenTheChainBuildsSuccessfully() {
        when(usageLimiterService.checkAndReserveTokenBudget(TENANT_ID, TenantPlan.FREE, 512))
                .thenReturn(2512L);

        PlatformHostedLlmProvider provider = mock(PlatformHostedLlmProvider.class);
        when(provider.getId()).thenReturn(1L);
        when(provider.getLabel()).thenReturn("test-row");
        when(provider.getModel()).thenReturn("test-model");
        when(provider.getPriority()).thenReturn(0);
        HostedProviderSelection selection = new HostedProviderSelection(provider, "decrypted-key");
        when(platformHostedLlmProviderService.resolveEnabledChain()).thenReturn(List.of(selection));

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient builtClient = mock(ChatClient.class);
        when(builder.defaultAdvisors(
                        any(org.springframework.ai.chat.client.advisor.api.Advisor[].class)))
                .thenReturn(builder);
        when(builder.build()).thenReturn(builtClient);
        ChatClient rawClient = mock(ChatClient.class);
        when(rawClient.mutate()).thenReturn(builder);
        when(hostedLlmChatClientFactory.create(
                        eq(provider),
                        anyString(),
                        anyInt(),
                        anyDouble(),
                        anyDouble(),
                        anyString(),
                        any(com.agenticai.core.chat.SamplingOptions.class)))
                .thenReturn(rawClient);

        List<HostedChainLink> links =
                service.buildChainForFreeTenant(
                        TENANT_ID, TenantPlan.FREE, 512, 0.7, 1.0, null, null);

        assertEquals(1, links.size());
        assertSame(builtClient, links.get(0).client());
        // The reservation the chain link carries for AgentChatService's
        // total-chain-failure release path (see HostedChainLink's javadoc).
        assertEquals(2512L, links.get(0).reservedTokens());
        verify(usageLimiterService, never()).reconcileTokenReservation(any(), anyLong(), anyLong());
        verify(perUserBudgetService, never())
                .releaseSpendReservation(any(), any(), any(), anyLong(), any());
    }

    // ---- 2026-08-12 turn-scoped reservation guards ------------------------

    @Test
    void releaseReservationsReleasesBothExactlyOnceForOneTurnsGuards() {
        TurnReservationGuards guards = new TurnReservationGuards();

        service.releaseReservations(
                TENANT_ID, TenantPlan.FREE, "subject-1", Modality.TEXT, 2512L, 900L, guards);
        // A second release against the SAME turn's guards must be a no-op.
        service.releaseReservations(
                TENANT_ID, TenantPlan.FREE, "subject-1", Modality.TEXT, 2512L, 900L, guards);

        verify(usageLimiterService, times(1)).reconcileTokenReservation(TENANT_ID, 2512L, 0L);
        verify(perUserBudgetService, times(1))
                .releaseSpendReservation(
                        TENANT_ID, "subject-1", Modality.TEXT, 900L, TenantPlan.FREE);
    }

    @Test
    void releaseReservationsIsANoOpWhenAnAdvisorAlreadyConsumedTheGuards() {
        // The idle-timeout chain-exhaustion shape: a link's advisors already
        // released both reservations on the Flux.timeout CANCEL, the 408 then
        // exhausted the chain, and AgentChatService's
        // HostedPoolExhaustedException handler calls this release. Before the
        // 2026-08-12 fix it subtracted the same reservations a second time,
        // driving the token windows negative and minting phantom per-user
        // budget headroom for the rest of the window.
        TurnReservationGuards guards = new TurnReservationGuards();
        guards.consumeTokenReservation();
        guards.consumeSpendReservation();

        service.releaseReservations(
                TENANT_ID, TenantPlan.FREE, "subject-1", Modality.TEXT, 2512L, 900L, guards);

        verify(usageLimiterService, never()).reconcileTokenReservation(any(), anyLong(), anyLong());
        verify(perUserBudgetService, never())
                .releaseSpendReservation(any(), any(), any(), anyLong(), any());
    }
}
