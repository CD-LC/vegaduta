package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.capability.AgentCapabilityState;
import com.agenticai.core.agents.capability.AgentCapabilityStateId;
import com.agenticai.core.agents.capability.AgentCapabilityStateRepository;
import com.agenticai.core.agents.capability.DeepResearchCapability;
import com.agenticai.core.agents.collab.RunBudget;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.web.DeepResearchConfig;
import com.agenticai.core.agents.tools.web.DeepResearchSession;
import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * deep_research / verify_citations registration: offered only when enabled AND a platform Brave key
 * exists; the shared per-turn RunBudget; and the gate-only budget semantics. {@code guardAndLog} is
 * stubbed to pass the raw callback through (this asserts the manifest decision, not the guard
 * wrap). The tools come from {@link BuiltInWebTools} (core/agents-browser) behind the kernel's
 * {@link WebToolPort}; the resolver guards them and applies the turn budget.
 */
class DeepResearchToolRegistrationTest {

    private AgentToolGuard toolGuard;
    private PlatformToolCredentialService credentials;
    private ObjectProvider<PlatformToolCredentialService> credentialProvider;
    private ObjectProvider<HttpRequestExecutor> http;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        toolGuard = mock(AgentToolGuard.class);
        when(toolGuard.guardAndLog(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(toolGuard.withRunBudget(any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        credentials = mock(PlatformToolCredentialService.class);
        credentialProvider = mock(ObjectProvider.class);
        when(credentialProvider.getIfAvailable()).thenReturn(credentials);
        http = mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private AgentIntegrationToolResolver resolver(
            DeepResearchConfig config, ObjectProvider<PlatformToolCredentialService> creds) {
        return resolver(config, creds, null);
    }

    /** The web tools behind the last {@link #resolver} built (core/agents-browser's port impl). */
    private BuiltInWebTools lastWeb;

    @SuppressWarnings("unchecked")
    private AgentIntegrationToolResolver resolver(
            DeepResearchConfig config,
            ObjectProvider<PlatformToolCredentialService> creds,
            AgentService agents) {
        lastWeb = new BuiltInWebTools(agents, creds, http, config);
        ObjectProvider<WebToolPort> port = mock(ObjectProvider.class);
        when(port.getIfAvailable()).thenReturn(lastWeb);
        return new AgentIntegrationToolResolver(
                toolGuard,
                agents,
                List.of(),
                null,
                null,
                mock(ObjectProvider.class),
                port,
                mock(ObjectProvider.class),
                null);
    }

    private static final String RESEARCH_MESSAGE =
            "Research the state of solid-state batteries and compare vendors";

    private List<String> webToolNames(AgentIntegrationToolResolver r) {
        return webToolNames(r, RESEARCH_MESSAGE);
    }

    private List<String> webToolNames(AgentIntegrationToolResolver r, String message) {
        List<ToolCallback> tools =
                r.resolveWebTools(
                        UUID.randomUUID(), "s1", new AtomicInteger(), message, null, false);
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void notOfferedAndZeroSchemaTokensWhenMessageIsNotResearchAndAgentDidNotOptIn() {
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        AgentService agents = mock(AgentService.class);
        when(agents.getAllowedToolNames(any())).thenReturn(java.util.Set.of());
        AgentIntegrationToolResolver r =
                resolver(DeepResearchConfig.defaults(), credentialProvider, agents);
        List<String> names = webToolNames(r, "what is the capital of France?");
        assertTrue(names.contains("web_search"));
        assertFalse(names.contains("deep_research"));
        assertFalse(names.contains("verify_citations"));
        assertFalse(webToolNames(r, null).contains("deep_research"));
        assertFalse(webToolNames(r, "hello").contains("verify_citations"));
    }

    @Test
    void offeredToAnAgentThatListsTheToolEvenWhenTheMessageDoesNotLookLikeResearch() {
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        AgentService agents = mock(AgentService.class);
        when(agents.getAllowedToolNames(any()))
                .thenReturn(java.util.Set.of("web_search", "deep_research"));
        List<String> names =
                webToolNames(
                        resolver(DeepResearchConfig.defaults(), credentialProvider, agents),
                        "hello");
        assertTrue(names.contains("deep_research"));
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<AgentCapabilityStateRepository> capabilityRows(
            UUID agentId, boolean enabled) {
        AgentCapabilityStateRepository rows = mock(AgentCapabilityStateRepository.class);
        when(rows.findById(any())).thenReturn(Optional.empty());
        when(rows.findById(new AgentCapabilityStateId(agentId, DeepResearchCapability.CAPABILITY_KEY)))
                .thenReturn(
                        Optional.of(
                                new AgentCapabilityState(
                                        agentId, DeepResearchCapability.CAPABILITY_KEY, enabled)));
        ObjectProvider<AgentCapabilityStateRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(rows);
        return provider;
    }

    private List<String> webToolNamesFor(
            AgentIntegrationToolResolver r, UUID agentId, String message) {
        return r.resolveWebTools(agentId, "s1", new AtomicInteger(), message, null, false).stream()
                .map(t -> t.getToolDefinition().name())
                .toList();
    }

    @Test
    void capabilitySwitchOffersTheToolsWithoutAResearchLookingMessageOrAllowListEntry() {
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        AgentService agents = mock(AgentService.class);
        when(agents.getAllowedToolNames(any())).thenReturn(java.util.Set.of());
        UUID agentId = UUID.randomUUID();
        AgentIntegrationToolResolver r =
                resolver(DeepResearchConfig.defaults(), credentialProvider, agents);
        lastWeb.research().setCapabilityStates(capabilityRows(agentId, true));
        List<String> names = webToolNamesFor(r, agentId, "hello");
        assertTrue(names.contains("deep_research"));
        assertTrue(names.contains("verify_citations"));
        // Another agent in the same tenant, no row: still not offered.
        assertFalse(webToolNamesFor(r, UUID.randomUUID(), "hello").contains("deep_research"));
    }

    @Test
    void capabilityRowSwitchedOffIsNotAnOptIn() {
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        UUID agentId = UUID.randomUUID();
        AgentIntegrationToolResolver r =
                resolver(DeepResearchConfig.defaults(), credentialProvider);
        lastWeb.research().setCapabilityStates(capabilityRows(agentId, false));
        assertFalse(webToolNamesFor(r, agentId, "hello").contains("deep_research"));
        assertFalse(r.researchCapabilityEnabled(agentId));
    }

    @Test
    void capabilitySwitchNeverOffersTheToolsWhenTheDeploymentFlagIsOff() {
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        UUID agentId = UUID.randomUUID();
        AgentIntegrationToolResolver r =
                resolver(DeepResearchConfig.disabled(), credentialProvider);
        lastWeb.research().setCapabilityStates(capabilityRows(agentId, true));
        List<String> names = webToolNamesFor(r, agentId, RESEARCH_MESSAGE);
        assertFalse(names.contains("deep_research"));
        assertFalse(names.contains("verify_citations"));
        assertFalse(
                r.researchCapabilityEnabled(agentId),
                "flag off: the row must not count as an allow either");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailingCapabilityReadIsTreatedAsNotOptedIn() {
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        AgentCapabilityStateRepository rows = mock(AgentCapabilityStateRepository.class);
        when(rows.findById(any())).thenThrow(new IllegalStateException("db down"));
        ObjectProvider<AgentCapabilityStateRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(rows);
        AgentIntegrationToolResolver r =
                resolver(DeepResearchConfig.defaults(), credentialProvider);
        lastWeb.research().setCapabilityStates(provider);
        assertFalse(webToolNamesFor(r, UUID.randomUUID(), "hello").contains("deep_research"));
    }

    @Test
    void capabilityCountsAsAnAllowForExactlyTheTwoResearchTools() {
        List<ToolCallback> tools =
                List.of(
                        namedTool("web_search"),
                        namedTool("deep_research"),
                        namedTool("verify_citations"),
                        namedTool("http_request"));
        java.util.Set<String> allowList = java.util.Set.of("web_search");
        List<String> on =
                AgentIntegrationToolResolver.filterAllowed(allowList, tools, () -> true).stream()
                        .map(t -> t.getToolDefinition().name())
                        .toList();
        assertEquals(List.of("web_search", "deep_research", "verify_citations"), on);
        List<String> off =
                AgentIntegrationToolResolver.filterAllowed(allowList, tools, () -> false).stream()
                        .map(t -> t.getToolDefinition().name())
                        .toList();
        assertEquals(List.of("web_search"), off);
        // No research tool resolved this turn: the capability row is not even read.
        boolean[] read = {false};
        AgentIntegrationToolResolver.filterAllowed(
                allowList,
                List.of(namedTool("web_search")),
                () -> {
                    read[0] = true;
                    return true;
                });
        assertFalse(read[0]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aJobSessionRoutesResearchProgressToThatJobsTrailAndAChatSessionGetsNoSink() {
        AgentIntegrationToolResolver r =
                resolver(DeepResearchConfig.defaults(), credentialProvider);
        // Not wired at all (slim context): no sink even for a job session.
        UUID jobId = UUID.randomUUID();
        String jobSession = com.agenticai.core.agents.job.AgentJobSession.sessionIdFor(jobId);
        assertSame(
                com.agenticai.core.agents.tools.web.DeepResearchService.ProgressSink.NONE,
                lastWeb.research().progressSink(jobSession));

        com.agenticai.core.agents.job.progress.JobProgressRecorder progress =
                mock(com.agenticai.core.agents.job.progress.JobProgressRecorder.class);
        ObjectProvider<com.agenticai.core.agents.job.progress.JobProgressRecorder> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(progress);
        lastWeb.research().setJobProgress(provider);

        assertSame(
                com.agenticai.core.agents.tools.web.DeepResearchService.ProgressSink.NONE,
                lastWeb.research().progressSink("chat-session-1"));
        assertSame(
                com.agenticai.core.agents.tools.web.DeepResearchService.ProgressSink.NONE,
                lastWeb.research().progressSink(null));
        assertSame(
                com.agenticai.core.agents.tools.web.DeepResearchService.ProgressSink.NONE,
                lastWeb.research().progressSink("job:not-a-uuid"));

        lastWeb.research().progressSink(jobSession).note("Searching 2 angles", null);
        org.mockito.Mockito.verify(progress)
                .record(
                        jobId,
                        "Searching 2 angles",
                        null,
                        com.agenticai.core.agents.job.progress.ProgressSource.RUNTIME);

        // A failing progress store must not surface to the research call.
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(progress)
                .record(any(), any(), any(), any());
        lastWeb.research().progressSink(jobSession).note("Extracting quotes", null);
    }

    @Test
    void theCapabilitySwitchIsReadOncePerAgentNotOnEveryTurn() {
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        AgentService agents = mock(AgentService.class);
        when(agents.getAllowedToolNames(any())).thenReturn(java.util.Set.of());
        UUID agentId = UUID.randomUUID();
        AgentCapabilityStateRepository rows = mock(AgentCapabilityStateRepository.class);
        when(rows.findById(any())).thenReturn(Optional.empty());
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentCapabilityStateRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(rows);
        AgentIntegrationToolResolver r =
                resolver(DeepResearchConfig.defaults(), credentialProvider, agents);
        lastWeb.research().setCapabilityStates(provider);

        // Three ordinary turns of an agent without the switch: one primary-key read, not three.
        for (int turn = 0; turn < 3; turn++) {
            assertFalse(webToolNamesFor(r, agentId, "hello").contains("deep_research"));
        }
        org.mockito.Mockito.verify(rows, org.mockito.Mockito.times(1)).findById(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDailySearchAllowanceIsBoundToTheResolvingCallerNeverToAToolArgument() {
        AgentIntegrationToolResolver r =
                resolver(DeepResearchConfig.defaults(), credentialProvider);
        UUID agentId = UUID.randomUUID();
        // Not wired (slim context): no allowance, the per-turn ceilings alone apply.
        org.junit.jupiter.api.Assertions.assertNull(lastWeb.research().searchAllowance(agentId));

        com.agenticai.core.agents.tools.web.DeepResearchDailyAllowance allowance =
                mock(com.agenticai.core.agents.tools.web.DeepResearchDailyAllowance.class);
        com.agenticai.core.agents.tools.web.DeepResearchToolCallback.SearchAllowance bound =
                mock(
                        com.agenticai.core.agents.tools.web.DeepResearchToolCallback.SearchAllowance
                                .class);
        when(allowance.forCaller("t1", "alice", agentId)).thenReturn(bound);
        ObjectProvider<com.agenticai.core.agents.tools.web.DeepResearchDailyAllowance>
                allowanceProvider = mock(ObjectProvider.class);
        when(allowanceProvider.getIfAvailable()).thenReturn(allowance);
        new ResearchToolWiring(
                        mock(ObjectProvider.class), mock(ObjectProvider.class), allowanceProvider)
                .attach(lastWeb);

        com.agenticai.core.security.TenantContext.set(
                com.agenticai.core.security.TenantContext.of("t1", "alice", java.util.Set.of()));
        try {
            assertSame(bound, lastWeb.research().searchAllowance(agentId));
        } finally {
            com.agenticai.core.security.TenantContext.clear();
        }
    }

    private static ToolCallback namedTool(String name) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name(name)
                        .description("d")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }

    @Test
    void intentOfferCanBeTurnedOffLeavingOptInOnly() {
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        AgentService agents = mock(AgentService.class);
        when(agents.getAllowedToolNames(any())).thenReturn(java.util.Set.of());
        AgentIntegrationToolResolver r =
                resolver(DeepResearchConfig.defaults(), credentialProvider, agents);
        lastWeb.applyResearchTuning(120, 20, false);
        assertFalse(webToolNames(r, RESEARCH_MESSAGE).contains("deep_research"));
    }

    @Test
    void discoveryListsTheToolsButCandidateLookupResolvesFailClosedStubs() {
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        AgentIntegrationToolResolver r =
                resolver(DeepResearchConfig.defaults(), credentialProvider);
        List<ToolCallback> listed =
                r.resolveWebToolsForDiscovery(
                        UUID.randomUUID(), "p", new AtomicInteger(), null, null, false);
        assertTrue(
                listed.stream()
                        .anyMatch(t -> t.getToolDefinition().name().equals("deep_research")));
        List<ToolCallback> stateless =
                ResearchTurnScope.withMode(
                        ResearchTurnScope.Mode.STATELESS,
                        () ->
                                r.resolveWebTools(
                                        UUID.randomUUID(),
                                        "s",
                                        new AtomicInteger(),
                                        null,
                                        null,
                                        false));
        ToolCallback stub =
                stateless.stream()
                        .filter(t -> t.getToolDefinition().name().equals("verify_citations"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                stub.call("{\"citations\":[]}").contains("not available on this execution path"));
        ToolCallback research =
                stateless.stream()
                        .filter(t -> t.getToolDefinition().name().equals("deep_research"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                research.call("{\"question\":\"q\"}")
                        .contains("not available on this execution path"));
    }

    @Test
    void delegateResolutionSharesTheParentTurnsResearchSessionAndBudget() {
        TurnRunBudgets budgets =
                new TurnRunBudgets(
                        () ->
                                new RunBudget(
                                        new BigDecimal("1"),
                                        new BigDecimal("3"),
                                        new BigDecimal("15"),
                                        8000));
        ResearchTurnScope.Sessions<DeepResearchSession> sessions =
                new ResearchTurnScope.Sessions<>();
        AtomicInteger parentTurn = new AtomicInteger();
        DeepResearchSession parentSession =
                sessions.forTurn(
                        ResearchTurnScope.turnKey(parentTurn), () -> new DeepResearchSession(3, 3));
        RunBudget parentBudget = budgets.forTurn(ResearchTurnScope.turnKey(parentTurn));
        Object[] seen = new Object[2];
        ToolCallback delegation =
                new ToolCallback() {
                    @Override
                    public org.springframework.ai.tool.definition.ToolDefinition
                            getToolDefinition() {
                        return org.springframework.ai.tool.definition.ToolDefinition.builder()
                                .name("ask_agent_x")
                                .description("d")
                                .inputSchema("{}")
                                .build();
                    }

                    @Override
                    public String call(String toolInput) {
                        // What a delegate's resolveWebTools does: on this thread, and on a thread
                        // the call spawned.
                        seen[0] =
                                sessions.forTurn(
                                        ResearchTurnScope.turnKey(new AtomicInteger()),
                                        () -> new DeepResearchSession(3, 3));
                        Thread t =
                                Thread.ofVirtual()
                                        .start(
                                                () ->
                                                        seen[1] =
                                                                budgets.forTurn(
                                                                        ResearchTurnScope.turnKey(
                                                                                new AtomicInteger())));
                        try {
                            t.join();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "ok";
                    }
                };
        ResearchTurnScope.bind(delegation, parentTurn).call("{}");
        assertSame(
                parentSession,
                seen[0],
                "delegate must reuse the parent's session, not get a fresh allowance");
        assertSame(
                parentBudget,
                seen[1],
                "a thread spawned by the delegation inherits the parent's budget key");
        AtomicInteger other = new AtomicInteger();
        assertSame(
                other,
                ResearchTurnScope.turnKey(other),
                "outside a delegation the caller's own key applies");
    }

    private static ToolCallback stubTool(java.util.function.Supplier<String> body) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("ask_agent_x")
                        .description("d")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return body.get();
            }
        };
    }

    @Test
    void nestedDelegationAToBToCStaysOnTheRootTurnsKey() {
        AtomicInteger aTurn = new AtomicInteger();
        AtomicInteger bOwn = new AtomicInteger();
        AtomicInteger cOwn = new AtomicInteger();
        AtomicInteger[] seenByC = new AtomicInteger[1];
        // C is the leaf: it reads which turn key its resolution belongs to.
        ToolCallback cTool =
                stubTool(
                        () -> {
                            seenByC[0] = ResearchTurnScope.turnKey(cOwn);
                            return "c";
                        });
        // B, running inside A's bound call, resolves ITS tools with its OWN counter and binds C's
        // delegation.
        ToolCallback bTool =
                stubTool(() -> ResearchTurnScope.bindIfEnabled(true, cTool, bOwn).call("{}"));
        ResearchTurnScope.bindIfEnabled(true, bTool, aTurn).call("{}");
        assertSame(aTurn, seenByC[0], "C must resolve against A's turn, not B's fresh counter");
        assertSame(cOwn, ResearchTurnScope.turnKey(cOwn), "scope is restored after the call");
    }

    @Test
    void bindingIsSkippedWhenResearchIsDisabled() {
        ToolCallback tool = stubTool(() -> "x");
        assertSame(
                tool,
                ResearchTurnScope.bindIfEnabled(false, tool, new AtomicInteger()),
                "flag off must add no wrapper to delegation tools");
    }

    @Test
    void offeredWhenEnabledAndBraveKeyPresent() {
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        List<String> names =
                webToolNames(resolver(DeepResearchConfig.defaults(), credentialProvider));
        assertTrue(names.contains("web_search"));
        assertTrue(names.contains("deep_research"));
        assertTrue(names.contains("verify_citations"));
    }

    @Test
    void omittedWhenFlagOffEvenWithKey() {
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        List<String> names =
                webToolNames(resolver(DeepResearchConfig.disabled(), credentialProvider));
        assertTrue(names.contains("web_search"));
        assertFalse(names.contains("deep_research"));
        assertFalse(names.contains("verify_citations"));
    }

    @Test
    void omittedWhenConfigNull() {
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        List<String> names = webToolNames(resolver(null, credentialProvider));
        assertFalse(names.contains("deep_research"));
    }

    @Test
    void offeredAsUnavailableStubWhenBraveKeyMissing() {
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.empty());
        List<String> names =
                webToolNames(resolver(DeepResearchConfig.defaults(), credentialProvider));
        // web_search is still in the payload - as the refusing stub, so a misconfigured deployment
        // is an honest tool result instead of the model concluding it has no web access.
        assertTrue(names.contains("web_search"));
        assertFalse(names.contains("deep_research"));
        assertFalse(names.contains("verify_citations"));
        assertTrue(names.contains("fetch_web_page"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void omittedWhenNoCredentialService() {
        ObjectProvider<PlatformToolCredentialService> none = mock(ObjectProvider.class);
        when(none.getIfAvailable()).thenReturn(null);
        List<String> names = webToolNames(resolver(DeepResearchConfig.defaults(), none));
        assertFalse(names.contains("deep_research"));
    }

    @Test
    void turnBudgetIsSharedWithinATurnAndSeparateAcrossTurns() {
        TurnRunBudgets budgets =
                new TurnRunBudgets(
                        () ->
                                new RunBudget(
                                        new BigDecimal("1"),
                                        new BigDecimal("3"),
                                        new BigDecimal("15"),
                                        8000));
        AtomicInteger turnA = new AtomicInteger();
        AtomicInteger turnB = new AtomicInteger();
        assertSame(budgets.forTurn(turnA), budgets.forTurn(turnA));
        assertNotSame(budgets.forTurn(turnA), budgets.forTurn(turnB));
        assertNotSame(budgets.forTurn(null), budgets.forTurn(null));
    }

    @Test
    void deepResearchIsGatedButNotChargedByTheSharedBudget() {
        RunBudget budget =
                new RunBudget(
                        new BigDecimal("0.01"), new BigDecimal("3"), new BigDecimal("15"), 8000);
        int[] calls = {0};
        ToolCallback inner =
                new ToolCallback() {
                    @Override
                    public org.springframework.ai.tool.definition.ToolDefinition
                            getToolDefinition() {
                        return org.springframework.ai.tool.definition.ToolDefinition.builder()
                                .name("deep_research")
                                .description("d")
                                .inputSchema("{}")
                                .build();
                    }

                    @Override
                    public String call(String toolInput) {
                        calls[0]++;
                        return "{\"ok\":true}";
                    }
                };
        // recordUsage=false, as the resolver wires it: running the tool must not spend budget...
        RunBudgetToolCallback gate = new RunBudgetToolCallback(inner, budget, false);
        assertEquals("{\"ok\":true}", gate.call("{}"));
        assertEquals(0, budget.spentUsd().signum());
        // ...but once delegation has used the cap, the tool refuses to run.
        budget.recordDelegate("q", "a");
        assertTrue(gate.call("{}").contains(RunBudget.STOP_REASON));
        assertEquals(1, calls[0]);
    }
}
