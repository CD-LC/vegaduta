package com.agenticai.core.agents.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.guardrails.ApprovalGateMode;
import com.agenticai.core.guardrails.GuardedToolCallback;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.guardrails.ToolDispatchGate;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The hook wrappers spliced into a REAL {@link GuardedToolCallback}, built the way {@code AgentToolGuard#guardAndLog}
 * builds it. These are the tighten-only guarantees: a hook never makes a call happen that the gate would not allow,
 * and never skips the approval gate.
 */
class HookGuardChainTest {

    private static final UUID AGENT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String TOOL = "send_email";

    /** A raw tool that records what it was called with. */
    private static final class RawTool implements ToolCallback {
        final List<String> calls = new CopyOnWriteArrayList<>();
        String output = "{\"sent\":true}";
        RuntimeException failure;

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(TOOL).description(TOOL).inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            calls.add(toolInput);
            if (failure != null) {
                throw failure;
            }
            return output;
        }
    }

    private final ApprovalPolicyService approvalPolicy = mock(ApprovalPolicyService.class);
    private final HumanApprovalGateService approvalGate = mock(HumanApprovalGateService.class);
    private final GuardrailService guardrails = mock(GuardrailService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final List<String> webhookPayloads = new CopyOnWriteArrayList<>();
    private final RawTool raw = new RawTool();
    private final AtomicInteger steps = new AtomicInteger();
    private HookEngine engine;
    private HookCtx ctx;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @BeforeEach
    void setUp() {
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(approvalGate.recordToolCallForApproval(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
            .thenReturn(new ApprovalRequest("mcp_tool." + TOOL, "Tool call", "{}", "agent"));
        HookWebhookClient webhook = new HookWebhookClient(
            url -> Optional.empty(), List.of("hooks.example.com"), "",
            (uri, payload, headers, timeout) -> {
                webhookPayloads.add(payload);
                return new HookWebhookClient.Response(200, "{\"decision\":\"allow\"}");
            });
        ObjectProvider<MeterRegistry> noMeters = provider(null);
        engine = new HookEngine(webhook, new HookRateLimiter(), new HookDecisionSink(provider(audit), noMeters), 2_000, 5_000, Runnable::run);
        ctx = new HookCtx(AGENT, "sess", "tenant-a", null, null, List.of());
    }

    private static EffectiveHook hook(String id, HookPoint point, HookActionType action, Map<String, Object> params) {
        return new EffectiveHook("agent", new HookDefinition(id, true, point, HookMatch.ANY, action, params, FailMode.CLOSED, 2_000));
    }

    private AgentToolHooks hooks(
        List<EffectiveHook> gate, List<EffectiveHook> pre, List<EffectiveHook> after, List<EffectiveHook> error
    ) {
        return new AgentToolHooks(engine, ctx, TOOL, gate, pre, after, error, new HookRateLimiter.RunCounters());
    }

    /** The chain exactly as AgentToolGuard builds it: outer hooks -> Guarded(inner hooks(raw)), gate and mode tightened. */
    private ToolCallback chain(AgentToolHooks hooks, ToolDispatchGate baseGate, ApprovalGateMode mode) {
        return chain(hooks, baseGate, mode, steps);
    }

    private ToolCallback chain(AgentToolHooks hooks, ToolDispatchGate baseGate, ApprovalGateMode mode,
        java.util.concurrent.atomic.AtomicInteger turnSteps) {
        ToolCallback guarded = new GuardedToolCallback(
            hooks.inner(raw), turnSteps, 10, guardrails, approvalPolicy, approvalGate, AGENT, "sess",
            null, false, null, null, hooks.tighten(mode), null, null, null, hooks.gate(baseGate), null);
        return hooks.outer(guarded);
    }

    // ------------------------------------------------------- the tighten-only guarantees

    @Test
    void aWebhookAllowDoesNotBypassTheApprovalGate() {
        when(approvalPolicy.requiresApproval("mcp_tool." + TOOL)).thenReturn(true);
        AgentToolHooks hooks = hooks(List.of(), List.of(hook("vet", HookPoint.BEFORE_TOOL, HookActionType.WEBHOOK,
            Map.of("url", "https://hooks.example.com/vet"))), List.of(), List.of());

        String result = chain(hooks, null, ApprovalGateMode.POLICY_DEFAULT).call("{\"to\":\"a@b.co\"}");

        assertTrue(result.contains("requires human approval"), result);
        assertTrue(raw.calls.isEmpty(), "the tool never ran");
        assertTrue(webhookPayloads.isEmpty(), "the hook is behind the gate: a parked call never reaches it");
        verify(approvalGate).recordToolCallForApproval(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void theClassificationGatesApprovalStillAppliesWhenHooksArePresent() {
        ToolDispatchGate classification = name -> ToolDispatchGate.Verdict.requireApproval("EGRESS irreversible");
        // Only a log hook: no gate hooks, so hooks.gate() returns the base unchanged and tighten() leaves the mode alone.
        AgentToolHooks hooks = hooks(List.of(), List.of(hook("log", HookPoint.BEFORE_TOOL, HookActionType.LOG, Map.of())),
            List.of(), List.of());

        String result = chain(hooks, classification, ApprovalGateMode.POLICY_DEFAULT).call("{}");

        assertTrue(result.contains("requires human approval"), result);
        assertTrue(raw.calls.isEmpty());
        assertSame(classification, hooks.gate(classification), "no gate hooks: the gate object is unchanged");
    }

    @Test
    void aBlockHookRefusesBeforeAnApprovalRequestIsEverRecorded() {
        when(approvalPolicy.requiresApproval("mcp_tool." + TOOL)).thenReturn(true);
        AgentToolHooks hooks = hooks(List.of(hook("no-send", HookPoint.BEFORE_TOOL, HookActionType.BLOCK, Map.of("reason", "Email is off"))),
            List.of(), List.of(), List.of());

        String result = chain(hooks, null, ApprovalGateMode.POLICY_DEFAULT).call("{}");

        assertTrue(result.startsWith("This tool call ('" + TOOL + "') was not executed"), result);
        assertTrue(result.contains("Email is off"), result);
        assertTrue(raw.calls.isEmpty());
        verify(approvalGate, never()).recordToolCallForApproval(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void aRequireApprovalHookParksTheCallEvenForAFullyAutonomousAgent() {
        // NEVER_GATE makes GuardedToolCallback ignore the gate's approval requirement; the hook steps the mode
        // back to POLICY_DEFAULT (the same degrade ALWAYS_CONFIRM_TOOLS get) so it actually holds.
        AgentToolHooks hooks = hooks(List.of(hook("ask", HookPoint.BEFORE_TOOL, HookActionType.REQUIRE_APPROVAL, Map.of())),
            List.of(), List.of(), List.of());

        assertEquals(ApprovalGateMode.POLICY_DEFAULT, hooks.tighten(ApprovalGateMode.NEVER_GATE));
        String result = chain(hooks, null, ApprovalGateMode.NEVER_GATE).call("{}");

        assertTrue(result.contains("requires human approval"), result);
        assertTrue(raw.calls.isEmpty());
    }

    @Test
    void tightenNeverLoosensAMode() {
        AgentToolHooks withApproval = hooks(List.of(hook("ask", HookPoint.BEFORE_TOOL, HookActionType.REQUIRE_APPROVAL, Map.of())),
            List.of(), List.of(), List.of());
        AgentToolHooks withBlock = hooks(List.of(hook("no", HookPoint.BEFORE_TOOL, HookActionType.BLOCK, Map.of())),
            List.of(), List.of(), List.of());

        assertEquals(ApprovalGateMode.ALWAYS_GATE, withApproval.tighten(ApprovalGateMode.ALWAYS_GATE));
        assertEquals(ApprovalGateMode.POLICY_DEFAULT, withApproval.tighten(ApprovalGateMode.POLICY_DEFAULT));
        assertEquals(ApprovalGateMode.NEVER_GATE, withBlock.tighten(ApprovalGateMode.NEVER_GATE), "a block needs no mode change");
    }

    @Test
    void anApprovedReplayStillRunsAndStillGetsItsHooks() {
        AgentToolHooks hooks = hooks(List.of(hook("ask", HookPoint.BEFORE_TOOL, HookActionType.REQUIRE_APPROVAL, Map.of())),
            List.of(hook("scrub", HookPoint.BEFORE_TOOL, HookActionType.REDACT, Map.of("patterns", List.of("email")))),
            List.of(), List.of());
        ToolCallback chain = chain(hooks, null, ApprovalGateMode.POLICY_DEFAULT);

        assertTrue(chain.call("{\"to\":\"jo@example.com\"}").contains("requires human approval"));
        assertTrue(raw.calls.isEmpty());

        // What AgentToolCallApprovalExecutor does once a human approved: a single-use grant for this action type.
        ApprovalReplayGrant.grant("mcp_tool." + TOOL);
        try {
            // The replay resolves its tools afresh (AgentToolCallApprovalExecutor#invokeTool), so it runs in
            // a new turn: a call parked in the original turn must not freeze the approved action itself.
            ToolCallback replay = chain(hooks, null, ApprovalGateMode.POLICY_DEFAULT,
                new java.util.concurrent.atomic.AtomicInteger());
            String result = replay.call("{\"to\":\"jo@example.com\"}");
            assertEquals("{\"sent\":true}", result);
        } finally {
            ApprovalReplayGrant.clear();
        }
        assertEquals(1, raw.calls.size());
        assertFalse(raw.calls.get(0).contains("jo@example.com"), "the replayed call still went through the redaction hook");
    }

    // ------------------------------------------------------------- inside the guard

    @Test
    void aBeforeToolHookRedactsTheArgumentsTheToolActuallyReceives() {
        AgentToolHooks hooks = hooks(List.of(), List.of(hook("scrub", HookPoint.BEFORE_TOOL, HookActionType.REDACT,
            Map.of("patterns", List.of("email")))), List.of(), List.of());

        String result = chain(hooks, null, ApprovalGateMode.NEVER_GATE).call("{\"to\":\"jo@example.com\"}");

        assertEquals("{\"sent\":true}", result);
        assertEquals(1, raw.calls.size());
        assertEquals("{\"to\":\"[REDACTED_EMAIL]\"}", raw.calls.get(0));
    }

    @Test
    void aHookDenialCountsAgainstTheStepCeilingLikeAnyOtherGuardedCall() {
        AgentToolHooks hooks = hooks(List.of(), List.of(hook("cap", HookPoint.BEFORE_TOOL, HookActionType.LIMIT,
            Map.of("maxPerRun", 1))), List.of(), List.of());
        ToolCallback chain = chain(hooks, null, ApprovalGateMode.NEVER_GATE);

        assertEquals("{\"sent\":true}", chain.call("{}"));
        String second = chain.call("{}");

        assertTrue(second.startsWith("This tool call ('" + TOOL + "') was not executed"), second);
        assertEquals(1, raw.calls.size());
        assertEquals(2, steps.get(), "the guard counted both attempts");
    }

    @Test
    void aToolFailureReachesOnErrorHooksAndStillPropagates() {
        raw.failure = new IllegalStateException("provider exploded");
        AgentToolHooks hooks = hooks(List.of(), List.of(), List.of(),
            List.of(hook("page", HookPoint.ON_ERROR, HookActionType.LOG, Map.of())));

        assertThrows(IllegalStateException.class, () -> chain(hooks, null, ApprovalGateMode.NEVER_GATE).call("{}"));

        verify(audit).record(anyString(), anyString(), anyString(), anyString(), any(), org.mockito.ArgumentMatchers.contains("decision=logged"));
    }

    // ----------------------------------------------------------------- after the guard

    @Test
    void anAfterToolNoteDoesNotBreakTheGuardsJsonValidation() {
        AgentToolHooks hooks = hooks(List.of(), List.of(), List.of(hook("note", HookPoint.AFTER_TOOL,
            HookActionType.APPEND_CONTEXT, Map.of("note", "Verify before acting."))), List.of());

        String result = chain(hooks, null, ApprovalGateMode.NEVER_GATE).call("{}");

        assertEquals("{\"sent\":true}\n\n[Workspace note] Verify before acting.", result,
            "the note is appended AFTER the guard validated the JSON, so validation is not tripped");
    }

    @Test
    void afterToolHooksSeeTheGuardSanitisedResultAndRedactIt() {
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> ((String) i.getArgument(1)).replace("sk-live", "[REDACTED]"));
        raw.output = "{\"key\":\"sk-live\",\"owner\":\"jo@example.com\"}";
        AgentToolHooks hooks = hooks(List.of(), List.of(), List.of(hook("scrub", HookPoint.AFTER_TOOL,
            HookActionType.REDACT, Map.of("patterns", List.of("email")))), List.of());

        String result = chain(hooks, null, ApprovalGateMode.NEVER_GATE).call("{}");

        assertEquals("{\"key\":\"[REDACTED]\",\"owner\":\"[REDACTED_EMAIL]\"}", result);
    }

    @Test
    void afterToolHooksLeaveAGuardRefusalAlone() {
        when(approvalPolicy.requiresApproval("mcp_tool." + TOOL)).thenReturn(true);
        AgentToolHooks hooks = hooks(List.of(), List.of(), List.of(
            hook("note", HookPoint.AFTER_TOOL, HookActionType.APPEND_CONTEXT, Map.of("note", "Should not appear")),
            hook("hide", HookPoint.AFTER_TOOL, HookActionType.BLOCK, Map.of("reason", "should not fire"))), List.of());

        String result = chain(hooks, null, ApprovalGateMode.POLICY_DEFAULT).call("{}");

        assertTrue(result.contains("requires human approval"), result);
        assertFalse(result.contains("Should not appear"));
        assertFalse(result.contains("withheld"));
    }

    @Test
    void anAfterToolBlockWithholdsARealResult() {
        AgentToolHooks hooks = hooks(List.of(), List.of(), List.of(
            hook("hide", HookPoint.AFTER_TOOL, HookActionType.BLOCK, Map.of("reason", "payroll data"))), List.of());

        String result = chain(hooks, null, ApprovalGateMode.NEVER_GATE).call("{}");

        assertTrue(result.startsWith("Tool output from '" + TOOL + "' was withheld"), result);
        assertFalse(result.contains("\"sent\""));
    }

    @Test
    void aToolWithNoApplicableHooksIsNotWrappedAtAll() {
        AgentToolHooks none = hooks(List.of(), List.of(), List.of(), List.of());

        assertTrue(none.isEmpty());
        assertSame(raw, none.inner(raw));
        ToolCallback guarded = mock(ToolCallback.class);
        assertSame(guarded, none.outer(guarded));
        ToolDispatchGate base = name -> ToolDispatchGate.Verdict.allow();
        assertSame(base, none.gate(base));
        assertEquals(ApprovalGateMode.NEVER_GATE, none.tighten(ApprovalGateMode.NEVER_GATE));
    }
}
