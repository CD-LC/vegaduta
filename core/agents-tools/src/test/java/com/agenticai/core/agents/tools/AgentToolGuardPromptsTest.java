package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.guardrails.ToolBudgetExhausted;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
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
 * The prompt registry reaches the tool chain the real {@link AgentToolGuard} builds: the text a guarded tool answers
 * with once the turn's tool-call ceiling is used up is the tenant's or the agent's override, and the shipped text
 * (byte for byte the old property) when there is none. Without a resolver wired - a hand-built fixture - the shipped
 * text applies.
 */
class AgentToolGuardPromptsTest {

    private static final UUID AGENT = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID OTHER_AGENT = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final TenantContext TENANT = TenantContext.of("tenant-a", "user-1", Set.of());

    private final AgentService agentService = mock(AgentService.class);
    private final GuardrailService guardrails = mock(GuardrailService.class);
    private final ToolCallback raw = mock(ToolCallback.class);
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        when(agentService.getAutonomyTier(any())).thenReturn(AgentAutonomyTier.SEMI_AUTONOMOUS);
        when(agentService.getRuntimeLimits(any())).thenReturn(RuntimeLimits.none());
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(raw.getToolDefinition())
                .thenReturn(ToolDefinition.builder().name("lookup").description("lookup").inputSchema("{}").build());
        when(raw.call(anyString())).thenReturn("{\"ok\":true}");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AgentToolGuard guard(boolean withResolver) {
        AgentToolGuard guard =
                new AgentToolGuard(
                        mock(ToolCallLogRepository.class),
                        agentService,
                        guardrails,
                        mock(ApprovalPolicyService.class),
                        mock(HumanApprovalGateService.class),
                        2,
                        providerOf(null),
                        providerOf(null),
                        providerOf(null),
                        providerOf(null),
                        mock(ToolCircuitBreakerService.class),
                        new AgentToolGovernance(providerOf(null), providerOf(null), providerOf(null), true, ""));
        if (withResolver) {
            guard.setPromptResolver(providerOf((PromptResolver) resolver));
        }
        return guard;
    }

    private void override(PromptScope scope, UUID agent, String key, String text) {
        store.put(new PromptOverride(
                scope, scope == PromptScope.AGENT ? agent.toString() : "", key, "en", text, true, 1, "owner", Instant.now()));
        resolver.evictAll();
    }

    /** Calls the guarded tool until the budget notice arrives and returns it. */
    private String budgetNotice(AgentToolGuard guard, UUID agent) {
        ToolCallback tool = guard.guardAndLog(raw, agent, "sess", new AtomicInteger(), "hello", null, false);
        for (int i = 0; i < 80; i++) {
            String result = tool.call("{}");
            if (ToolBudgetExhausted.isBudgetExhausted(result)) {
                return result;
            }
        }
        throw new AssertionError("the tool never reported its budget as used up");
    }

    @Test
    void byDefaultTheBudgetNoticeIsTheShippedTextWithOrWithoutAResolver() {
        String viaRegistry = budgetNotice(guard(true), AGENT);
        String viaFixture = budgetNotice(guard(false), AGENT);

        assertEquals(viaFixture, viaRegistry);
        assertTrue(viaRegistry.contains("Tool budget for this reply is exhausted"), viaRegistry);
        assertTrue(viaRegistry.contains(new ToolStepLimitProperties().getExhaustedMessage().substring(0, 40)), viaRegistry);
    }

    @Test
    void aTenantOverrideReachesTheBudgetNoticeOfEveryAgentAndAnAgentOverrideBeatsItForThatAgent() {
        override(PromptScope.TENANT, null, "toolresult.tool_budget.calls", "House budget notice at {limit}.");
        override(PromptScope.AGENT, AGENT, "toolresult.tool_budget.calls", "Agent budget notice at {limit}.");
        AgentToolGuard guard = guard(true);

        String forAgent = budgetNotice(guard, AGENT);
        String forOther = budgetNotice(guard, OTHER_AGENT);

        assertNotNull(forAgent);
        assertTrue(forAgent.contains("Agent budget notice at "), forAgent);
        assertTrue(forOther.contains("House budget notice at "), forOther);
        assertTrue(ToolBudgetExhausted.isBudgetExhausted(forAgent), "still the structured tool_budget_exhausted result");
    }

    @Test
    void withoutAResolverTheOverrideIsNotSeenAndTheShippedTextApplies() {
        override(PromptScope.TENANT, null, "toolresult.tool_budget.calls", "House budget notice at {limit}.");

        assertTrue(budgetNotice(guard(false), AGENT).contains("Tool budget for this reply is exhausted"));
    }
}
