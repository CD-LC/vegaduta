package com.agenticai.core.agents.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.guardrails.ToolDispatchGate;
import com.agenticai.core.guardrails.ToolDispatchGate.Outcome;
import com.agenticai.core.guardrails.ToolDispatchGate.Verdict;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The tighten-only invariant at the dispatch gate: whatever the classification gate (or any other base gate)
 * decided, the hooks can only make the verdict stricter.
 */
class HookDispatchGateTest {

    private static final UUID AGENT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final AuditLogService audit = mock(AuditLogService.class);

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private HookEngine engine() {
        ObjectProvider<MeterRegistry> noMeters = provider(null);
        return new HookEngine(
            new HookWebhookClient(null, List.of(), "", (u, p, h, t) -> new HookWebhookClient.Response(200, "{}")),
            new HookRateLimiter(), new HookDecisionSink(provider(audit), noMeters), 2_000, 5_000, Runnable::run);
    }

    private static EffectiveHook hook(String id, HookActionType action, boolean enabled) {
        return new EffectiveHook("agent", new HookDefinition(id, enabled, HookPoint.BEFORE_TOOL, HookMatch.ANY, action,
            Map.of("reason", "because"), FailMode.CLOSED, 2_000));
    }

    private HookDispatchGate gate(ToolDispatchGate base, EffectiveHook... hooks) {
        HookCtx ctx = new HookCtx(AGENT, "s", "tenant-a", null, null, List.of());
        return new HookDispatchGate(base, engine(), ctx, "send_email", List.of(hooks));
    }

    @Test
    void combineIsTheStricterOfTheTwoForEveryPair() {
        for (Outcome base : Outcome.values()) {
            for (Outcome hook : Outcome.values()) {
                Verdict b = new Verdict(base, "base");
                Verdict h = new Verdict(hook, "hook");

                Verdict combined = HookDispatchGate.combine(b, h);

                int expected = Math.max(HookDispatchGate.rank(b), HookDispatchGate.rank(h));
                assertEquals(expected, HookDispatchGate.rank(combined), base + " + " + hook);
                assertTrue(HookDispatchGate.rank(combined) >= HookDispatchGate.rank(b), "never weaker than the base");
                assertTrue(HookDispatchGate.rank(combined) >= HookDispatchGate.rank(h), "never weaker than the hook");
            }
        }
    }

    @Test
    void aTieKeepsTheBaseVerdictAndItsReason() {
        Verdict base = Verdict.requireApproval("irreversible send");
        Verdict hook = Verdict.requireApproval("a hook asks too");

        assertSame(base, HookDispatchGate.combine(base, hook));
    }

    @Test
    void nullsReadAsAllow() {
        assertEquals(Outcome.ALLOW, HookDispatchGate.combine(null, null).outcome());
        assertEquals(Outcome.DENY, HookDispatchGate.combine(null, Verdict.deny("x")).outcome());
    }

    @Test
    void aHookCannotLiftTheClassificationGatesApproval() {
        // The only hooks this gate ever carries are block / require_approval, so the strongest "allow-ish" input
        // is a gate holding a disabled hook: the base verdict must come through untouched.
        Verdict base = Verdict.requireApproval("EGRESS irreversible");
        HookDispatchGate gate = gate(name -> base, hook("off", HookActionType.BLOCK, false));

        Verdict v = gate.check("send_email");

        assertSame(base, v);
    }

    @Test
    void aBaseDenyIsNeverDowngradedToAnApprovalRequirement() {
        Verdict base = Verdict.deny("quarantined");
        HookDispatchGate gate = gate(name -> base, hook("ask", HookActionType.REQUIRE_APPROVAL, true));

        Verdict v = gate.check("send_email");

        assertSame(base, v);
        verifyNoInteractions(audit);
    }

    @Test
    void aHookDenyTightensAnAllow() {
        HookDispatchGate gate = gate(name -> Verdict.allow(), hook("no-send", HookActionType.BLOCK, true));

        Verdict v = gate.check("send_email");

        assertEquals(Outcome.DENY, v.outcome());
        assertTrue(v.reason().contains("agent:no-send"));
        verify(audit).record(anyString(), eq("agent.hook.decision"), eq("agent"), eq(AGENT.toString()), eq(AuditOutcome.REJECTED), anyString());
    }

    @Test
    void aHookApprovalTightensAnAllowAndIsRecordedAsPending() {
        HookDispatchGate gate = gate(name -> Verdict.allow(), hook("ask", HookActionType.REQUIRE_APPROVAL, true));

        Verdict v = gate.check("send_email");

        assertEquals(Outcome.REQUIRE_APPROVAL, v.outcome());
        verify(audit).record(anyString(), eq("agent.hook.decision"), eq("agent"), anyString(), eq(AuditOutcome.PENDING_APPROVAL), anyString());
    }

    @Test
    void aBaseThatThrowsIsReadAsAllowButTheHooksStillApply() {
        // GuardedToolCallback treats a throwing gate as allow (fail-open). Without this catch a failing
        // classification lookup would also swallow the hooks.
        HookDispatchGate gate = gate(name -> {
            throw new IllegalStateException("registry down");
        }, hook("no-send", HookActionType.BLOCK, true));

        assertEquals(Outcome.DENY, gate.check("send_email").outcome());
    }

    @Test
    void aMissingBaseGateIsFine() {
        assertEquals(Outcome.REQUIRE_APPROVAL, gate(null, hook("ask", HookActionType.REQUIRE_APPROVAL, true)).check("t").outcome());
        verify(audit, never()).record(anyString(), eq("agent.hook.decision"), eq("agent"), anyString(), eq(AuditOutcome.REJECTED), anyString());
    }
}
