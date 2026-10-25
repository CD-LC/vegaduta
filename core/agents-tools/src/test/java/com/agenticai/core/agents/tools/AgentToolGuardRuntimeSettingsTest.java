package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.limits.AgentRuntimeProperties;
import com.agenticai.core.agents.runtime.AgentRuntime;
import com.agenticai.core.agents.runtime.AgentRuntimeSettings;
import com.agenticai.core.agents.limits.AgentRuntimeSettingsResolver;
import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.agents.runtime.RuntimeSetting;
import com.agenticai.core.agents.limits.TenantRuntimeLimitsService;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.chat.ToolLoopProperties;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.guardrails.ToolBudgetExhausted;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import com.agenticai.core.knowledge.KnowledgeCollectionService;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Where the tool guard gets an agent's runtime settings from: the turn's own (handed over for the turn's agent
 * only), else the resolver, else - in a hand-built fixture with neither - the guard's constructor settings. Plus
 * the knowledge-search tool, which takes its top-k and min-score from the same place.
 */
class AgentToolGuardRuntimeSettingsTest {

    private static final UUID AGENT = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID OTHER_AGENT = UUID.fromString("88888888-8888-8888-8888-888888888888");

    private final ToolCallLogRepository logRepository = mock(ToolCallLogRepository.class);
    private final AgentService agentService = mock(AgentService.class);
    private final GuardrailService guardrails = mock(GuardrailService.class);
    private final TenantRuntimeLimitsService tenantLimits = mock(TenantRuntimeLimitsService.class);
    private final ToolCallback raw = mock(ToolCallback.class);

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant-a", "user-1", Set.of()));
        when(agentService.getAutonomyTier(any())).thenReturn(AgentAutonomyTier.SEMI_AUTONOMOUS);
        when(agentService.getRuntimeLimits(any())).thenReturn(RuntimeLimits.none());
        when(tenantLimits.current()).thenReturn(RuntimeLimits.none());
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(raw.getToolDefinition())
                .thenReturn(ToolDefinition.builder().name("lookup").description("lookup").inputSchema("{}").build());
        when(raw.call(anyString())).thenReturn("{\"ok\":true}");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        AgentToolGuard.TURN_RUNTIME_SCOPE.remove();
    }

    private AgentRuntimeSettingsResolver resolver() {
        return new AgentRuntimeSettingsResolver(
                new AgentRuntimeProperties(),
                new AgentRuntimeSettingsResolver.PlatformKeys(10, Duration.ofSeconds(240), 8),
                new ToolStepLimitProperties(),
                new ToolOutputProperties(),
                new ToolLoopProperties(),
                tenantLimits,
                agentService);
    }

    private AgentToolGuard guard(AgentRuntimeSettingsResolver resolver) {
        AgentToolGuard guard =
                new AgentToolGuard(
                        logRepository,
                        agentService,
                        guardrails,
                        mock(ApprovalPolicyService.class),
                        mock(HumanApprovalGateService.class),
                        10,
                        providerOf(null),
                        providerOf(null),
                        providerOf(null),
                        providerOf(null),
                        mock(ToolCircuitBreakerService.class),
                        new AgentToolGovernance(providerOf(null), providerOf(null), providerOf(null), true, ""));
        if (resolver != null) {
            guard.setRuntimeResolver(providerOf((AgentRuntime) resolver));
        }
        return guard;
    }

    private int callsThatRunBeforeTheBudgetNotice(ToolCallback tool) {
        int ran = 0;
        for (int i = 0; i < 60; i++) {
            if (ToolBudgetExhausted.isBudgetExhausted(tool.call("{}"))) {
                return ran;
            }
            ran++;
        }
        return ran;
    }

    private ToolCallback guarded(AgentToolGuard guard, UUID agent) {
        return guard.guardAndLog(raw, agent, "sess", new AtomicInteger(), "hello", null, false);
    }

    @Test
    void withNoResolverTheGuardKeepsItsConstructorCeiling() {
        assertEquals(10, callsThatRunBeforeTheBudgetNotice(guarded(guard(null), AGENT)));
    }

    @Test
    void theResolversAgentLayerSetsTheCeilingOfAToolOutsideAnyTurn() {
        when(agentService.getRuntimeLimits(AGENT)).thenReturn(RuntimeLimits.none().with(RuntimeSetting.MAX_TOOL_STEPS, 4));

        assertEquals(4, callsThatRunBeforeTheBudgetNotice(guarded(guard(resolver()), AGENT)),
                "approval replay and edge execution resolve tools with no turn: they get the agent's own ceiling");
    }

    @Test
    void theTurnsOwnSettingsAreUsedForTheTurnsAgent() {
        AgentRuntimeSettings turn = resolver().resolve(AgentAutonomyTier.SEMI_AUTONOMOUS, null, RuntimeLimits.capToolSteps(2), null);
        AgentToolGuard.TURN_RUNTIME_SCOPE.set(new AgentToolGuard.TurnRuntimeScope(AGENT, turn));

        assertEquals(2, callsThatRunBeforeTheBudgetNotice(guarded(guard(resolver()), AGENT)),
                "the job's remaining budget rides on the turn's settings");
    }

    @Test
    void aDelegatedAgentsToolsAreNotHeldToTheTurnsSettings() {
        AgentRuntimeSettings turn = resolver().resolve(AgentAutonomyTier.SEMI_AUTONOMOUS, null, RuntimeLimits.capToolSteps(2), null);
        AgentToolGuard.TURN_RUNTIME_SCOPE.set(new AgentToolGuard.TurnRuntimeScope(AGENT, turn));

        assertEquals(10, callsThatRunBeforeTheBudgetNotice(guarded(guard(resolver()), OTHER_AGENT)));
    }

    @Test
    void aResolverThatFailsLeavesTheGuardOnItsOwnSettingsRatherThanBreakingTheTool() {
        when(agentService.getRuntimeLimits(AGENT)).thenThrow(new IllegalStateException("db down"));
        AgentRuntimeSettingsResolver failing = resolver();

        assertNull(guard(failing).runtimeSettings(AGENT));
        assertEquals(10, callsThatRunBeforeTheBudgetNotice(guarded(guard(failing), AGENT)));
    }

    // --- the knowledge-search tool ------------------------------------------------------------------

    @Test
    void theKnowledgeSearchToolTakesTheAgentsTopKAndMinScore() {
        when(agentService.getRuntimeLimits(AGENT)).thenReturn(
                RuntimeLimits.none().with(RuntimeSetting.RETRIEVAL_TOP_K, 9).with(RuntimeSetting.RETRIEVAL_MIN_SCORE, 0.1));
        when(agentService.isKnowledgeBaseEnabled(AGENT)).thenReturn(true);
        when(agentService.getAllowedKnowledgeCollectionIds(AGENT)).thenReturn(Set.of());
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        when(search.search(anyString(), anyInt(), any(), anyBoolean())).thenReturn(List.of(
                hit("strong", -0.5), hit("weak", -0.01)));
        AgentKnowledgeToolResolver resolver = new AgentKnowledgeToolResolver(
                guard(resolver()),
                new KnowledgeServiceProviders(
                        providerOf(search), providerOf(mock(KnowledgeCollectionService.class)),
                        providerOf(null), providerOf(null)),
                providerOf(null), providerOf(null), agentService, mock(HumanApprovalGateService.class),
                providerOf(null), null, null, providerOf(null));

        List<ToolCallback> tools = resolver.resolveKnowledgeTools(AGENT, "sess", new AtomicInteger(), "hello", null, false);
        ToolCallback searchTool = tools.stream()
                .filter(t -> t.getToolDefinition().name().equals("search_knowledge_base")).findFirst().orElseThrow();
        String result = searchTool.call("{\"query\":\"q\"}");

        verify(search).search("q", 9, Set.of(), false);
        assertTrue(result.contains("strong"), result);
        assertFalse(result.contains("weak"), "below the agent's min-score: " + result);
    }

    private static SearchResultResponse hit(String source, double distance) {
        return new SearchResultResponse(UUID.randomUUID(), source, 0, "text of " + source, UUID.randomUUID(), distance, null);
    }
}
