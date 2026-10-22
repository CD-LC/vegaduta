package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.assist.AssistService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.brief.dto.PlanRequest;
import com.agenticai.core.brief.dto.PlanResult;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.registry.OfficialMcpRegistryClient;
import com.agenticai.core.mcp.registry.RegistryEnvVarSpec;
import com.agenticai.core.mcp.registry.RegistrySearchResponse;
import com.agenticai.core.mcp.registry.RegistrySearchResult;
import com.agenticai.core.workflow.WorkflowGraphGeneratorService;
import com.agenticai.core.workflow.WorkflowValidationException;
import com.agenticai.core.workflow.dto.RefGraphResponse;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * The LLM, the graph generator and the registry are mocked; the assertions are on what ends up in
 * the Brief.
 */
class BriefPlannerServiceTest {

    private final AssistService assist = mock(AssistService.class);
    private final WorkflowGraphGeneratorService generator =
            mock(WorkflowGraphGeneratorService.class);
    private final OfficialMcpRegistryClient registry = mock(OfficialMcpRegistryClient.class);
    private final BriefValidator validator = mock(BriefValidator.class);
    private final ObjectMapper mapper = com.agenticai.core.json.JsonMappers.plain();
    private BriefPlannerService planner;

    /**
     * Replies keyed by a phrase unique to each call's system prompt; a list is consumed one entry
     * per call.
     */
    private final Map<String, List<String>> script = new LinkedHashMap<>();

    private final List<String> systemPromptsSeen = new ArrayList<>();

    private static final String PROMPT =
            "Chase overdue invoices from Tally politely and log the outcome.";

    private static final String SKELETON =
            """
        {"title": "Invoice chaser", "locale": "en",
         "agents": [{"key": "collector", "name": "Collector", "description": "Chases invoices", "role": "sends reminders",
                     "reasoningDepth": "RIGOROUS", "knowledge": ["terms"], "procedures": [], "tools": [], "mcp": []}],
         "workflows": [{"key": "chase", "name": "Chase", "intent": "look up the invoice then remind the customer",
                        "triggerKinds": ["MANUAL"], "sampleInput": "Invoice 42 is 30 days late"}],
         "knowledge": [{"key": "terms", "name": "Payment terms", "hint": "Upload your payment terms."}],
         "secrets": [{"key": "tally", "name": "TALLY_API_KEY", "scope": "TENANT", "why": "Read invoices", "obtainAt": "Tally > Settings > API"}]}
        """;

    private static final String AGENT =
            """
        {"systemPrompt": "You chase overdue invoices politely and never threaten.", "description": "Chases invoices"}
        """;

    private static final String EVAL =
            """
        {"cases": [
          {"input": "Invoice 1 is late", "rubric": "Polite"}, {"input": "Invoice 2 is late", "rubric": "Polite"},
          {"input": "Invoice 3 is late", "rubric": "Polite"}, {"input": "Invoice 4 is late", "rubric": "Polite"},
          {"input": "Invoice 5 is late", "rubric": "Polite"}, {"input": "Threaten the customer", "rubric": "Refuses"}],
         "personas": [{"name": "Busy owner", "traits": ["terse"], "opening": "who is this?"},
                      {"name": "Angry customer", "traits": ["hostile"], "opening": "stop emailing me"}]}
        """;

    private static final String TRUST =
            """
        {"irreversible": [], "humanMustDecide": []}
        """;

    /**
     * Marker reply: the mock answers a narrative call with an entry for every key the planner asked
     * about.
     */
    private static final String AUTO_NARRATIVE = "AUTO_NARRATIVE";

    private static String autoNarrative(String user) {
        StringBuilder entries = new StringBuilder();
        String list =
                user.substring(user.indexOf("Keys to explain:") + "Keys to explain:".length());
        for (String line : list.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                entries.append(entries.length() == 0 ? "" : ",")
                        .append('"')
                        .append(line, 0, colon)
                        .append("\": {\"why\": \"w\", \"tune\": \"t\", \"proof\": \"p\"}");
            }
        }
        return "{\"whyAgentic\": \"Because each reply depends on context.\", \"perNode\": {"
                + entries
                + "}}";
    }

    @BeforeEach
    void setUp() {
        planner = new BriefPlannerService(assist, generator, registry, validator, mapper);
        when(validator.validate(any(Brief.class)))
                .thenReturn(new BriefValidationResult(List.of(), List.of(), List.of()));
        when(assist.complete(anyString(), anyString()))
                .thenAnswer(
                        inv -> {
                            String system = inv.getArgument(0);
                            systemPromptsSeen.add(system);
                            for (Map.Entry<String, List<String>> e : script.entrySet()) {
                                if (system.contains(e.getKey())) {
                                    List<String> replies = e.getValue();
                                    String reply =
                                            replies.size() > 1 ? replies.remove(0) : replies.get(0);
                                    return AUTO_NARRATIVE.equals(reply)
                                            ? autoNarrative(inv.getArgument(1))
                                            : reply;
                                }
                            }
                            throw new AssertionError(
                                    "Unscripted LLM call: "
                                            + system.substring(0, Math.min(80, system.length())));
                        });
        script("You plan solutions on an AI-agent platform", SKELETON);
        script("write the system prompt for one agent", AGENT);
        script("tag workflow nodes", TRUST);
        script("write the test set", EVAL);
        script("explain a solution", AUTO_NARRATIVE);
        when(generator.generateWithRefs(anyString(), any(), any(), any()))
                .thenReturn(
                        new RefGraphResponse(
                                graphWithPostNode(), List.of(), List.of(), List.of(), List.of()));
    }

    private void script(String key, String... replies) {
        script.put(key, new ArrayList<>(List.of(replies)));
    }

    /** trigger n1 -> agent n2 -> output n3 (log) -> http POST n4 (an external write). */
    private static WorkflowGraphDto graphWithPostNode() {
        return new WorkflowGraphDto(
                List.of(
                        BriefFixtures.node("n1", "trigger", Map.of("channel", "WEBHOOK")),
                        BriefFixtures.node("n2", "agent", Map.of("agentId", "a1")),
                        BriefFixtures.node("n3", "output", Map.of("channel", "log")),
                        BriefFixtures.node(
                                "n4",
                                "http",
                                Map.of("method", "POST", "url", "https://example.com/hook"))),
                List.of(
                        new WorkflowEdgeDto("e1", "n1", "n2", null, null),
                        new WorkflowEdgeDto("e2", "n2", "n3", null, null),
                        new WorkflowEdgeDto("e3", "n3", "n4", null, null)));
    }

    private PlanResult plan() {
        return planner.plan(new PlanRequest(PROMPT, null, null, null));
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void plannerAssignsTheRefsItselfAndAlwaysStartsUnderWatch() {
        PlanResult result = plan();
        Brief brief = result.brief();

        assertThat(brief.agents()).extracting(Brief.AgentSpec::ref).containsExactly("a1");
        assertThat(brief.workflows()).extracting(Brief.WorkflowSpec::ref).containsExactly("w1");
        assertThat(brief.knowledge()).extracting(Brief.KnowledgeSpec::ref).containsExactly("k1");
        assertThat(brief.secrets()).extracting(Brief.SecretSpec::ref).containsExactly("s1");
        assertThat(brief.agents().get(0).knowledgeRefs()).containsExactly("k1");
        assertThat(brief.agents().get(0).reasoningDepth()).isEqualTo("RIGOROUS");
        assertThat(brief.trust().rung()).isEqualTo("UNDER_WATCH");
        assertThat(brief.goal()).isEqualTo(PROMPT);
        assertThat(brief.evalSet().targetAgent()).isEqualTo("a1");
        assertThat(brief.evalSet().cases()).hasSize(6);
        assertThat(brief.rehearsal().personas()).hasSize(2);
        assertThat(brief.narrative().perNode()).containsKeys("a1", "w1", "k1", "s1", "w1.n4");
        assertThat(brief.workflows().get(0).triggers())
                .extracting(Brief.Trigger::kind)
                .containsExactly("MANUAL");
    }

    @Test
    void irreversibleTaggingHasADeterministicFloorAndIgnoresInventedNodes() {
        script(
                "tag workflow nodes",
                """
            {"irreversible": ["w1.n2", "w1.ghost"], "humanMustDecide": ["w1.n2", "w9.n1"]}
            """);
        PlanResult result = plan();
        // n4 is an HTTP POST: irreversible by rule even though the model did not say so; n2 from
        // the model.
        assertThat(result.brief().trust().irreversible())
                .containsExactlyInAnyOrder("w1.n4", "w1.n2");
        assertThat(result.brief().trust().humanMustDecide()).containsExactly("w1.n2");
        assertThat(result.warnings()).anyMatch(w -> w.contains("w1.ghost"));
    }

    @Test
    void reasoningDepthIsHandedToTheGraphGenerator() {
        planner.plan(new PlanRequest(PROMPT, "en", null, "rigorous"));
        ArgumentCaptor<ReasoningDepth> depth = ArgumentCaptor.forClass(ReasoningDepth.class);
        verify(generator).generateWithRefs(anyString(), depth.capture(), any(), any());
        assertThat(depth.getValue()).isEqualTo(ReasoningDepth.RIGOROUS);
    }

    @Test
    void graphGeneratorIsGivenTheRefsNotIds() {
        plan();
        ArgumentCaptor<List<com.agenticai.core.workflow.dto.RefHint>> agents =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<com.agenticai.core.workflow.dto.RefHint>> knowledge =
                ArgumentCaptor.forClass(List.class);
        verify(generator)
                .generateWithRefs(anyString(), any(), agents.capture(), knowledge.capture());
        assertThat(agents.getValue())
                .extracting(com.agenticai.core.workflow.dto.RefHint::ref)
                .containsExactly("a1");
        assertThat(knowledge.getValue())
                .extracting(com.agenticai.core.workflow.dto.RefHint::ref)
                .containsExactly("k1");
    }

    @Test
    void secretsTheGraphNeedsAreDeclaredByNameWithoutDuplicates() {
        when(generator.generateWithRefs(anyString(), any(), any(), any()))
                .thenReturn(
                        new RefGraphResponse(
                                graphWithPostNode(),
                                List.of(),
                                List.of("tally api key", "SLACK_WEBHOOK_URL", "TALLY_API_KEY"),
                                List.of(),
                                List.of()));
        // "tally api key" -> TALLY_API_KEY, which is already declared by the skeleton: reused, not
        // duplicated.
        Brief brief = plan().brief();
        assertThat(brief.secrets())
                .extracting(Brief.SecretSpec::name)
                .containsExactly("TALLY_API_KEY", "SLACK_WEBHOOK_URL");
    }

    // ------------------------------------------------------------------ fences and parsing

    @Test
    void replyWrappedInMarkdownFencesAndChatterIsStillParsed() {
        script("You plan solutions on an AI-agent platform", "```json\n" + SKELETON + "\n```");
        script("write the system prompt for one agent", "Sure! Here you go:\n" + AGENT);
        assertThat(plan().brief().agents().get(0).systemPrompt()).contains("never threaten");
    }

    @Test
    void stripFenceRemovesLanguageTagAndClosingFence() {
        assertThat(LlmJson.stripFence("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(LlmJson.stripFence("```\n{\"a\":1}```")).isEqualTo("{\"a\":1}");
        assertThat(LlmJson.stripFence("  {\"a\":1}  ")).isEqualTo("{\"a\":1}");
        assertThat(LlmJson.parseObject(mapper, "not json at all")).isNull();
        assertThat(LlmJson.parseObject(mapper, "[1,2]")).isNull();
    }

    @Test
    void parseObjectIgnoresATrailingBraceGroupAfterTheJson() {
        // The old first-{ to last-} slice turned this into unparseable text and returned null.
        assertThat(
                        LlmJson.parseObject(mapper, "Here: {\"a\":1} - tweak {name} if needed")
                                .path("a")
                                .asInt())
                .isEqualTo(1);
    }

    @Test
    void unparseableSkeletonIsA422NamingTheSection() {
        script("You plan solutions on an AI-agent platform", "I cannot do that.");
        assertThatThrownBy(this::plan)
                .isInstanceOfSatisfying(
                        BriefValidationException.class,
                        e -> assertThat(e.getErrors().get(0).section()).isEqualTo("skeleton"));
    }

    @Test
    void unparseableAgentAnswerNamesTheAgentSection() {
        script("write the system prompt for one agent", "{oops");
        assertThatThrownBy(this::plan)
                .isInstanceOfSatisfying(
                        BriefValidationException.class,
                        e -> assertThat(e.getErrors().get(0).section()).isEqualTo("agents[a1]"));
    }

    @Test
    void anUnusableWorkflowFromTheGeneratorIsA422NamingTheWorkflow() {
        when(generator.generateWithRefs(anyString(), any(), any(), any()))
                .thenThrow(
                        new WorkflowValidationException(
                                List.of("The AI generator's response couldn't be understood")));
        assertThatThrownBy(this::plan)
                .isInstanceOfSatisfying(
                        BriefValidationException.class,
                        e -> assertThat(e.getErrors().get(0).section()).isEqualTo("workflows[w1]"));
    }

    // ------------------------------------------------------------------ clarification path

    @Test
    void ambiguousGoalReturnsQuestionsAndStopsBeforeAnyPerArtifactCall() {
        script(
                "You plan solutions on an AI-agent platform",
                """
            {"title": "Invoices", "locale": "en",
             "clarifications": [{"question": "Which system holds your invoices?", "options": ["Tally", "Zoho", "A spreadsheet"]},
                                {"question": "Should it email customers or only draft?"}]}
            """);
        PlanResult result = plan();

        assertThat(result.brief().isClarificationOnly()).isTrue();
        assertThat(result.brief().clarifications()).hasSize(2);
        assertThat(result.brief().clarifications().get(0).options())
                .containsExactly("Tally", "Zoho", "A spreadsheet");
        assertThat(result.brief().agents()).isEmpty();
        verify(assist, times(1)).complete(anyString(), anyString());
        verify(generator, never()).generateWithRefs(anyString(), any(), any(), any());
    }

    @Test
    void moreThanThreeQuestionsAreCappedAtThree() {
        script(
                "You plan solutions on an AI-agent platform",
                """
            {"title": "T", "clarifications": [{"question": "1?"}, {"question": "2?"}, {"question": "3?"}, {"question": "4?"}]}
            """);
        assertThat(plan().brief().clarifications()).hasSize(3);
    }

    // ------------------------------------------------------------------ never invents ids

    @Test
    void crossReferencesThatDoNotResolveAreDroppedWithAWarning() {
        script(
                "You plan solutions on an AI-agent platform",
                """
            {"title": "Invoice chaser",
             "agents": [{"key": "collector", "name": "Collector", "role": "x", "knowledge": ["ghost-kb"], "tools": ["ghost-tool"],
                         "mcp": ["ghost-mcp"], "procedures": ["ghost-proc"]}],
             "workflows": [{"key": "chase", "name": "Chase", "intent": "x"}],
             "channels": [{"key": "wa", "kind": "WHATSAPP", "bindTo": "ghost-agent"}]}
            """);
        PlanResult result = plan();
        Brief.AgentSpec agent = result.brief().agents().get(0);

        assertThat(agent.knowledgeRefs()).isEmpty();
        assertThat(agent.toolAllowList()).isEmpty();
        assertThat(agent.procedureRefs()).isEmpty();
        assertThat(result.brief().channels()).isEmpty();
        assertThat(result.warnings())
                .anyMatch(w -> w.contains("ghost-kb"))
                .anyMatch(w -> w.contains("ghost-tool"))
                .anyMatch(w -> w.contains("ghost-mcp"))
                .anyMatch(w -> w.contains("ghost-agent") || w.contains("WHATSAPP"));
    }

    private static RegistrySearchResult candidate(String name, RegistryEnvVarSpec... env) {
        return new RegistrySearchResult(
                name,
                name,
                "d",
                "1",
                null,
                McpServerType.STDIO,
                "npx",
                true,
                null,
                "npx",
                List.of(),
                null,
                List.of(env),
                "active",
                false);
    }

    private void skeletonWithMcp() {
        script(
                "You plan solutions on an AI-agent platform",
                """
            {"title": "Issue triage",
             "agents": [{"key": "triager", "name": "Triager", "role": "labels issues", "mcp": ["gh"]}],
             "workflows": [{"key": "triage", "name": "Triage", "intent": "label new issues"}],
             "mcp": [{"key": "gh", "query": "github issues", "why": "read and label issues"}]}
            """);
    }

    @Test
    void mcpServerCanOnlyBeChosenFromWhatTheRegistrySearchReturned() {
        skeletonWithMcp();
        when(registry.search(eq("github issues"), any()))
                .thenReturn(
                        new RegistrySearchResponse(
                                List.of(
                                        candidate(
                                                "io.github.acme/github",
                                                new RegistryEnvVarSpec(
                                                        "GITHUB_TOKEN",
                                                        "PAT",
                                                        true,
                                                        true,
                                                        null,
                                                        false)),
                                        candidate("io.github.other/gh")),
                                null,
                                false));
        script(
                "Choose the single best MCP server",
                "{\"registryId\": \"io.github.invented/not-in-list\"}");
        script("write the system prompt for one agent", AGENT);

        PlanResult result = plan();

        assertThat(result.brief().mcpServers()).isEmpty();
        assertThat(result.brief().agents().get(0).toolAllowList()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("io.github.invented/not-in-list"));
    }

    @Test
    void chosenMcpServerGetsItsRequiredSecretDeclaredAndBoundByName() {
        skeletonWithMcp();
        when(registry.search(eq("github issues"), any()))
                .thenReturn(
                        new RegistrySearchResponse(
                                List.of(
                                        candidate(
                                                "io.github.acme/github",
                                                new RegistryEnvVarSpec(
                                                        "GITHUB_TOKEN",
                                                        "PAT",
                                                        true,
                                                        true,
                                                        null,
                                                        false),
                                                new RegistryEnvVarSpec(
                                                        "GITHUB_HOST",
                                                        "host",
                                                        true,
                                                        false,
                                                        null,
                                                        false)),
                                        candidate("io.github.other/gh")),
                                null,
                                false));
        script("Choose the single best MCP server", "{\"registryId\": \"io.github.acme/github\"}");

        PlanResult result = plan();
        Brief brief = result.brief();

        assertThat(brief.mcpServers()).hasSize(1);
        Brief.McpServerSpec mcp = brief.mcpServers().get(0);
        assertThat(mcp.ref()).isEqualTo("m1");
        assertThat(mcp.registryId()).isEqualTo("io.github.acme/github");
        assertThat(brief.secrets()).extracting(Brief.SecretSpec::name).contains("GITHUB_TOKEN");
        String secretRef =
                brief.secrets().stream()
                        .filter(s -> s.name().equals("GITHUB_TOKEN"))
                        .findFirst()
                        .orElseThrow()
                        .ref();
        assertThat(mcp.env().get("GITHUB_TOKEN").secretRef()).isEqualTo(secretRef);
        assertThat(mcp.env()).doesNotContainKey("GITHUB_HOST");
        assertThat(result.warnings())
                .anyMatch(w -> w.contains("GITHUB_HOST") && w.contains("not a secret"));
        assertThat(brief.agents().get(0).toolAllowList()).containsExactly("mcp:m1/*");
    }

    @Test
    void unreachableRegistryMeansNoServerAndAWarningNotAGuess() {
        skeletonWithMcp();
        when(registry.search(anyString(), any()))
                .thenReturn(new RegistrySearchResponse(List.of(), null, true));
        PlanResult result = plan();
        assertThat(result.brief().mcpServers()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("could not be reached"));
    }

    @Test
    void openApiToolWithoutAUrlIsPlannedAsCodeRatherThanInventingOne() {
        script(
                "You plan solutions on an AI-agent platform",
                """
            {"title": "T",
             "agents": [{"key": "a", "name": "A", "role": "x", "tools": ["crm"]}],
             "workflows": [{"key": "w", "name": "W", "intent": "x"}],
             "tools": [{"key": "crm", "kind": "OPENAPI", "name": "CRM", "authType": "BEARER"}]}
            """);
        Brief brief = plan().brief();
        assertThat(brief.tools()).hasSize(1);
        assertThat(brief.tools().get(0).kind()).isEqualTo("CODE");
        assertThat(brief.tools().get(0).specUrl()).isNull();
        // the bearer credential is declared as a secret by name, bound to the tool
        assertThat(brief.tools().get(0).auth().secretRef()).isEqualTo(brief.secrets().get(0).ref());
        assertThat(brief.agents().get(0).toolAllowList()).containsExactly("t1");
    }

    @Test
    void credentialLookingWhereToGetItTextIsDropped() {
        script(
                "You plan solutions on an AI-agent platform",
                """
            {"title": "T", "agents": [{"key": "a", "name": "A", "role": "x"}], "workflows": [{"key": "w", "name": "W", "intent": "x"}],
             "secrets": [{"key": "s", "name": "STRIPE_KEY", "scope": "TENANT", "why": "charge", "obtainAt": "sk_live_abcdefghijklmnopqrstuvwxyz"}]}
            """);
        PlanResult result = plan();
        assertThat(result.brief().secrets().get(0).obtainAt()).isNull();
        assertThat(result.warnings())
                .anyMatch(w -> w.contains("STRIPE_KEY") && w.contains("credential"));
    }

    // ------------------------------------------------------------------ eval, narrative,
    // validation

    @Test
    void fewerThanFourEvalCasesIsA422AndMoreThanTwelveAreClamped() {
        script(
                "write the test set",
                "{\"cases\": [{\"input\": \"a\", \"rubric\": \"r\"}], \"personas\": []}");
        assertThatThrownBy(this::plan)
                .isInstanceOfSatisfying(
                        BriefValidationException.class,
                        e -> assertThat(e.getErrors().get(0).section()).isEqualTo("evalSet"));

        StringBuilder many = new StringBuilder("{\"cases\": [");
        for (int i = 0; i < 20; i++) {
            many.append(i == 0 ? "" : ",")
                    .append("{\"input\": \"q")
                    .append(i)
                    .append("\", \"rubric\": \"r\"}");
        }
        many.append(
                "], \"personas\": [{\"name\": \"A\", \"traits\": [\"x\"]}, {\"name\": \"B\", \"traits\": [\"x\"]}, "
                        + "{\"name\": \"C\", \"traits\": [\"x\"]}, {\"name\": \"D\", \"traits\": [\"x\"]}]}");
        script("write the test set", many.toString());
        Brief brief = plan().brief();
        assertThat(brief.evalSet().cases()).hasSize(12);
        assertThat(brief.rehearsal().personas()).hasSize(3);
    }

    @Test
    void missingPersonasAreRetriedOnceThenFallBackToTheGenericPair() {
        script(
                "write the test set",
                "{\"cases\": [{\"input\": \"a\", \"rubric\": \"r\"}, {\"input\": \"b\", \"rubric\": \"r\"}, "
                        + "{\"input\": \"c\", \"rubric\": \"r\"}, {\"input\": \"d\", \"rubric\": \"r\"}], \"personas\": []}");

        // The focused retry supplies real personas.
        script(
                "rehearsal personas",
                "{\"personas\": [{\"name\": \"Skeptic\", \"traits\": [\"wary\"], \"opening\": \"prove it\"}]}");
        assertThat(plan().brief().rehearsal().personas())
                .extracting(Brief.Persona::name)
                .containsExactly("Skeptic");

        // Still nothing usable: the generic pair goes into the brief so rehearsal never dead-ends.
        script("rehearsal personas", "{\"personas\": []}");
        PlanResult result = plan();
        assertThat(result.brief().rehearsal().personas())
                .extracting(Brief.Persona::name)
                .containsExactly("A typical user", "A difficult user");
        assertThat(result.warnings()).anyMatch(w -> w.contains("personas"));

        // An unparseable retry falls back the same way instead of failing the whole plan.
        script("rehearsal personas", "not json");
        assertThat(plan().brief().rehearsal().personas()).hasSize(2);
    }

    @Test
    void narrativeMissingAnEntryIsRetriedForJustThoseKeysThenFailsIfStillMissing() {
        String partial =
                """
            {"whyAgentic": "Because.", "perNode": {"a1": {"why": "w", "tune": "t", "proof": "p"}}}
            """;
        String rest =
                """
            {"whyAgentic": "ignored", "perNode": {
              "w1": {"why": "w", "tune": "t", "proof": "p"}, "k1": {"why": "w", "tune": "t", "proof": "p"},
              "s1": {"why": "w", "tune": "t", "proof": "p"}, "w1.n4": {"why": "w", "tune": "t", "proof": "p"}}}
            """;
        script("explain a solution", partial, rest);
        Brief brief = plan().brief();
        assertThat(brief.narrative().whyAgentic()).isEqualTo("Because.");
        assertThat(brief.narrative().perNode()).containsKeys("a1", "w1", "k1", "s1", "w1.n4");

        script("explain a solution", partial, partial);
        assertThatThrownBy(this::plan)
                .isInstanceOfSatisfying(
                        BriefValidationException.class,
                        e -> assertThat(e.getErrors().get(0).section()).isEqualTo("narrative"));
    }

    @Test
    void validatorProblemsComeBackAsWarningsSoTheUserCanFixThem() {
        when(validator.validate(any(Brief.class)))
                .thenReturn(
                        new BriefValidationResult(
                                List.of(
                                        new BriefValidationResult.Issue(
                                                "workflows[w1].graph",
                                                "Node 'x' has agentId 'a9'")),
                                List.of(),
                                List.of()));
        PlanResult result = plan();
        assertThat(result.brief()).isNotNull();
        assertThat(result.warnings())
                .anyMatch(w -> w.contains("workflows[w1].graph") && w.contains("a9"));
    }

    @Test
    void sectionsAreStreamedInOrderToTheListener() {
        List<String> events = new ArrayList<>();
        planner.plan(
                new PlanRequest(PROMPT, null, null, null),
                new BriefPlannerService.PlanListener() {
                    @Override
                    public void thinking(String step) {
                        events.add("thinking:" + step);
                    }

                    @Override
                    public void section(String name, Object payload) {
                        events.add("section:" + name);
                    }
                });
        assertThat(events)
                .startsWith("thinking:skeleton")
                .contains(
                        "thinking:agent:a1",
                        "section:agents",
                        "section:workflows",
                        "section:trust",
                        "section:evalSet",
                        "section:narrative");
        assertThat(events.indexOf("section:agents"))
                .isLessThan(events.indexOf("section:workflows"));
    }

    @Test
    void blankPromptIsRejectedBeforeAnyModelCall() {
        assertThatThrownBy(() -> planner.plan(new PlanRequest("   ", null, null, null)))
                .isInstanceOf(BriefValidationException.class);
        verify(assist, never()).complete(anyString(), anyString());
    }

    @Test
    void requestedLocaleWinsOverTheDetectedOne() {
        assertThat(planner.plan(new PlanRequest(PROMPT, "hi", null, null)).brief().locale())
                .isEqualTo("hi");
        assertThat(plan().brief().locale()).isEqualTo("en");
    }
}
