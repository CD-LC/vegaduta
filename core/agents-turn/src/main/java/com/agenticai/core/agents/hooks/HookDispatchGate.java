package com.agenticai.core.agents.hooks;

import com.agenticai.core.guardrails.ToolDispatchGate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Folds the {@code block} and {@code require_approval} hooks into the dispatch gate every guarded tool call
 * already consults ({@code GuardedToolCallback} asks it before the verifier, the procedures and the approval gate).
 *
 * <p><b>Tighten-only, by construction.</b> The verdict is the stricter of the wrapped gate's verdict and the hooks'
 * (DENY over REQUIRE_APPROVAL over ALLOW) - see {@link #combine}. A hook can add a refusal or an approval
 * requirement; it can never remove one the classification gate or any other check imposed. Because this runs
 * inside {@code GuardedToolCallback}, the confirmation gate, classification, procedures and step ceiling all still
 * run on the call; a hook has no way to skip them.
 *
 * <p>The wrapped gate is called under its own catch: {@code GuardedToolCallback} treats a throwing gate as
 * "allow" (fail-open), and without this catch a failure in the classification lookup would also swallow the hooks.
 * Here the base failing is read as "allow" - exactly what it would have been - while the hooks still apply.
 */
public final class HookDispatchGate implements ToolDispatchGate {

    private static final Logger log = LoggerFactory.getLogger(HookDispatchGate.class);

    private final ToolDispatchGate base;
    private final HookEngine engine;
    private final HookCtx ctx;
    private final String tool;
    private final List<EffectiveHook> gateHooks;

    HookDispatchGate(ToolDispatchGate base, HookEngine engine, HookCtx ctx, String tool, List<EffectiveHook> gateHooks) {
        this.base = base;
        this.engine = engine;
        this.ctx = ctx;
        this.tool = tool;
        this.gateHooks = gateHooks;
    }

    @Override
    public Verdict check(String toolName) {
        Verdict baseVerdict = Verdict.allow();
        if (base != null) {
            try {
                Verdict v = base.check(toolName);
                if (v != null) {
                    baseVerdict = v;
                }
            } catch (RuntimeException e) {
                log.warn("Tool dispatch gate failed for tool '{}' - treating it as allow (fail-open); hooks still apply", toolName, e);
            }
        }
        Verdict hookVerdict = engine.gateVerdict(gateHooks);
        Verdict combined = combine(baseVerdict, hookVerdict);
        // Recorded only when a hook is what tightened the outcome: a call the classification gate already refused
        // or parked is not the hook's decision, and a replay of an approved call is not re-reported as new.
        if (combined == hookVerdict && rank(hookVerdict) > rank(baseVerdict)) {
            EffectiveHook deciding = engine.decidingGateHook(gateHooks, hookVerdict.outcome());
            if (deciding != null) {
                engine.recordGateDecision(ctx, deciding, hookVerdict.outcome(), tool, hookVerdict.reason());
            }
        }
        return combined;
    }

    /**
     * The stricter of two verdicts; on a tie the base verdict wins so its reason is kept. Never weaker than either
     * input - the invariant the tests pin.
     */
    public static Verdict combine(Verdict base, Verdict hook) {
        Verdict b = base == null ? Verdict.allow() : base;
        Verdict h = hook == null ? Verdict.allow() : hook;
        return rank(h) > rank(b) ? h : b;
    }

    static int rank(Verdict v) {
        return switch (v.outcome()) {
            case DENY -> 2;
            case REQUIRE_APPROVAL -> 1;
            case ALLOW -> 0;
        };
    }
}
