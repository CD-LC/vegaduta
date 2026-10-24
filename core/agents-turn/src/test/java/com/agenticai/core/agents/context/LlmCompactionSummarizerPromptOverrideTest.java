package com.agenticai.core.agents.context;

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
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.TenantAiModelPreferenceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The compaction summarizer's system prompt is resolved through the registry on the real call: the client
 * only answers when it is sent exactly the wording the test expects, so a summary proves which text was sent.
 */
class LlmCompactionSummarizerPromptOverrideTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
    private final ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
    private final LlmGatewayService gateway = mock(LlmGatewayService.class);
    private final TenantLimits limits = mock(TenantLimits.class);
    private final ChatClient client = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
    private LlmCompactionSummarizer summarizer;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("t1", "u1", Set.of()));
        when(limits.plan("t1")).thenReturn(TenantPlan.PREMIUM);
        when(limits.usesPlatformPool("t1", TenantPlan.PREMIUM)).thenReturn(false);
        when(gateway.resolveClient(anyString(), any(GatewayCallSpec.class))).thenReturn(client);
        when(credentials.resolveActiveKey(any())).thenReturn("key");
        when(catalog.replacementForRetiredModel(any())).thenReturn(Optional.empty());
        when(credentials.resolveBest(false))
                .thenReturn(new ProviderCredentialResolver.ResolvedProvider(com.agenticai.core.providers.ProviderName.OPENAI, "k", null, false));
        when(catalog.defaultModelFor(com.agenticai.core.providers.ProviderName.OPENAI)).thenReturn(Optional.of("gpt-4o-mini"));
        @SuppressWarnings("unchecked")
        ObjectProvider<TenantAiModelPreferenceService> none = mock(ObjectProvider.class);
        AuxiliaryModelResolver auxiliary =
                new AuxiliaryModelResolver(
                        new AuxiliaryModelProperties(), credentials, catalog, new AgentServiceAuxiliaryAgentModels(mock(AgentService.class)), none, "claude-x");
        summarizer = new LlmCompactionSummarizer(auxiliary, gateway, limits, mock(FreeTierChatClientService.class), 800, 20_000L, 24_000, 2_000);
        summarizer.setPromptResolver(resolver);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void answerOnlyTo(String systemPrompt) {
        when(client.prompt().system(eq(systemPrompt)).user(anyString()).advisors(any(java.util.function.Consumer.class)).call().content())
                .thenReturn("the summary");
    }

    private Optional<String> summarize(UUID agent) {
        List<Message> turns = List.of(new UserMessage("hello"));
        return summarizer.summarize(TenantContext.current(), agent, "s1", null, turns);
    }

    @Test
    void theShippedWordingIsSentUntilAnAgentOverrideExists() {
        answerOnlyTo(PromptResolver.defaultsOnly().catalog().defaultText("summarizer.compaction"));
        assertThat(summarize(AGENT)).contains("the summary");
    }

    @Test
    void anAgentOverrideIsSentForThatAgentAndNotForAnother() {
        store.put(new PromptOverride(PromptScope.AGENT, AGENT.toString(), "summarizer.compaction", "en", "Compact kindly.", true, 1, "owner", Instant.now()));
        resolver.evictAll();
        answerOnlyTo("Compact kindly.");

        assertThat(summarize(AGENT)).contains("the summary");
        assertThat(summarize(UUID.randomUUID())).isEmpty();
    }
}
