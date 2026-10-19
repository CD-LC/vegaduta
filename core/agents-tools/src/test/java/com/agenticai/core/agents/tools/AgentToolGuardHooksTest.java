package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hooks.AgentHookConfigService;
import com.agenticai.core.agents.hooks.AgentHookRuntime;
import com.agenticai.core.agents.hooks.EffectiveHook;
import com.agenticai.core.agents.hooks.FailMode;
import com.agenticai.core.agents.hooks.HookActionType;
import com.agenticai.core.agents.hooks.HookDefinition;
import com.agenticai.core.agents.hooks.HookMatch;
import com.agenticai.core.agents.hooks.HookPoint;
import com.agenticai.core.agents.hooks.HookSet;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Map;
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
 * The hook wiring in {@link AgentToolGuard#guardAndLog}: with the flag off (or no runtime bean) the
 * chain is the one that always existed and the hook config is never read; with it on, the hooks
 * take effect and still only tighten.
 */
class AgentToolGuardHooksTest {

    private static final UUID AGENT = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String TOOL = "send_email";

    private final ToolCallLogRepository logRepository = mock(ToolCallLogRepository.class);
    private final AgentService agentService = mock(AgentService.class);
    private final GuardrailService guardrails = mock(GuardrailService.class);
    private final ApprovalPolicyService approvalPolicy = mock(ApprovalPolicyService.class);
    private final HumanApprovalGateService approvalGate = mock(HumanApprovalGateService.class);
    private final AgentHookConfigService config = mock(AgentHookConfigService.class);
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
        when(agentService.getAutonomyTier(AGENT)).thenReturn(AgentAutonomyTier.SEMI_AUTONOMOUS);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any()))
                .thenAnswer(i -> i.getArgument(1));
        when(approvalGate.recordToolCallForApproval(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString(),
                        any()))
                .thenReturn(new ApprovalRequest("mcp_tool." + TOOL, "Tool call", "{}", "agent"));
        when(raw.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name(TOOL)
                                .description(TOOL)
                                .inputSchema("{}")
                                .build());
        when(raw.call(anyString())).thenReturn("{\"sent\":true}");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AgentToolGuard guard(AgentHookRuntime runtime) {
        AgentToolGovernance governance =
                new AgentToolGovernance(
                        providerOf(null), providerOf(null), providerOf(null), true, "");
        if (runtime != null) {
            governance.setHookRuntime(runtime);
        }
        return new AgentToolGuard(
                logRepository,
                agentService,
                guardrails,
                approvalPolicy,
                approvalGate,
                10,
                providerOf(null),
                providerOf(null),
                providerOf(null),
                providerOf(null),
                mock(ToolCircuitBreakerService.class),
                governance);
    }

    private AgentHookRuntime runtime(boolean enabled) {
        return new AgentHookRuntime(
                config,
                providerOf(null),
                providerOf(null),
                providerOf(null),
                providerOf(null),
                enabled,
                2_000,
                5_000,
                "");
    }

    private ToolCallback guarded(AgentToolGuard guard) {
        return guard.guardAndLog(raw, AGENT, "sess", new AtomicInteger(), "hello", null, false);
    }

    private static EffectiveHook hook(
            String id, HookPoint point, HookActionType action, Map<String, Object> params) {
        return new EffectiveHook(
                "agent",
                new HookDefinition(
                        id, true, point, HookMatch.ANY, action, params, FailMode.CLOSED, 2_000));
    }

    private void hooksAre(EffectiveHook... hooks) {
        when(config.effective(AGENT)).thenReturn(new HookSet(List.of(hooks), List.of()));
    }

    @Test
    void withNoRuntimeBeanTheChainIsUnchanged() {
        assertEquals("{\"sent\":true}", guarded(guard(null)).call("{}"));
    }

    @Test
    void withTheFlagOffTheHookConfigIsNeverReadAndTheCallRunsAsBefore() {
        hooksAre(hook("no-send", HookPoint.BEFORE_TOOL, HookActionType.BLOCK, Map.of()));

        assertEquals("{\"sent\":true}", guarded(guard(runtime(false))).call("{}"));

        verify(config, never()).effective(any());
    }

    @Test
    void governanceHandsBackNoHooksWhenTheFlagIsOff() {
        AgentToolGovernance governance =
                new AgentToolGovernance(
                        providerOf(null), providerOf(null), providerOf(null), true, "");
        governance.setHookRuntime(runtime(false));

        assertNull(governance.toolHooks(raw, AGENT, "sess", new AtomicInteger()));
        assertNull(
                new AgentToolGovernance(
                                providerOf(null), providerOf(null), providerOf(null), true, "")
                        .toolHooks(raw, AGENT, "sess", new AtomicInteger()),
                "and with no runtime at all");
    }

    @Test
    void aBlockHookRefusesTheCallThroughTheRealGuardAndTheToolNeverRuns() {
        hooksAre(
                hook(
                        "no-send",
                        HookPoint.BEFORE_TOOL,
                        HookActionType.BLOCK,
                        Map.of("reason", "Email is off")));

        String result = guarded(guard(runtime(true))).call("{}");

        assertTrue(result.contains("This tool call ('" + TOOL + "') was not executed"), result);
        assertTrue(result.contains("Email is off"), result);
        verify(raw, never()).call(anyString());
    }

    @Test
    void aRequireApprovalHookHoldsEvenForAFullyAutonomousAgent() {
        when(agentService.getAutonomyTier(AGENT)).thenReturn(AgentAutonomyTier.FULLY_AUTONOMOUS);
        hooksAre(hook("ask", HookPoint.BEFORE_TOOL, HookActionType.REQUIRE_APPROVAL, Map.of()));

        String result = guarded(guard(runtime(true))).call("{}");

        assertTrue(result.contains("requires human approval"), result);
        verify(raw, never()).call(anyString());
    }

    @Test
    void aFullyAutonomousAgentWithoutAnApprovalHookIsStillUngated() {
        when(agentService.getAutonomyTier(AGENT)).thenReturn(AgentAutonomyTier.FULLY_AUTONOMOUS);
        hooksAre(hook("audit", HookPoint.BEFORE_TOOL, HookActionType.LOG, Map.of()));

        assertEquals("{\"sent\":true}", guarded(guard(runtime(true))).call("{}"));
    }

    @Test
    void aSupervisedAgentStaysGatedWhateverTheHooksSay() {
        when(agentService.getAutonomyTier(AGENT)).thenReturn(AgentAutonomyTier.SUPERVISED);
        hooksAre(hook("audit", HookPoint.BEFORE_TOOL, HookActionType.LOG, Map.of()));

        String result = guarded(guard(runtime(true))).call("{}");

        assertTrue(
                result.contains("requires human approval"),
                "a log hook cannot lift ALWAYS_GATE: " + result);
        verify(raw, never()).call(anyString());
    }

    @Test
    void anAfterToolNoteReachesTheModelFacingResult() {
        hooksAre(
                hook(
                        "note",
                        HookPoint.AFTER_TOOL,
                        HookActionType.APPEND_CONTEXT,
                        Map.of("note", "Verify before acting.")));

        String result = guarded(guard(runtime(true))).call("{}");

        assertEquals("{\"sent\":true}\n\n[Workspace note] Verify before acting.", result);
    }
}
