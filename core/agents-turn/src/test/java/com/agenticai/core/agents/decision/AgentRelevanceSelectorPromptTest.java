package com.agenticai.core.agents.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The two questions put to the decision engine come from the prompt registry and honour a tenant override. */
class AgentRelevanceSelectorPromptTest {

    private final DecisionEngine decisions = mock(DecisionEngine.class);
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static AgentResponse agent(String name, String prompt) {
        AgentResponse a = mock(AgentResponse.class);
        when(a.id()).thenReturn(UUID.randomUUID());
        when(a.name()).thenReturn(name);
        when(a.systemPrompt()).thenReturn(prompt);
        return a;
    }

    private void override(String key, String text) {
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        store.put(new PromptOverride(PromptScope.TENANT, "", key, "en", text, true, 1, "owner", Instant.now()));
        resolver.evictAll();
    }

    private AgentRelevanceSelector selector() {
        when(decisions.isAvailable()).thenReturn(true);
        when(decisions.tenantPolicy()).thenReturn(Optional.empty());
        AgentRelevanceSelector s = new AgentRelevanceSelector(decisions, true);
        s.setPromptResolver(resolver);
        return s;
    }

    @Test
    void selectionQuestionIsTheShippedOneUntilOverridden() {
        AgentResponse a = agent("A", "does a");
        AgentResponse b = agent("B", "does b");
        when(decisions.choose(anyString(), anyString(), anyMap())).thenReturn(Optional.empty());
        AgentRelevanceSelector s = selector();

        s.selectBest("do a thing", List.of(a, b));
        verify(decisions).choose(
                org.mockito.ArgumentMatchers.eq("do a thing"),
                org.mockito.ArgumentMatchers.eq(PromptResolver.defaultsOnly().catalog().defaultText("router.agent_select")),
                anyMap());

        override("router.agent_select", "Who should answer?");
        s.selectBest("do a thing", List.of(a, b));
        verify(decisions).choose(
                org.mockito.ArgumentMatchers.eq("do a thing"), org.mockito.ArgumentMatchers.eq("Who should answer?"), anyMap());
    }

    @Test
    void additionalSpecialistQuestionCarriesEachCandidatesDescriptionAndHonoursAnOverride() {
        AgentResponse primary = agent("Translator", "Translates text between languages");
        AgentResponse crm = agent("CRM Logger", "Logs records into the CRM system");
        when(decisions.nouls(anyString(), anyMap())).thenReturn(Map.of());
        AgentRelevanceSelector s = selector();

        s.selectAdditionalRelevant("translate and log it", List.of(primary, crm), primary, 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> shipped = ArgumentCaptor.forClass(Map.class);
        verify(decisions).nouls(anyString(), shipped.capture());
        String question = shipped.getValue().get(crm.id().toString());
        assertThat(question).startsWith("The user's task may need more than one specialist. Would the agent \"");
        assertThat(question).contains("CRM Logger").doesNotContain("{agent}").doesNotContain("%s");

        override("router.agent_additional", "Would {agent} help?");
        s.selectAdditionalRelevant("translate and log it", List.of(primary, crm), primary, 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> overridden = ArgumentCaptor.forClass(Map.class);
        verify(decisions, org.mockito.Mockito.times(2)).nouls(anyString(), overridden.capture());
        assertThat(overridden.getValue().get(crm.id().toString())).startsWith("Would ").endsWith(" help?").contains("CRM Logger");
    }
}
