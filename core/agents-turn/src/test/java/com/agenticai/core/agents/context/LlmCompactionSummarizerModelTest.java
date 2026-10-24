package com.agenticai.core.agents.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Compaction ran on {@code listModels(provider).get(0)} of whichever credential came first. It now runs on the
 * model {@link AuxiliaryModelResolver} chooses: the agent being compacted keeps its own model.
 */
class LlmCompactionSummarizerModelTest {

    private final UUID agentId = UUID.randomUUID();
    private final ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
    private final ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
    private final AgentService agents = mock(AgentService.class);
    private final LlmGatewayService gateway = mock(LlmGatewayService.class);
    private final TenantLimits limits = mock(TenantLimits.class);
    private final FreeTierChatClientService pool = mock(FreeTierChatClientService.class);
    private final ChatClient client = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
    private LlmCompactionSummarizer summarizer;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("t1", "u1", Set.of()));
        when(limits.plan("t1")).thenReturn(TenantPlan.PREMIUM);
        when(limits.usesPlatformPool("t1", TenantPlan.PREMIUM)).thenReturn(false);
        when(client.prompt().system(anyString()).user(anyString()).advisors(any(java.util.function.Consumer.class)).call().content())
                .thenReturn("the summary");
        when(gateway.resolveClient(anyString(), any(GatewayCallSpec.class))).thenReturn(client);
        when(credentials.resolveActiveKey(any())).thenReturn("key");
        when(catalog.replacementForRetiredModel(any())).thenReturn(Optional.empty());
        @SuppressWarnings("unchecked")
        ObjectProvider<TenantAiModelPreferenceService> none = mock(ObjectProvider.class);
        AuxiliaryModelResolver auxiliary =
                new AuxiliaryModelResolver(new AuxiliaryModelProperties(), credentials, catalog, new AgentServiceAuxiliaryAgentModels(agents), none, "claude-x");
        summarizer = new LlmCompactionSummarizer(auxiliary, gateway, limits, pool, 800, 20_000L, 24_000, 2_000);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Optional<String> summarizeFor(UUID agent) {
        List<Message> turns = List.of(new UserMessage("hello"));
        return summarizer.summarize(TenantContext.current(), agent, "s1", null, turns);
    }

    @Test
    void theAgentBeingCompactedKeepsItsOwnModel() {
        Agent agent = mock(Agent.class);
        when(agent.getProviderName()).thenReturn(ProviderName.MISTRAL);
        when(agent.getModel()).thenReturn("mistral-large-x");
        when(agents.getEntity(agentId)).thenReturn(agent);

        assertThat(summarizeFor(agentId)).contains("the summary");

        ArgumentCaptor<GatewayCallSpec> spec = ArgumentCaptor.forClass(GatewayCallSpec.class);
        verify(gateway).resolveClient(anyString(), spec.capture());
        assertThat(spec.getValue().providerName()).isEqualTo(ProviderName.MISTRAL);
        assertThat(spec.getValue().model()).isEqualTo("mistral-large-x");
        assertThat(spec.getValue().featureKey()).isEqualTo("compaction");
        verify(catalog, never()).listModels(any());
    }

    @Test
    void withoutAnAgentTheBestCredentialsCatalogDefaultIsUsedNotTheFirstAlphabeticalModel() {
        when(credentials.resolveBest(false))
                .thenReturn(new ProviderCredentialResolver.ResolvedProvider(ProviderName.OPENAI, "k", null, false));
        when(catalog.defaultModelFor(ProviderName.OPENAI)).thenReturn(Optional.of("gpt-4o-mini"));

        assertThat(summarizeFor(null)).contains("the summary");

        ArgumentCaptor<GatewayCallSpec> spec = ArgumentCaptor.forClass(GatewayCallSpec.class);
        verify(gateway).resolveClient(anyString(), spec.capture());
        assertThat(spec.getValue().model()).isEqualTo("gpt-4o-mini");
        verify(catalog, never()).listModels(any());
    }
}
