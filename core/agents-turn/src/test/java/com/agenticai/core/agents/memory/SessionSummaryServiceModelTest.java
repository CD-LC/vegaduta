package com.agenticai.core.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.auxiliary.AuxiliaryModelProperties;
import com.agenticai.core.agents.auxiliary.AuxiliaryModelResolver;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.turn.AgentServiceAuxiliaryAgentModels;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.TenantAiModelPreferenceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The rolling session summary ran on {@code listModels(provider).get(0)} of whichever credential came first.
 * It now runs on the model {@link AuxiliaryModelResolver} chooses, and logs which.
 */
class SessionSummaryServiceModelTest {

    private final UUID agentId = UUID.randomUUID();
    private final ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
    private final ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
    private final AgentService agents = mock(AgentService.class);
    private final LlmGatewayService gateway = mock(LlmGatewayService.class);
    private final TenantLimits limits = mock(TenantLimits.class);
    private final ChatClient client = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
    private SessionSummaryService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("t1", "u1", Set.of()));
        when(limits.plan("t1")).thenReturn(TenantPlan.PREMIUM);
        when(limits.usesPlatformPool("t1", TenantPlan.PREMIUM)).thenReturn(false);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("folded summary");
        when(gateway.resolveClient(anyString(), any(GatewayCallSpec.class))).thenReturn(client);
        when(credentials.resolveActiveKey(any())).thenReturn("key");
        when(catalog.replacementForRetiredModel(any())).thenReturn(Optional.empty());
        @SuppressWarnings("unchecked")
        ObjectProvider<TenantAiModelPreferenceService> none = mock(ObjectProvider.class);
        AuxiliaryModelResolver auxiliary =
                new AuxiliaryModelResolver(new AuxiliaryModelProperties(), credentials, catalog, new AgentServiceAuxiliaryAgentModels(agents), none, "claude-x");
        service = new SessionSummaryService(auxiliary, gateway, limits, mock(FreeTierChatClientService.class), true, 600, 4000, 800, 24_000);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private String fold(UUID agent) throws Exception {
        CompletableFuture<String> persisted = new CompletableFuture<>();
        List<ChatTurn> turns = List.of(new ChatTurn(MessageRole.USER, "rotate the key", Instant.parse("2026-08-17T00:00:00Z")));
        service.foldAsync("sess-1", agent, TenantContext.current(), null, turns, persisted::complete);
        return persisted.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aSessionWhoseAgentIsKnownIsSummarisedByThatAgentsModel() throws Exception {
        Agent agent = mock(Agent.class);
        when(agent.getProviderName()).thenReturn(ProviderName.GEMINI);
        when(agent.getModel()).thenReturn("gemini-2.5-pro");
        when(agents.getEntity(agentId)).thenReturn(agent);

        assertThat(fold(agentId)).isEqualTo("folded summary");

        ArgumentCaptor<GatewayCallSpec> spec = ArgumentCaptor.forClass(GatewayCallSpec.class);
        verify(gateway, timeout(1000)).resolveClient(anyString(), spec.capture());
        assertThat(spec.getValue().providerName()).isEqualTo(ProviderName.GEMINI);
        assertThat(spec.getValue().model()).isEqualTo("gemini-2.5-pro");
        assertThat(spec.getValue().featureKey()).isEqualTo("session-summary");
    }

    @Test
    void withoutAnAgentTheCatalogDefaultIsUsedNeverTheFirstAlphabeticalModel() throws Exception {
        when(credentials.resolveBest(false))
                .thenReturn(new ProviderCredentialResolver.ResolvedProvider(ProviderName.GROK, "k", null, false));
        when(catalog.defaultModelFor(ProviderName.GROK)).thenReturn(Optional.of("grok-4-1-fast-non-reasoning"));

        assertThat(fold(null)).isEqualTo("folded summary");

        ArgumentCaptor<GatewayCallSpec> spec = ArgumentCaptor.forClass(GatewayCallSpec.class);
        verify(gateway, timeout(1000)).resolveClient(anyString(), spec.capture());
        assertThat(spec.getValue().model()).isEqualTo("grok-4-1-fast-non-reasoning");
        verify(catalog, never()).listModels(any());
    }
}
