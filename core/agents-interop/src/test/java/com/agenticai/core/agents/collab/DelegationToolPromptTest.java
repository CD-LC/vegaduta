package com.agenticai.core.agents.collab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.crew.DelegateToSpecialistToolCallback;
import com.agenticai.core.agents.crew.HandoffChain;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.prompts.tool.ToolPromptGolden;
import com.agenticai.core.prompts.tool.ToolPromptSchemas;
import com.agenticai.core.prompts.tool.ToolPromptTexts;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The three delegation tools build their description from run time values and look it up in the
 * prompt registry themselves (tool.template.*). The expected strings below are what the code built
 * before the registry existed, copied from the old implementation's output, so a change of the
 * shipped default shows up here.
 */
class DelegationToolPromptTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final String TEAM =
            "Delegates several independent questions to specialist agents AT ONCE and waits for all of them. "
                    + "Members you can address: alpha (Alpha Agent), beta (Beta Agent). Use this when the user's request "
                    + "splits into independent parts that different specialists can answer in parallel - each member gets "
                    + "only the question you send (no conversation history), works in isolation, and you get every answer "
                    + "back together. Do NOT use it for sequential work where one specialist's answer shapes the next "
                    + "question - delegate one at a time instead.";

    private static final String SPECIALIST =
            "Hands off a focused sub-task to a specialist agent, chosen by capability/role rather than by name. "
                    + "Available capabilities in this workspace: research, billing. Use this when part of the request "
                    + "falls squarely in one of those specialties rather than your own. The specialist will NOT see this "
                    + "conversation's history, only the sub-task you send plus, if you provide one, a short context "
                    + "summary. It may itself hand off to ANOTHER specialist if its own sub-task needs one, up to 3 hops "
                    + "total in this chain (currently at hop 0 of 3) - it can never hand back to an agent already in this "
                    + "chain.";

    private static final String AGENT_WITH_SPECIALTY =
            "Delegates a specific question to \"Alpha Agent\", a specialist agent: Answers finance questions. Use "
                    + "this when part of the user's request falls squarely in this specialist's area rather than your own "
                    + "- ask it a focused, self-contained question and use its answer. It will NOT see this conversation's "
                    + "history, only the exact question you send plus, if you provide one, a short context summary.";

    private static final String AGENT_WITHOUT_SPECIALTY =
            "Delegates a specific question to \"Alpha Agent\", a specialist agent. Use this when part of the "
                    + "user's request falls squarely in this specialist's area rather than your own - ask it a focused, "
                    + "self-contained question and use its answer. It will NOT see this conversation's history, only the "
                    + "exact question you send plus, if you provide one, a short context summary.";

    private final PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver = new DefaultPromptResolver(catalog, store, new PromptProperties());

    @BeforeEach
    void tenant() {
        TenantContext.set(TenantContext.of("tenant-a", "admin@a", Set.of("ROLE_tenant-admin")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void override(PromptScope scope, UUID agent, String key, String text) {
        store.put(
                new PromptOverride(scope, scope == PromptScope.AGENT ? agent.toString() : "", key, "en", text, true, 1, "t", Instant.now()));
        resolver.evictAll();
    }

    private static DelegateToTeamToolCallback team() {
        return new DelegateToTeamToolCallback(
                mock(AgentChatService.class),
                List.of(
                        new DelegateToTeamToolCallback.Member(UUID.randomUUID(), "Alpha Agent", "alpha"),
                        new DelegateToTeamToolCallback.Member(UUID.randomUUID(), "Beta Agent", "beta")),
                null);
    }

    private static DelegateToSpecialistToolCallback specialist() {
        return new DelegateToSpecialistToolCallback(
                mock(AgentChatService.class), List.of("research", "billing"), HandoffChain.root("s1", UUID.randomUUID()));
    }

    private static DelegateToAgentToolCallback agent(String specialty) {
        return new DelegateToAgentToolCallback(
                mock(AgentChatService.class), UUID.randomUUID(), "ask_alpha", "Alpha Agent", specialty);
    }

    @Test
    void byDefaultEveryDescriptionIsWhatTheCodeBuiltBeforeTheRegistry() {
        assertThat(team().getToolDefinition().description()).isEqualTo(TEAM);
        assertThat(specialist().getToolDefinition().description()).isEqualTo(SPECIALIST);
        assertThat(agent("Answers finance questions").getToolDefinition().description()).isEqualTo(AGENT_WITH_SPECIALTY);
        assertThat(agent("").getToolDefinition().description()).isEqualTo(AGENT_WITHOUT_SPECIALTY);
        assertThat(agent(null).getToolDefinition().description()).isEqualTo(AGENT_WITHOUT_SPECIALTY);
    }

    @Test
    void theRosterListsTheMembersInTheOrderTheyWereGivenWhateverTheirNumber() {
        // Map.copyOf iterates in a per-JVM salted order; with sixteen members a copy that lost the order
        // would match the given one only with probability 1/16!, so this fails deterministically if it does.
        List<DelegateToTeamToolCallback.Member> members = new java.util.ArrayList<>();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            String slug = String.format("m%02d", i);
            members.add(new DelegateToTeamToolCallback.Member(UUID.randomUUID(), "Member " + i, slug));
            expected.append(i == 0 ? "" : ", ").append(slug).append(" (Member ").append(i).append(")");
        }
        DelegateToTeamToolCallback sixteen = new DelegateToTeamToolCallback(mock(AgentChatService.class), members, null);
        assertThat(sixteen.getToolDefinition().description()).contains("Members you can address: " + expected + ".");
    }

    @Test
    void theShippedTemplatesFilledInEqualThoseTexts() {
        PromptCatalog c = PromptResolver.defaultsOnly().catalog();
        assertThat(ToolPromptGolden.problems(c, "delegate_parallel", TEAM, team().getToolDefinition().inputSchema(),
                        Map.of("roster", "alpha (Alpha Agent), beta (Beta Agent)")))
                .isEmpty();
        assertThat(ToolPromptGolden.problems(c, "delegate_to_specialist", SPECIALIST, specialist().getToolDefinition().inputSchema(),
                        Map.of("roster", "research, billing", "max_depth", String.valueOf(HandoffChain.MAX_DEPTH), "hop", "0")))
                .isEmpty();
        assertThat(ToolPromptGolden.problems(c, "delegate_to_agent", AGENT_WITH_SPECIALTY, agent("x").getToolDefinition().inputSchema(),
                        Map.of("name", "Alpha Agent", "specialty", ": Answers finance questions.")))
                .isEmpty();
    }

    @Test
    void anAgentOverrideReachesTheToolsBuiltForThatAgentOnly() {
        override(PromptScope.AGENT, AGENT, "tool.template.delegate_parallel", "Team of {roster} - ask in parallel.");
        override(PromptScope.AGENT, AGENT, "tool.template.delegate_to_specialist", "Specialists: {roster} (hop {hop}/{max_depth}).");
        override(PromptScope.AGENT, AGENT, "tool.template.delegate_to_agent", "Ask \"{name}\"{specialty} Only when needed.");

        DelegateToTeamToolCallback forAgent =
                ToolPromptTexts.within(ToolPromptTexts.of(resolver, AGENT), DelegationToolPromptTest::team);
        DelegateToTeamToolCallback forOther =
                ToolPromptTexts.within(ToolPromptTexts.of(resolver, UUID.randomUUID()), DelegationToolPromptTest::team);

        assertThat(forAgent.getToolDefinition().description()).isEqualTo("Team of alpha (Alpha Agent), beta (Beta Agent) - ask in parallel.");
        assertThat(forOther.getToolDefinition().description()).isEqualTo(TEAM);
        assertThat(ToolPromptTexts.within(ToolPromptTexts.of(resolver, AGENT), DelegationToolPromptTest::specialist)
                        .getToolDefinition().description())
                .isEqualTo("Specialists: research, billing (hop 0/" + HandoffChain.MAX_DEPTH + ").");
        assertThat(ToolPromptTexts.within(ToolPromptTexts.of(resolver, AGENT), () -> agent("Answers finance questions"))
                        .getToolDefinition().description())
                .isEqualTo("Ask \"Alpha Agent\": Answers finance questions. Only when needed.");
    }

    @Test
    void aToolBuiltOutsideAnyScopeKeepsTheShippedWordingWhateverIsOverridden() {
        override(PromptScope.TENANT, null, "tool.template.delegate_parallel", "Overridden {roster}.");
        assertThat(team().getToolDefinition().description()).isEqualTo(TEAM);
    }

    @Test
    void parameterOverridesChangeOnlyTheTextsAndTheToolKeepsItsSchemaAndName() {
        override(PromptScope.TENANT, null, "tool.param.delegate_parallel.tasks.question", "Ask this.");
        override(PromptScope.TENANT, null, "tool.param.delegate_to_agent.question", "The question.");
        DelegateToTeamToolCallback team = ToolPromptTexts.within(ToolPromptTexts.of(resolver, AGENT), DelegationToolPromptTest::team);
        ToolDefinition def = team.getToolDefinition();
        String plain = team().getToolDefinition().inputSchema();

        assertThat(ToolPromptSchemas.byPath(def.inputSchema())).containsEntry("tasks.question", "Ask this.");
        assertThat(ToolPromptSchemas.sameShape(plain, def.inputSchema())).isTrue();
        assertThat(def.name()).isEqualTo("delegate_parallel");
        assertThat(def.inputSchema()).contains("\"maxItems\"");
        assertThat(ToolPromptSchemas.byPath(
                        ToolPromptTexts.within(ToolPromptTexts.of(resolver, AGENT), () -> agent("x"))
                                .getToolDefinition().inputSchema()))
                .containsEntry("question", "The question.");
    }
}
