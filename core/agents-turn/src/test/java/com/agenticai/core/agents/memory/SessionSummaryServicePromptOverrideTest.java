package com.agenticai.core.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.auxiliary.AuxiliaryModelProperties;
import com.agenticai.core.agents.auxiliary.AuxiliaryModelResolver;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.turn.AgentServiceAuxiliaryAgentModels;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
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
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The rolling-summary system prompt is resolved through the registry on the real fold: the client only answers
 * when it is sent exactly the wording the test expects, so a persisted summary proves which text was sent.
 */
class SessionSummaryServicePromptOverrideTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
    private final ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
    private final LlmGatewayService gateway = mock(LlmGatewayService.class);
    private final TenantLimits limits = mock(TenantLimits.class);
    private final ChatClient client = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
    private SessionSummaryService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("t1", "u1", Set.of()));
        when(limits.plan("t1")).thenReturn(TenantPlan.PREMIUM);
        when(limits.usesPlatformPool("t1", TenantPlan.PREMIUM)).thenReturn(false);
        when(gateway.resolveClient(anyString(), any(GatewayCallSpec.class))).thenReturn(client);
        when(credentials.resolveActiveKey(any())).thenReturn("key");
        when(catalog.replacementForRetiredModel(any())).thenReturn(Optional.empty());
        when(credentials.resolveBest(false)).thenReturn(new ProviderCredentialResolver.ResolvedProvider(ProviderName.OPENAI, "k", null, false));
        when(catalog.defaultModelFor(ProviderName.OPENAI)).thenReturn(Optional.of("gpt-4o-mini"));
        @SuppressWarnings("unchecked")
        ObjectProvider<TenantAiModelPreferenceService> none = mock(ObjectProvider.class);
        AuxiliaryModelResolver auxiliary =
                new AuxiliaryModelResolver(
                        new AuxiliaryModelProperties(), credentials, catalog, new AgentServiceAuxiliaryAgentModels(mock(AgentService.class)), none, "claude-x");
        service = new SessionSummaryService(auxiliary, gateway, limits, mock(FreeTierChatClientService.class), true, 600, 4000, 800, 24_000);
        service.setPromptResolver(resolver);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void answerOnlyTo(String systemPrompt) {
        when(client.prompt().system(eq(systemPrompt)).user(anyString()).call().content()).thenReturn("folded summary");
    }

    private String fold(UUID agent) throws Exception {
        CompletableFuture<String> persisted = new CompletableFuture<>();
        List<ChatTurn> turns = List.of(new ChatTurn(MessageRole.USER, "rotate the key", Instant.parse("2026-08-17T00:00:00Z")));
        service.foldAsync("sess-1", agent, TenantContext.current(), null, turns, persisted::complete);
        try {
            return persisted.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return null; // nothing persisted: the client did not answer, i.e. another wording was sent
        }
    }

    @Test
    void theShippedWordingIsSentUntilAnAgentOverrideExists() throws Exception {
        answerOnlyTo(PromptResolver.defaultsOnly().catalog().defaultText("summarizer.session"));
        assertThat(fold(AGENT)).isEqualTo("folded summary");
    }

    @Test
    void anAgentOverrideIsSentForThatAgentAndNotForAnother() throws Exception {
        store.put(new PromptOverride(PromptScope.AGENT, AGENT.toString(), "summarizer.session", "en", "Fold it.", true, 1, "owner", Instant.now()));
        resolver.evictAll();
        answerOnlyTo("Fold it.");

        assertThat(fold(AGENT)).isEqualTo("folded summary");
        assertThat(fold(UUID.randomUUID())).isNull();
    }
}
