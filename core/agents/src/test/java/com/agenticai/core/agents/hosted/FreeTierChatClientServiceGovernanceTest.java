package com.agenticai.core.agents.hosted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.quota.FreeTierUsageLimiterService;
import com.agenticai.core.chat.HostedLlmChatClientFactory;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.chat.gateway.GatewayGovernanceAdvisor;
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
 * The hosted-pool (free-tier agent chat) path never goes through {@code
 * LlmGatewayService#resolveClient}, so {@link FreeTierChatClientService} must
 * attach the {@link GatewayGovernanceAdvisor} beans itself - otherwise the
 * content policy (injection screening) and response PII flag silently skip
 * every hosted-pool turn.
 */
class FreeTierChatClientServiceGovernanceTest {

    private static final String TENANT_ID = "tenant-1";

    private FreeTierUsageLimiterService usageLimiterService;
    private PlatformHostedLlmProviderService platformHostedLlmProviderService;
    private HostedLlmChatClientFactory hostedLlmChatClientFactory;
    private FreeTierChatClientService service;
    private final List<Object> attachedAdvisors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        usageLimiterService = mock(FreeTierUsageLimiterService.class);
        platformHostedLlmProviderService = mock(PlatformHostedLlmProviderService.class);
        hostedLlmChatClientFactory = mock(HostedLlmChatClientFactory.class);
        service = new FreeTierChatClientService(
            usageLimiterService, platformHostedLlmProviderService, hostedLlmChatClientFactory,
            mock(PerUserBudgetService.class), mock(LlmPricingCatalog.class), new HostedProviderHealth(3, 120, 180),
            mock(PlatformSettingsService.class), true, 10_000L);

        when(usageLimiterService.checkAndReserveTokenBudget(TENANT_ID, TenantPlan.FREE, 512)).thenReturn(2512L);
        PlatformHostedLlmProvider provider = mock(PlatformHostedLlmProvider.class);
        when(provider.getId()).thenReturn(1L);
        when(provider.getLabel()).thenReturn("test-row");
        when(provider.getModel()).thenReturn("test-model");
        when(provider.getPriority()).thenReturn(0);
        when(platformHostedLlmProviderService.resolveEnabledChain())
            .thenReturn(List.of(new HostedProviderSelection(provider, "decrypted-key")));

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
        when(hostedLlmChatClientFactory.create(eq(provider), anyString(), anyInt(), anyDouble(), anyDouble(), anyString(), any(com.agenticai.core.chat.SamplingOptions.class)))
            .thenReturn(rawClient);
    }

    @Test
    void attachesEveryGovernanceAdvisorToEachHostedLink() {
        GatewayGovernanceAdvisor contentPolicy = mock(GatewayGovernanceAdvisor.class);
        GatewayGovernanceAdvisor responsePii = mock(GatewayGovernanceAdvisor.class);
        service.setGovernanceAdvisors(List.of(contentPolicy, responsePii));

        service.buildChainForFreeTenant(TENANT_ID, TenantPlan.FREE, 512, 0.7, 1.0, null, null);

        assertTrue(attachedAdvisors.contains(contentPolicy));
        assertTrue(attachedAdvisors.contains(responsePii));
        // Still exactly one of each - attached once, alongside the usage-tracking advisor.
        assertEquals(1, attachedAdvisors.stream().filter(a -> a == contentPolicy).count());
        assertTrue(attachedAdvisors.stream().anyMatch(a -> a instanceof FreeTierTokenTrackingAdvisor));
    }

    @Test
    void noGovernanceBeansAttachesOnlyTheUsageAdvisors() {
        // Setter never called (slim context) - the chain still builds.
        service.buildChainForFreeTenant(TENANT_ID, TenantPlan.FREE, 512, 0.7, 1.0, null, null);

        assertFalse(attachedAdvisors.isEmpty());
        assertTrue(attachedAdvisors.stream().noneMatch(a -> a instanceof GatewayGovernanceAdvisor));
    }

    @Test
    void agentSamplingOptionsReachTheHostedFactoryAndSurviveOnTheLink() {
        var sampling = new com.agenticai.core.chat.SamplingOptions(40, List.of("END"), 0.4, 0.3, 7);

        List<HostedChainLink> chain = service.buildChainForFreeTenant(
            TENANT_ID, TenantPlan.FREE, 512, 0.7, 1.0, null, null, sampling);

        org.mockito.Mockito.verify(hostedLlmChatClientFactory).create(
            any(com.agenticai.core.providers.PlatformHostedLlmProvider.class), anyString(), anyInt(), anyDouble(), anyDouble(), anyString(), eq(sampling));
        assertEquals(sampling, chain.get(0).sampling());
    }
}
