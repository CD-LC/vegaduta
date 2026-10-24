package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.crew.HandoffChain;
import com.agenticai.core.agents.crew.SpecialistCandidate;
import com.agenticai.core.agents.crew.SpecialistHandoffService;
import com.agenticai.core.agents.skills.SkillPromptBlock;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.skills.SkillSource;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.ResourceAccessService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

/**
 * A delegated or specialist call reads its framing texts (the skills header, the forwarded-context
 * header, the empty-answer line) through the DELEGATE's own overrides, not the built-in wording.
 */
class DelegationTextsOverrideTest {

    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 22);

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
    private final PromptDirectiveSettings settings = PromptDirectiveSettings.forTest(resolver);
    private final SubAgentTurn subTurn = mock(SubAgentTurn.class);
    private ChatDelegateCall delegate;
    private ChatSpecialistHandoff specialist;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant-a", "owner", Set.of()));
        Agent agent = mock(Agent.class);
        when(agent.getId()).thenReturn(TARGET);
        when(agent.getProviderName()).thenReturn(ProviderName.OPENAI);
        when(agent.getModel()).thenReturn("gpt-test");
        when(agent.getSystemPrompt()).thenReturn("You are a collaborator.");
        AgentService agentService = mock(AgentService.class);
        when(agentService.getEntity(any())).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("gpt-test");
        ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
        when(credentials.resolveActiveKey(any())).thenReturn("key");
        LlmGatewayService gateway = mock(LlmGatewayService.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(gateway.resolveClient(any(), any())).thenReturn(chatClient);
        AgentToolPort tools = mock(AgentToolPort.class);
        when(tools.resolveToolsForDelegatedCall(any(), any(), any(), any())).thenReturn(List.of());
        when(tools.resolveToolsForSpecialistHandoff(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(tools.proceduresPromptBlock(any())).thenReturn("");
        String skills =
                SkillPromptBlock.render(
                        List.of(new SkillService.SkillMetadata(null, "xlsx", "Make sheets", SkillSource.BUILT_IN, true, 0)));
        when(tools.skillsPromptBlock(any(), any())).thenReturn(skills);
        ChatPromptDirectives.DirectiveGates gates =
                new ChatPromptDirectives.DirectiveGates(
                        () -> true,
                        () -> true,
                        () -> true,
                        () -> true,
                        () -> true,
                        id -> settings.forTurn(DAY, id));
        ResourceAccessService access = mock(ResourceAccessService.class);
        LlmQualityJudgeService judge = mock(LlmQualityJudgeService.class);
        ProviderErrorTranslator translator = mock(ProviderErrorTranslator.class);
        ChatTurnExecutor turns =
                new ChatTurnExecutor(null, null, null, null, null, null, null, null, null, java.time.Duration.ofSeconds(30));
        delegate =
                new ChatDelegateCall(
                        agentService, access, credentials, gateway, tools, judge, translator, turns, t -> null, gates, subTurn);
        specialist =
                new ChatSpecialistHandoff(
                        agentService, access, credentials, gateway, tools, judge, translator, turns, t -> null, gates, subTurn);
        SpecialistHandoffService handoffService = mock(SpecialistHandoffService.class);
        when(handoffService.findSpecialist(any())).thenReturn(Optional.of(new SpecialistCandidate(TARGET, "Billing", "billing")));
        specialist.setHandoffService(handoffService);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void override(PromptScope scope, String key, String text) {
        store.put(
                new PromptOverride(
                        scope, scope == PromptScope.AGENT ? TARGET.toString() : "", key, "en", text, true, 1, "owner", Instant.now()));
        resolver.evictAll();
    }

    private SubAgentTurn.Request captured() {
        ArgumentCaptor<SubAgentTurn.Request> request = ArgumentCaptor.forClass(SubAgentTurn.Request.class);
        verify(subTurn).run(request.capture());
        return request.getValue();
    }

    @Test
    void withNoOverrideTheFramingIsTheOldText() {
        delegate.delegateCall(TARGET, "What is 2+2?", "The user is in finance.");

        SubAgentTurn.Request r = captured();
        assertThat(r.systemPrompt())
                .contains(
                        "\n\nContext forwarded by the delegating agent (a short summary, not the full conversation - use it only if relevant to the question below):\nThe user is in finance.")
                .contains("Before you answer, check whether the request matches one of these skills.");
        assertThat(r.emptyAnswer()).isEqualTo("The collaborator agent returned an empty answer.");
    }

    @Test
    void aDelegatedCallUsesTheDelegatesOwnOverrides() {
        override(PromptScope.TENANT, "delegation.context_header.collaborator", "From the caller: {context}");
        override(PromptScope.AGENT, "skills.header", "## Skills\nUse them.");
        override(PromptScope.TENANT, "delegation.empty_answer.collaborator", "Nothing came back.");

        delegate.delegateCall(TARGET, "What is 2+2?", "The user is in finance.");

        SubAgentTurn.Request r = captured();
        assertThat(r.systemPrompt())
                .contains("From the caller: The user is in finance.")
                .contains("## Skills\nUse them.\n- xlsx: Make sheets")
                .doesNotContain("Context forwarded by the delegating agent")
                .doesNotContain("FIRST action");
        assertThat(r.emptyAnswer()).isEqualTo("Nothing came back.");
    }

    @Test
    void aSpecialistHandoffUsesTheTargetsOwnOverrides() {
        override(PromptScope.AGENT, "delegation.context_header.specialist", "Handed over: {context}");
        override(PromptScope.AGENT, "delegation.empty_answer.specialist", "The specialist had nothing.");

        specialist.delegateToSpecialist(HandoffChain.root("parent", UUID.randomUUID()), "billing", "Refund it", "Order 42");

        SubAgentTurn.Request r = captured();
        assertThat(r.systemPrompt()).contains("Handed over: Order 42");
        assertThat(r.emptyAnswer()).isEqualTo("The specialist had nothing.");
    }
}
