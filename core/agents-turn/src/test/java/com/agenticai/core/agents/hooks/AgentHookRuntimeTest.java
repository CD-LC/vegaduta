package com.agenticai.core.agents.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolDeterminism;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.guardrails.ApprovalGateMode;
import com.agenticai.core.guardrails.ToolDispatchGate;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
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

/** Flag-off identity, and how the runtime sorts a hook set into the adapters one tool and one turn get. */
class AgentHookRuntimeTest {

    private static final UUID AGENT = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private final AgentHookConfigService config = mock(AgentHookConfigService.class);
    private final ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant-a", "user-1", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AgentHookRuntime runtime(boolean enabled) {
        ObjectProvider<AuditLogService> audit = provider(null);
        ObjectProvider<MeterRegistry> meters = provider(null);
        ObjectProvider<McpRemoteUrlGuard> guard = provider(null);
        return new AgentHookRuntime(config, provider(registry), audit, meters, guard, enabled, 2_000, 5_000, "");
    }

    private static ToolCallback tool(String name) {
        ToolCallback t = mock(ToolCallback.class);
        when(t.getToolDefinition()).thenReturn(ToolDefinition.builder().name(name).description(name).inputSchema("{}").build());
        return t;
    }

    private static EffectiveHook hook(String id, HookPoint point, HookActionType action, HookMatch match, Map<String, Object> params) {
        return new EffectiveHook("agent", new HookDefinition(id, true, point, match, action, params, FailMode.CLOSED, 2_000));
    }

    private void hooksAre(EffectiveHook... hooks) {
        when(config.effective(AGENT)).thenReturn(new HookSet(List.of(hooks), List.of()));
    }

    // ------------------------------------------------------------------ flag off

    @Test
    void withTheFlagOffNothingIsReadAndNothingIsReturned() {
        AgentHookRuntime runtime = runtime(false);

        assertFalse(runtime.isEnabled());
        assertNull(runtime.toolHooks(tool("t"), AGENT, "s", new AtomicInteger(), null));
        assertNull(runtime.beginRun(AGENT, "s", null));
        verify(config, never()).effective(any());
    }

    @Test
    void withTheFlagOnButNoHooksConfiguredNothingIsReturnedEither() {
        when(config.effective(AGENT)).thenReturn(HookSet.EMPTY);
        AgentHookRuntime runtime = runtime(true);

        assertNull(runtime.toolHooks(tool("t"), AGENT, "s", new AtomicInteger(), null));
        assertNull(runtime.beginRun(AGENT, "s", null));
    }

    @Test
    void aConfigFailureLeavesTheCallsRunningWithoutHooksRatherThanFailingThem() {
        when(config.effective(AGENT)).thenThrow(new IllegalStateException("db down"));
        AgentHookRuntime runtime = runtime(true);

        assertNull(runtime.toolHooks(tool("t"), AGENT, "s", new AtomicInteger(), null));
        assertNull(runtime.beginRun(AGENT, "s", null));
    }

    // ------------------------------------------------------------- tool partitioning

    @Test
    void toolHooksAreSortedIntoGateInnerOuterAndErrorAdapters() {
        hooksAre(
            hook("no-send", HookPoint.BEFORE_TOOL, HookActionType.BLOCK, new HookMatch("send_*", null, null, null), Map.of()),
            hook("cap", HookPoint.BEFORE_TOOL, HookActionType.LIMIT, new HookMatch("send_*", null, null, null), Map.of("maxPerRun", 2)),
            hook("note", HookPoint.AFTER_TOOL, HookActionType.APPEND_CONTEXT, HookMatch.ANY, Map.of("note", "careful")),
            hook("page", HookPoint.ON_ERROR, HookActionType.LOG, HookMatch.ANY, Map.of()),
            hook("model", HookPoint.BEFORE_MODEL, HookActionType.LOG, HookMatch.ANY, Map.of()));
        ToolCallback raw = tool("send_email");

        AgentToolHooks hooks = runtime(true).toolHooks(raw, AGENT, "s", new AtomicInteger(), null);

        assertNotNull(hooks);
        assertTrue(hooks.gate(null) instanceof HookDispatchGate, "the block hook went to the dispatch gate");
        assertTrue(hooks.inner(raw) instanceof HookingToolCallback, "limit + after + error need the inner wrapper");
        ToolCallback guarded = tool("send_email");
        assertTrue(hooks.outer(guarded) instanceof HookingResultToolCallback, "the AFTER_TOOL note needs the outer wrapper");
    }

    @Test
    void aToolNoHookMatchesGetsNoAdaptersAtAll() {
        hooksAre(hook("no-send", HookPoint.BEFORE_TOOL, HookActionType.BLOCK, new HookMatch("send_*", null, null, null), Map.of()));

        assertNull(runtime(true).toolHooks(tool("read_file"), AGENT, "s", new AtomicInteger(), null));
    }

    @Test
    void onlyBlockAndApprovalHooksMeansOnlyTheGateIsReplaced() {
        hooksAre(hook("ask", HookPoint.BEFORE_TOOL, HookActionType.REQUIRE_APPROVAL, HookMatch.ANY, Map.of()));
        ToolCallback raw = tool("t");

        AgentToolHooks hooks = runtime(true).toolHooks(raw, AGENT, "s", new AtomicInteger(), null);

        assertSame(raw, hooks.inner(raw), "nothing runs around the raw tool");
        ToolCallback guarded = tool("t");
        assertSame(guarded, hooks.outer(guarded));
        assertTrue(hooks.gate(name -> ToolDispatchGate.Verdict.allow()) instanceof HookDispatchGate);
        assertEquals(ApprovalGateMode.POLICY_DEFAULT, hooks.tighten(ApprovalGateMode.NEVER_GATE));
    }

    @Test
    void aWildcardApprovalOrBlockHookNeverGatesTheDeferredToolDispatchers() {
        hooksAre(
            hook("ask-all", HookPoint.BEFORE_TOOL, HookActionType.REQUIRE_APPROVAL, HookMatch.ANY, Map.of()),
            hook("block-all", HookPoint.BEFORE_TOOL, HookActionType.BLOCK, HookMatch.ANY, Map.of()));

        assertNull(runtime(true).toolHooks(tool("call_tool"), AGENT, "s", new AtomicInteger(), null),
            "call_tool is dispatch plumbing: the deferred target carries its own hooks");
        assertNull(runtime(true).toolHooks(tool("search_tools"), AGENT, "s", new AtomicInteger(), null));
        assertNotNull(runtime(true).toolHooks(tool("send_email"), AGENT, "s", new AtomicInteger(), null),
            "an ordinary tool is still gated by the same hooks");
    }

    @Test
    void aDisabledHookIsIgnored() {
        HookDefinition off = new HookDefinition("off", false, HookPoint.BEFORE_TOOL, HookMatch.ANY, HookActionType.BLOCK, Map.of(),
            FailMode.CLOSED, 2_000);
        when(config.effective(AGENT)).thenReturn(new HookSet(List.of(new EffectiveHook("agent", off)), List.of()));

        assertNull(runtime(true).toolHooks(tool("t"), AGENT, "s", new AtomicInteger(), null));
    }

    @Test
    void perRunLimitsCountPerTurnAndTheCounterIsSharedByEveryToolOfTheTurn() {
        hooksAre(hook("cap", HookPoint.BEFORE_TOOL, HookActionType.LIMIT, HookMatch.ANY, Map.of("maxPerRun", 1)));
        AgentHookRuntime runtime = runtime(true);
        AtomicInteger turnOne = new AtomicInteger();
        ToolCallback a = tool("tool_a");
        ToolCallback b = tool("tool_b");
        when(a.call(any())).thenReturn("{}");
        when(b.call(any())).thenReturn("{}");

        ToolCallback wrappedA = runtime.toolHooks(a, AGENT, "s", turnOne, null).inner(a);
        ToolCallback wrappedB = runtime.toolHooks(b, AGENT, "s", turnOne, null).inner(b);

        assertEquals("{}", wrappedA.call("{}"));
        assertTrue(wrappedB.call("{}").startsWith("This tool call ('tool_b') was not executed"),
            "the same hook, the same turn: the second tool call is over the cap");
        verify(b, never()).call(any());

        ToolCallback fresh = runtime.toolHooks(b, AGENT, "s", new AtomicInteger(), null).inner(b);
        assertEquals("{}", fresh.call("{}"), "another turn counts from zero");
    }

    // ------------------------------------------------------- classification matching

    private static ToolClassificationDecision classified(String tool, ToolSensitivity sensitivity, boolean irreversible) {
        return new ToolClassificationDecision.Classified(new ToolClassification(
            tool, ToolTrustTier.TENANT_SCOPED, sensitivity, ToolDeterminism.SIDE_EFFECT, null, irreversible, true));
    }

    @Test
    void sensitivityHooksUseTheToolClassification() {
        hooksAre(hook("egress", HookPoint.BEFORE_TOOL, HookActionType.BLOCK, new HookMatch(null, null, "EGRESS", null), Map.of()));
        when(registry.resolve("send_email")).thenReturn(classified("send_email", ToolSensitivity.EGRESS, true));
        when(registry.resolve("read_doc")).thenReturn(classified("read_doc", ToolSensitivity.READ_TENANT, false));
        when(registry.resolve("mystery")).thenReturn(
            new ToolClassificationDecision.Blocked("mystery", ToolClassificationDecision.Reason.UNCLASSIFIED, "none"));
        AgentHookRuntime runtime = runtime(true);

        assertNotNull(runtime.toolHooks(tool("send_email"), AGENT, "s", new AtomicInteger(), null));
        assertNull(runtime.toolHooks(tool("read_doc"), AGENT, "s", new AtomicInteger(), null));
        assertNull(runtime.toolHooks(tool("mystery"), AGENT, "s", new AtomicInteger(), null),
            "an unclassified tool is not an EGRESS tool");
    }

    @Test
    void theUnclassifiedSentinelTargetsToolsNobodyClassified() {
        hooksAre(hook("unknown", HookPoint.BEFORE_TOOL, HookActionType.REQUIRE_APPROVAL,
            new HookMatch(null, null, HookMatch.UNCLASSIFIED, null), Map.of()));
        when(registry.resolve("mystery")).thenReturn(
            new ToolClassificationDecision.Blocked("mystery", ToolClassificationDecision.Reason.UNCLASSIFIED, "none"));
        when(registry.resolve("read_doc")).thenReturn(classified("read_doc", ToolSensitivity.READ_TENANT, false));
        AgentHookRuntime runtime = runtime(true);

        assertNotNull(runtime.toolHooks(tool("mystery"), AGENT, "s", new AtomicInteger(), null));
        assertNull(runtime.toolHooks(tool("read_doc"), AGENT, "s", new AtomicInteger(), null));
    }

    @Test
    void aFailedClassificationLookupMatchesConservatively() {
        hooksAre(hook("egress", HookPoint.BEFORE_TOOL, HookActionType.BLOCK, new HookMatch(null, null, "EGRESS", null), Map.of()));
        when(registry.resolve("send_email")).thenThrow(new IllegalStateException("db down"));

        assertNotNull(runtime(true).toolHooks(tool("send_email"), AGENT, "s", new AtomicInteger(), null),
            "could not tell must not read as does not apply for a rule that only tightens");
    }

    @Test
    void classificationIsOnlyLookedUpWhenAHookConstrainsOnIt() {
        hooksAre(hook("by-name", HookPoint.BEFORE_TOOL, HookActionType.BLOCK, new HookMatch("send_*", null, null, null), Map.of()));

        runtime(true).toolHooks(tool("send_email"), AGENT, "s", new AtomicInteger(), null);

        verify(registry, never()).resolve(any(String.class));
    }

    @Test
    void classificationLookupsAreMemoisedPerTenantAndTool() {
        hooksAre(hook("egress", HookPoint.BEFORE_TOOL, HookActionType.BLOCK, new HookMatch(null, null, "EGRESS", null), Map.of()));
        when(registry.resolve("send_email")).thenReturn(classified("send_email", ToolSensitivity.EGRESS, true));
        AgentHookRuntime runtime = runtime(true);

        runtime.toolHooks(tool("send_email"), AGENT, "s", new AtomicInteger(), null);
        runtime.toolHooks(tool("send_email"), AGENT, "s", new AtomicInteger(), null);

        verify(registry, times(1)).resolve("send_email");
    }

    // ------------------------------------------------------------------- run level

    @Test
    void beginRunCarriesTheModelAndRunHooksOnly() {
        hooksAre(
            hook("closed", HookPoint.BEFORE_MODEL, HookActionType.BLOCK, HookMatch.ANY, Map.of("reason", "closed")),
            hook("audit", HookPoint.ON_RUN_END, HookActionType.LOG, HookMatch.ANY, Map.of()),
            hook("tool-only", HookPoint.BEFORE_TOOL, HookActionType.BLOCK, HookMatch.ANY, Map.of()));

        HookRun run = runtime(true).beginRun(AGENT, "s", null);

        assertNotNull(run);
        assertEquals("closed", run.beforeModel("hi"));
    }

    @Test
    void aSetWithOnlyToolHooksStartsNoRun() {
        hooksAre(hook("tool-only", HookPoint.BEFORE_TOOL, HookActionType.BLOCK, HookMatch.ANY, Map.of()));

        assertNull(runtime(true).beginRun(AGENT, "s", null), "so the chat path keeps its caches and its stream untouched");
    }

    @Test
    void aToolConstrainedOnErrorHookNeverStartsARun() {
        hooksAre(hook("flaky", HookPoint.ON_ERROR, HookActionType.LOG, new HookMatch("flaky_*", null, null, null), Map.of()));

        assertNull(runtime(true).beginRun(AGENT, "s", null));
    }
}
