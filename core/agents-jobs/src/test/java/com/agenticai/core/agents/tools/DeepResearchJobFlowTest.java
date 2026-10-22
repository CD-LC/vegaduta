package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.collab.RunBudget;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.job.output.JobResultSchemaValidator;
import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.job.progress.JobProgressRecorder;
import com.agenticai.core.agents.job.progress.ProgressSource;
import com.agenticai.core.agents.research.ResearchRunPlan;
import com.agenticai.core.agents.tools.web.BraveWebSearchClient;
import com.agenticai.core.agents.tools.web.DeepResearchConfig;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A research run's tool path, end to end, with no network and no model: the tools are resolved
 * exactly as a job turn resolves them (job session id, the research agent's allow-list, {@code
 * Mode.TURN}), and this test plays the model - deep_research, then verify_citations, then the
 * structured answer.
 *
 * <p>What it pins: both tools resolve for the research agent without a research-looking message; a
 * second resolution in the same turn shares the evidence session, so verify_citations sees what
 * deep_research read; the search and page-read ceilings hold across calls; the run-budget gate
 * refuses once the budget is spent; progress notes land on the job's trail; and an answer built
 * from the tool output satisfies the research result schema.
 *
 * <p>What it does NOT cover: {@code AgentJobExecution}, {@code AgentChatService}, the
 * guard/approval wrap ({@code guardAndLog} is a pass-through here) and a real model following the
 * system prompt.
 */
class DeepResearchJobFlowTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String PAGE =
            "Intro text here. Solid-state batteries reach 400 Wh/kg in lab cells. Other sentence follows.";
    private static final String QUOTE = "Solid-state batteries reach 400 Wh/kg in lab cells.";
    private static final String CLAIM = "Lab cells of solid-state batteries reach 400 Wh/kg.";

    private final AtomicInteger searches = new AtomicInteger();
    private final AtomicInteger fetches = new AtomicInteger();
    private final UUID agentId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final String jobSession = AgentJobSession.sessionIdFor(jobId);
    private final String goal =
            ResearchRunPlan.goal(
                    new ResearchRunPlan.Validated(
                            "battery progress", "comparison", List.of(), null));

    private AgentIntegrationToolResolver resolver;
    private AgentJobProgressService progress;
    private TurnRunBudgets budgets;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        AgentToolGuard toolGuard = mock(AgentToolGuard.class);
        when(toolGuard.guardAndLog(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        // Same decision the real AgentToolGuard#withRunBudget makes.
        when(toolGuard.withRunBudget(any(), any(), anyBoolean()))
                .thenAnswer(
                        inv -> {
                            ToolCallback tool = inv.getArgument(0);
                            RunBudget budget = inv.getArgument(1);
                            boolean recordUsage = inv.getArgument(2);
                            return budget == null || !budget.isEnabled()
                                    ? tool
                                    : new RunBudgetToolCallback(tool, budget, recordUsage);
                        });

        PlatformToolCredentialService credentials = mock(PlatformToolCredentialService.class);
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        ObjectProvider<PlatformToolCredentialService> credentialProvider =
                mock(ObjectProvider.class);
        when(credentialProvider.getIfAvailable()).thenReturn(credentials);

        // The research agent: an allow-list of exactly the four research tools.
        AgentService agents = mock(AgentService.class);
        when(agents.getAllowedToolNames(any())).thenReturn(ResearchRunPlan.AGENT_TOOLS);

        budgets =
                new TurnRunBudgets(
                        () ->
                                new RunBudget(
                                        new BigDecimal("0.01"),
                                        new BigDecimal("3"),
                                        new BigDecimal("15"),
                                        8000));

        // Tight ceilings so they are reachable: 3 page reads and 2 searches per turn.
        DeepResearchConfig config = new DeepResearchConfig(true, 6, 8, 12000, 3, 2, 6, 45, 2);
        // The web tools are core/agents-browser's BuiltInWebTools behind the kernel's WebToolPort.
        BuiltInWebTools webTools =
                new BuiltInWebTools(agents, credentialProvider, mock(ObjectProvider.class), config);
        ObjectProvider<WebToolPort> port = mock(ObjectProvider.class);
        when(port.getIfAvailable()).thenReturn(webTools);
        resolver =
                new AgentIntegrationToolResolver(
                        toolGuard,
                        agents,
                        List.of(),
                        null,
                        null,
                        mock(ObjectProvider.class),
                        port,
                        mock(ObjectProvider.class),
                        budgets::forTurn);
        // Intent offer OFF: the tools must come from the agent's opt-in, not from the wording of
        // the goal.
        webTools.applyResearchTuning(120, 20, false);
        webTools.setResearchTransportForTest(
                (query, count) -> {
                    searches.incrementAndGet();
                    String slug = query.replace(' ', '-');
                    return List.of(
                            new BraveWebSearchClient.SearchResult(
                                    "One " + query, "https://example.com/" + slug + "/1", "d"),
                            new BraveWebSearchClient.SearchResult(
                                    "Two " + query, "https://example.com/" + slug + "/2", "d"));
                },
                (url, maxChars) -> {
                    fetches.incrementAndGet();
                    return PAGE;
                });

        progress = mock(AgentJobProgressService.class);
        ObjectProvider<JobProgressRecorder> progressProvider = mock(ObjectProvider.class);
        when(progressProvider.getIfAvailable()).thenReturn(progress);
        webTools.research().setJobProgress(progressProvider);
    }

    private List<ToolCallback> resolve(AtomicInteger turn) {
        return resolver.resolveWebTools(agentId, jobSession, turn, goal, null, false);
    }

    private static ToolCallback tool(List<ToolCallback> tools, String name) {
        return tools.stream()
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static String citations(String sourceId) {
        return "{\"citations\":[{\"sourceId\":\""
                + sourceId
                + "\",\"quote\":\""
                + QUOTE
                + "\",\"claim\":\""
                + CLAIM
                + "\"}]}";
    }

    @Test
    void aResearchRunsToolPathHoldsEndToEnd() throws Exception {
        AtomicInteger turn = new AtomicInteger();

        // 1. Both tools resolve in a job turn for the research agent.
        List<ToolCallback> tools = resolve(turn);
        List<String> names = tools.stream().map(t -> t.getToolDefinition().name()).toList();
        assertTrue(
                names.containsAll(
                        List.of(
                                "web_search",
                                "fetch_web_page",
                                "deep_research",
                                "verify_citations")),
                names.toString());

        // 2. deep_research: two angles, four candidate pages, but only three page reads allowed
        // this turn.
        JsonNode research =
                MAPPER.readTree(
                        tool(tools, "deep_research")
                                .call(
                                        "{\"question\":\"battery progress\",\"subQuestions\":[\"solid state battery\",\"lithium battery\"],"
                                                + "\"effort\":\"comparison\"}"));
        assertEquals(2, research.get("workers").asInt());
        assertEquals(3, research.get("sources").size());
        assertEquals(2, searches.get());
        assertEquals(3, fetches.get());
        assertEquals(2, research.get("searchesUsed").asInt());
        assertEquals(2, research.get("searchLimit").asInt());
        assertEquals(3, research.get("fetchesUsed").asInt());
        assertEquals(3, research.get("fetchLimit").asInt());
        assertEquals("S1", research.get("sources").get(0).get("id").asString());

        // 3. Progress went to THIS job's trail, in phase order, as platform (RUNTIME) notes.
        InOrder trail = inOrder(progress);
        trail.verify(progress).record(jobId, "Searching 2 angles", null, ProgressSource.RUNTIME);
        trail.verify(progress)
                .record(jobId, "Found 4 candidate pages, reading 3", null, ProgressSource.RUNTIME);
        trail.verify(progress)
                .record(jobId, "Read 3 of 3 pages (0 failed)", null, ProgressSource.RUNTIME);
        trail.verify(progress).record(jobId, "Extracting quotes", null, ProgressSource.RUNTIME);
        trail.verify(progress)
                .record(jobId, "Used 2/2 searches, 3/3 page reads", null, ProgressSource.RUNTIME);

        // 4. A second resolution in the SAME turn shares the session: verify_citations sees the
        // evidence.
        List<ToolCallback> again = resolve(turn);
        JsonNode verified = MAPPER.readTree(tool(again, "verify_citations").call(citations("S1")));
        assertEquals(1, verified.get("verified").asInt());
        assertEquals("SUPPORTED", verified.get("results").get(0).get("verdict").asString());

        // 5. Ceilings hold across calls: a second deep_research gets no more searches and no more
        // reads.
        JsonNode second =
                MAPPER.readTree(
                        tool(again, "deep_research").call("{\"question\":\"one more angle\"}"));
        assertTrue(second.get("error").asString().contains("search ceiling"));
        assertEquals(2, searches.get());
        assertEquals(3, fetches.get());
        assertEquals(2, second.get("searchesUsed").asInt());

        // 6. The answer a model would assemble from that output satisfies the research result
        // schema.
        ObjectNode answer = MAPPER.createObjectNode();
        answer.put("title", "Battery progress");
        answer.put("reportMarkdown", "Lab cells of solid-state batteries reach 400 Wh/kg [S1].");
        ArrayNode sources = answer.putArray("sources");
        ObjectNode source = sources.addObject();
        source.put("id", "S1");
        source.put("url", research.get("sources").get(0).get("url").asString());
        source.put("title", research.get("sources").get(0).get("title").asString());
        ObjectNode quote = source.putArray("quotes").addObject();
        quote.put("quote", QUOTE);
        quote.put("claim", CLAIM);
        quote.put("verdict", verified.get("results").get(0).get("verdict").asString());
        answer.putArray("unanswered").add("lithium battery");
        answer.put("partial", false);
        JobResultSchemaValidator validator =
                new JobResultSchemaValidator(MAPPER, new AgentJobProperties());
        assertEquals(List.of(), validator.violations(ResearchRunPlan.RESULT_SCHEMA, answer));
        answer.remove("reportMarkdown");
        assertFalse(validator.violations(ResearchRunPlan.RESULT_SCHEMA, answer).isEmpty());
    }

    @Test
    void anotherTurnStartsWithNoEvidenceAndAFreshAllowance() throws Exception {
        AtomicInteger first = new AtomicInteger();
        tool(resolve(first), "deep_research").call("{\"question\":\"battery progress\"}");
        assertEquals(1, searches.get());

        AtomicInteger next = new AtomicInteger();
        List<ToolCallback> tools = resolve(next);
        // Fail closed: nothing was researched in THIS turn, so nothing can be verified.
        JsonNode verify = MAPPER.readTree(tool(tools, "verify_citations").call(citations("S1")));
        assertTrue(
                verify.get("error")
                        .asString()
                        .contains("no deep_research results exist in this turn"));
        // ...and the new turn has its own search allowance.
        JsonNode research =
                MAPPER.readTree(
                        tool(tools, "deep_research").call("{\"question\":\"battery progress\"}"));
        assertEquals(1, research.get("searchesUsed").asInt());
        assertEquals(2, searches.get());
    }

    @Test
    void onceTheRunBudgetIsSpentDeepResearchRefusesWithoutSearching() throws Exception {
        AtomicInteger turn = new AtomicInteger();
        // The turn's shared budget, used up by delegation spend before deep_research is called.
        budgets.forTurn(ResearchTurnScope.turnKey(turn)).recordDelegate("q", "a");

        JsonNode refused =
                MAPPER.readTree(
                        tool(resolve(turn), "deep_research")
                                .call("{\"question\":\"battery progress\"}"));
        assertEquals(RunBudget.STOP_REASON, refused.get("stopReason").asString());
        assertEquals(0, searches.get(), "a refused call must not spend platform-paid searches");
        assertEquals(0, fetches.get());
    }

    @Test
    void aChatSessionRunsTheSameToolsButWritesNoJobProgress() throws Exception {
        List<ToolCallback> tools =
                resolver.resolveWebTools(
                        agentId, "chat-session-1", new AtomicInteger(), goal, null, false);
        JsonNode research =
                MAPPER.readTree(
                        tool(tools, "deep_research").call("{\"question\":\"battery progress\"}"));
        assertEquals(1, research.get("searchesUsed").asInt());
        org.mockito.Mockito.verifyNoInteractions(progress);
    }
}
