package com.agenticai.core.agents.turn.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.decision.DecisionEngine;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

/**
 * The quality passes, the planner and the cost-routing question read their text from the prompt
 * registry on the real call path: a tenant override reaches every agent of the tenant, an agent
 * override only that agent, and with no override the shipped wording is sent.
 */
class SecondaryCallPromptOverrideTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void override(PromptScope scope, String key, String text) {
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        store.put(
                new PromptOverride(
                        scope, scope == PromptScope.AGENT ? AGENT.toString() : "", key, "en", text, true, 1, "owner", Instant.now()));
        resolver.evictAll();
    }

    private record Mocked(ChatClient client, ChatClient.ChatClientRequestSpec spec) {
        List<String> systems() {
            ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
            verify(spec, atLeastOnce()).system(system.capture());
            return system.getAllValues();
        }
    }

    private static Mocked answering(String... contents) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.advisors(org.mockito.ArgumentMatchers.<java.util.function.Consumer<ChatClient.AdvisorSpec>>any()))
                .thenReturn(spec);
        when(spec.call()).thenReturn(call);
        if (contents.length == 1) {
            when(call.content()).thenReturn(contents[0]);
        } else {
            when(call.content()).thenReturn(contents[0], java.util.Arrays.copyOfRange(contents, 1, contents.length));
        }
        return new Mocked(chatClient, spec);
    }

    private static String shipped(String key) {
        return PromptResolver.defaultsOnly().catalog().defaultText(key);
    }

    @Test
    void reflexionSendsTheShippedWordingByDefaultAndTheAgentsOverrideForThatAgentOnly() {
        ReflexionService service = new ReflexionService();
        service.setPromptResolver(resolver);

        Mocked plain = answering("INCOMPLETE: gap", "better");
        service.critiqueAndMaybeRevise(plain.client(), "q", "a", null, null, AGENT, "s");
        assertThat(plain.systems())
                .containsExactly(shipped("quality.reflexion.critique"), shipped("quality.reflexion.revise"));

        override(PromptScope.AGENT, "quality.reflexion.critique", "Critique like a hawk.");
        override(PromptScope.AGENT, "quality.reflexion.revise", "Rewrite it.");

        Mocked forAgent = answering("INCOMPLETE: gap", "better");
        service.critiqueAndMaybeRevise(forAgent.client(), "q", "a", null, null, AGENT, "s");
        assertThat(forAgent.systems()).containsExactly("Critique like a hawk.", "Rewrite it.");

        Mocked forOther = answering("INCOMPLETE: gap", "better");
        service.critiqueAndMaybeRevise(forOther.client(), "q", "a", null, null, OTHER, "s");
        assertThat(forOther.systems())
                .containsExactly(shipped("quality.reflexion.critique"), shipped("quality.reflexion.revise"));
    }

    @Test
    void consistencyCheckHonoursATenantOverrideOnBothCalls() {
        AnswerConsistencyChecker checker = new AnswerConsistencyChecker();
        checker.setPromptResolver(resolver);
        override(PromptScope.TENANT, "quality.consistency.check", "Check hard.");
        override(PromptScope.TENANT, "quality.consistency.revise", "Fix it.");

        Mocked m = answering("REVISE: wrong", "fixed");
        checker.checkAndRevise(m.client(), "q", "a", null, null, OTHER, "s");

        assertThat(m.systems()).containsExactly("Check hard.", "Fix it.");
    }

    @Test
    void plannerPlanReplanAndToolHintAreOverridable() {
        TaskPlanningService planner = new TaskPlanningService();
        planner.setPromptResolver(resolver);
        override(PromptScope.TENANT, "planner.task", "Plan well.");
        override(PromptScope.AGENT, "planner.task.tools", "\nTools: {tools}.\n");
        override(PromptScope.TENANT, "planner.task.replan", "Plan again.");

        Mocked forAgent = answering("1. do it");
        planner.planIfNeeded(forAgent.client(), "msg", AGENT, "s", List.of("a", "b"));
        assertThat(forAgent.systems()).containsExactly("Plan well.\nTools: a, b.\n");

        Mocked forOther = answering("1. do it");
        planner.planIfNeeded(forOther.client(), "msg", OTHER, "s", List.of("a", "b"));
        assertThat(forOther.systems()).containsExactly("Plan well." + TaskPlanningService.toolsHint(List.of("a", "b")));

        Mocked replan = answering("1. again");
        planner.replan(replan.client(), "msg", "1. old", "step failed", AGENT, "s");
        assertThat(replan.systems()).containsExactly("Plan again.");
    }

    @Test
    void costRoutingQuestionIsOverridableForTheAgent() {
        DecisionEngine decisions = mock(DecisionEngine.class);
        when(decisions.isAvailable()).thenReturn(true);
        when(decisions.tenantPolicy()).thenReturn(Optional.empty());
        when(decisions.noul(anyString(), anyString())).thenReturn(Optional.of(0.9));
        QueryComplexityClassifier classifier = new QueryComplexityClassifier(decisions, true, 0.75);
        classifier.setPromptResolver(resolver);
        String hard = "please analyze whether this vendor email is worth replying to " + "x".repeat(200);

        assertThat(classifier.isSimple(hard, null, AGENT)).isTrue();
        verify(decisions).noul(hard, shipped("router.complexity"));

        override(PromptScope.AGENT, "router.complexity", "Is this trivial?");
        assertThat(classifier.isSimple(hard, null, AGENT)).isTrue();
        verify(decisions).noul(hard, "Is this trivial?");
        assertThat(classifier.isSimple(hard, null, OTHER)).isTrue();
        verify(decisions, org.mockito.Mockito.times(2)).noul(hard, shipped("router.complexity"));
    }
}
