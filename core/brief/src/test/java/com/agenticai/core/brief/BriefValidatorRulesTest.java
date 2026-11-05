package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.brief.BriefValidationResult.Issue;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.brief.model.Brief.AgentSpec;
import com.agenticai.core.brief.model.Brief.Clarification;
import com.agenticai.core.brief.model.Brief.Generation;
import com.agenticai.core.brief.model.Brief.Guardrail;
import com.agenticai.core.brief.model.Brief.ProcedureSpec;
import com.agenticai.core.brief.model.Brief.Trigger;
import com.agenticai.core.brief.model.Brief.WorkflowSpec;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.OrchestratorService;
import com.agenticai.core.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * One defect at a time through the whole {@link BriefValidator}: header, clarifications, ref
 * hygiene, agents, procedures and workflow triggers. Setup mirrors {@link BriefValidatorTest}.
 */
class BriefValidatorRulesTest {

    private BriefValidator validator;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        AgentService agentService = mock(AgentService.class);
        doThrow(new AgentNotFoundException(UUID.randomUUID()))
                .when(agentService)
                .requireExists(any(UUID.class));
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.maxAgents("t1")).thenReturn(Optional.empty());
        when(limits.maxWorkflows("t1")).thenReturn(Optional.empty());
        OrchestratorService orchestrator =
                new OrchestratorService(
                        agentService, null, new ObjectMapper(), mock(ObjectProvider.class));
        validator =
                new BriefValidator(
                        orchestrator,
                        limits,
                        agentService,
                        mock(WorkflowService.class),
                        new ObjectMapper());
        TenantContext.set(
                TenantContext.of(
                        "t1", "user-1", Set.of("PERM_MANAGE_AGENTS", "PERM_MANAGE_INTEGRATIONS")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private List<String> errorsOf(Brief brief) {
        return validator.validate(brief).errors().stream().map(Issue::toString).toList();
    }

    private static AgentSpec agent(String ref, String name, String prompt, String depth) {
        return new AgentSpec(
                ref, name, null, prompt, depth, null, null, null, null, null, null, null, null);
    }

    private static Brief withAgents(AgentSpec... agents) {
        BriefFixtures.B b = BriefFixtures.valid();
        b.agents = new ArrayList<>(List.of(agents));
        return b.build();
    }

    @Test
    void aNullBriefIsEmpty() {
        assertThat(validator.validate(null).errors())
                .extracting(Issue::section)
                .containsExactly("brief");
    }

    @Test
    void headerRulesCoverVersionTitleGoalAndLocale() {
        BriefFixtures.B b = BriefFixtures.valid();
        b.title = "ab";
        b.goal = "too short";
        b.locale = "english-US-x";
        Brief built = b.build();
        Brief wrongVersion =
                new Brief(
                        2,
                        built.title(),
                        built.goal(),
                        built.locale(),
                        built.clarifications(),
                        built.trust(),
                        built.agents(),
                        built.procedures(),
                        built.workflows(),
                        built.tools(),
                        built.mcpServers(),
                        built.knowledge(),
                        built.secrets(),
                        built.channels(),
                        built.evalSet(),
                        built.rehearsal(),
                        built.narrative());

        assertThat(validator.validate(wrongVersion).errors())
                .extracting(Issue::section)
                .contains("briefVersion", "title", "goal", "locale");
    }

    @Test
    void clarificationsAreCappedAndEachNeedsAQuestionAndAtMostFourOptions() {
        Brief questions =
                new Brief(
                        1,
                        null,
                        null,
                        null,
                        List.of(
                                new Clarification(" ", List.of()),
                                new Clarification("Which?", List.of("a", "b", "c", "d", "e")),
                                new Clarification("Ok?", List.of("y", "n")),
                                new Clarification("Too many", List.of())),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        List<String> sections =
                validator.validate(questions).errors().stream().map(Issue::section).toList();

        assertThat(sections)
                .contains(
                        "clarifications",
                        "clarifications[0].question",
                        "clarifications[1].options");
    }

    @Test
    void aMissingOrUnknownTrustRungIsRejected() {
        BriefFixtures.B none = BriefFixtures.valid();
        none.trust = null;
        assertThat(errorsOf(none.build())).anyMatch(m -> m.startsWith("trust:"));

        BriefFixtures.B bad = BriefFixtures.valid();
        bad.trust = new Brief.Trust("GODMODE", List.of(), List.of());
        assertThat(errorsOf(bad.build())).anyMatch(m -> m.startsWith("trust.rung"));
    }

    @Test
    void refsMustBeWellFormedOfTheRightKindAndUnique() {
        assertThat(errorsOf(withAgents(agent("agent-one", "N", "p", "FAST"))))
                .anyMatch(m -> m.contains("not a valid ref"));
        assertThat(errorsOf(withAgents(agent("w5", "N", "p", "FAST"))))
                .anyMatch(m -> m.contains("must start with 'a'"));
        assertThat(
                        errorsOf(
                                withAgents(
                                        agent("a1", "N", "p", "FAST"),
                                        agent("a1", "M", "p", "FAST"))))
                .anyMatch(m -> m.contains("Duplicate ref 'a1'"));
    }

    @Test
    void agentFieldRulesAreEnforced() {
        String longText = "x".repeat(8001);
        AgentSpec bad =
                new AgentSpec(
                        "a1",
                        " ",
                        "d".repeat(401),
                        longText,
                        "WARP",
                        "AUTO",
                        new Generation(null, null, 9000),
                        List.of("", "mcp:m9/tool", "t9", "bad key!", "web_search"),
                        List.of("k9"),
                        List.of("p9"),
                        java.util.Arrays.asList(new Guardrail(" ", null), null),
                        null,
                        null);

        List<String> errors = errorsOf(withAgents(bad));

        assertThat(errors)
                .anyMatch(m -> m.contains("agents[a1].name"))
                .anyMatch(m -> m.contains("agents[a1].description"))
                .anyMatch(m -> m.contains("agents[a1].systemPrompt"))
                .anyMatch(m -> m.contains("agents[a1].reasoningDepth"))
                .anyMatch(m -> m.contains("agents[a1].tuningMode"))
                .anyMatch(m -> m.contains("generation.maxTokens"))
                .anyMatch(m -> m.contains("Empty tool entry"))
                .anyMatch(m -> m.contains("'m9' is not a MCP server ref"))
                .anyMatch(m -> m.contains("'t9' is not a tool ref"))
                .anyMatch(m -> m.contains("neither a ref nor a built-in tool key"))
                .anyMatch(m -> m.contains("procedureRefs"))
                .anyMatch(m -> m.contains("guardrails[0]"))
                .anyMatch(m -> m.contains("guardrails[1]"));
        assertThat(errors).noneMatch(m -> m.contains("web_search"));
    }

    @Test
    void aBriefNeedsBetweenOneAndSixAgents() {
        assertThat(errorsOf(withAgents())).anyMatch(m -> m.startsWith("agents:"));
        AgentSpec[] seven = new AgentSpec[7];
        for (int i = 0; i < 7; i++) {
            seven[i] = agent("a" + (i + 1), "N" + i, "p", "FAST");
        }
        assertThat(errorsOf(withAgents(seven))).anyMatch(m -> m.startsWith("agents:"));
    }

    @Test
    void procedureRulesCoverNameStepsSourceAndTheCap() {
        BriefFixtures.B b = BriefFixtures.valid();
        List<ProcedureSpec> procedures = new ArrayList<>();
        procedures.add(new ProcedureSpec("p1", " ", List.of(), "MAGIC"));
        procedures.add(
                new ProcedureSpec("p2", "Fine", java.util.Arrays.asList("step", " "), "PLANNER"));
        procedures.add(new ProcedureSpec("p3", "Fine", List.of("step"), "USER_GOAL"));
        for (int i = 4; i <= 7; i++) {
            procedures.add(new ProcedureSpec("p" + i, "Fine", List.of("s"), null));
        }
        Brief built = b.build();
        Brief withProcedures =
                new Brief(
                        1,
                        built.title(),
                        built.goal(),
                        built.locale(),
                        List.of(),
                        built.trust(),
                        built.agents(),
                        procedures,
                        built.workflows(),
                        built.tools(),
                        built.mcpServers(),
                        built.knowledge(),
                        built.secrets(),
                        built.channels(),
                        built.evalSet(),
                        built.rehearsal(),
                        built.narrative());

        List<String> errors = errorsOf(withProcedures);

        assertThat(errors)
                .anyMatch(m -> m.startsWith("procedures:"))
                .anyMatch(m -> m.startsWith("procedures[p1].name"))
                .anyMatch(m -> m.startsWith("procedures[p1].steps"))
                .anyMatch(m -> m.startsWith("procedures[p1].source"))
                .anyMatch(m -> m.startsWith("procedures[p2].steps"));
    }

    @Test
    void workflowRulesCoverNameSampleInputAndTriggers() {
        BriefFixtures.B b = BriefFixtures.valid();
        WorkflowSpec base = BriefFixtures.workflow("w1", "a1", "k1");
        b.workflows =
                new ArrayList<>(
                        List.of(
                                new WorkflowSpec(
                                        "w1",
                                        " ",
                                        base.graph(),
                                        java.util.Arrays.asList(
                                                new Trigger("PIGEON", null, null),
                                                new Trigger("SCHEDULE", " ", null),
                                                new Trigger("CHANNEL", null, "c9"),
                                                null,
                                                new Trigger("MANUAL", null, null)),
                                        "s".repeat(2001))));

        List<String> errors = errorsOf(b.build());

        assertThat(errors)
                .anyMatch(m -> m.startsWith("workflows[w1].name"))
                .anyMatch(m -> m.startsWith("workflows[w1].sampleInput"))
                .anyMatch(m -> m.startsWith("workflows[w1].triggers[0].kind"))
                .anyMatch(m -> m.startsWith("workflows[w1].triggers[1].cron"))
                .anyMatch(m -> m.startsWith("workflows[w1].triggers[2].channelRef"))
                .anyMatch(m -> m.startsWith("workflows[w1].triggers[3].kind"));
    }

    @Test
    void aWorkflowWithoutNodesIsRejectedAndMoreThanFourAreTooMany() {
        BriefFixtures.B b = BriefFixtures.valid();
        List<WorkflowSpec> many = new ArrayList<>();
        many.add(new WorkflowSpec("w1", "Empty", null, List.of(), null));
        for (int i = 2; i <= 5; i++) {
            many.add(BriefFixtures.workflow("w" + i, "a1", "k1"));
        }
        b.workflows = many;

        assertThat(errorsOf(b.build()))
                .anyMatch(m -> m.startsWith("workflows:"))
                .anyMatch(m -> m.startsWith("workflows[w1].graph"));
    }
}
