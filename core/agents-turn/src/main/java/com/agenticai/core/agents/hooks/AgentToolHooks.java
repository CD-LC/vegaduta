package com.agenticai.core.agents.hooks;

import com.agenticai.core.guardrails.ApprovalGateMode;
import com.agenticai.core.guardrails.ToolDispatchGate;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * The hooks that apply to ONE tool on ONE turn, resolved once when the tool's wrapper chain is built, plus the
 * four small adapters {@code AgentToolGuard#guardAndLog} uses to splice them into that chain:
 *
 * <pre>
 *   StepEvent -&gt; TenantContext -&gt; Logging -&gt; [outer: AFTER_TOOL] -&gt; Guarded -&gt; [inner: BEFORE_TOOL, ON_ERROR] -&gt; raw tool
 *                                                  ^ dispatch gate gains [block, require_approval]
 * </pre>
 *
 * <p>Everything here is optional: {@code AgentHookRuntime#toolHooks} returns {@code null} when the feature is off or
 * no hook applies to the tool, and the guard then builds exactly the chain it always built. Each adapter also
 * returns its input unchanged when it has nothing to add.
 */
public final class AgentToolHooks {

    private final HookEngine engine;
    private final HookCtx ctx;
    private final String tool;
    private final List<EffectiveHook> gateHooks;
    private final List<EffectiveHook> preHooks;
    private final List<EffectiveHook> afterHooks;
    private final List<EffectiveHook> errorHooks;
    private final HookRateLimiter.RunCounters run;

    AgentToolHooks(
        HookEngine engine, HookCtx ctx, String tool,
        List<EffectiveHook> gateHooks, List<EffectiveHook> preHooks, List<EffectiveHook> afterHooks,
        List<EffectiveHook> errorHooks, HookRateLimiter.RunCounters run
    ) {
        this.engine = engine;
        this.ctx = ctx;
        this.tool = tool;
        this.gateHooks = List.copyOf(gateHooks);
        this.preHooks = List.copyOf(preHooks);
        this.afterHooks = List.copyOf(afterHooks);
        this.errorHooks = List.copyOf(errorHooks);
        this.run = run;
    }

    /** True when no hook applies to this tool at all. */
    boolean isEmpty() {
        return gateHooks.isEmpty() && preHooks.isEmpty() && afterHooks.isEmpty() && errorHooks.isEmpty();
    }

    /** Wraps the RAW tool (inside the guard) with BEFORE_TOOL arguments-hooks, ON_ERROR, and the ran-marker. */
    public ToolCallback inner(ToolCallback raw) {
        if (preHooks.isEmpty() && afterHooks.isEmpty() && errorHooks.isEmpty()) {
            return raw;
        }
        return new HookingToolCallback(raw, engine, ctx, tool, preHooks, errorHooks, run, !afterHooks.isEmpty());
    }

    /** Wraps the guarded tool (outside the guard, inside logging) with the AFTER_TOOL hooks. */
    public ToolCallback outer(ToolCallback guarded) {
        if (afterHooks.isEmpty()) {
            return guarded;
        }
        return new HookingResultToolCallback(guarded, engine, ctx, tool, afterHooks);
    }

    /** The dispatch gate with {@code block} / {@code require_approval} folded in (stricter-of, never looser). */
    public ToolDispatchGate gate(ToolDispatchGate base) {
        if (gateHooks.isEmpty()) {
            return base;
        }
        return new HookDispatchGate(base, engine, ctx, tool, gateHooks);
    }

    /**
     * A {@code require_approval} hook must hold for a fully-autonomous agent too, whose {@code NEVER_GATE} mode makes
     * {@code GuardedToolCallback} ignore the classification gate's approval requirement. For that one case the mode
     * steps back to {@code POLICY_DEFAULT} - the same degrade {@code AgentToolGuard} already applies to
     * {@code ALWAYS_CONFIRM_TOOLS}. Any other mode is returned as is; this can only ever make the gate stricter.
     */
    public ApprovalGateMode tighten(ApprovalGateMode mode) {
        if (mode == ApprovalGateMode.NEVER_GATE && hasApprovalHook()) {
            return ApprovalGateMode.POLICY_DEFAULT;
        }
        return mode;
    }

    private boolean hasApprovalHook() {
        for (EffectiveHook eh : gateHooks) {
            if (eh.def().action() == HookActionType.REQUIRE_APPROVAL) {
                return true;
            }
        }
        return false;
    }
}
