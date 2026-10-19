package com.agenticai.core.agents.hooks;

import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The inner half of a tool's hook wrappers: sits directly around the RAW tool, so it is reached only after
 * the guard chain ({@code GuardedToolCallback}: step ceiling, classification gate, procedures, approval gate)
 * has already let the call through. That placement is what makes hooks tighten-only: nothing here runs for a
 * call the gate refused or parked, and nothing here can approve anything.
 *
 * <p>Runs the {@code BEFORE_TOOL} hooks that need the arguments or the clock (limit, redact, webhook, log) and
 * the {@code ON_ERROR} hooks, and tells the outer half that the tool ran. {@code block} and
 * {@code require_approval} are not evaluated here - they are decided earlier, at the dispatch gate
 * ({@link HookDispatchGate}), so a blocked call never reaches the approval inbox and an approved replay is not
 * re-gated by this layer.
 *
 * <p>A hook-denied call returns a tool result the model can react to (the same soft-stop posture as every other
 * refusal in the chain); it is never thrown.
 */
public final class HookingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final HookEngine engine;
    private final HookCtx ctx;
    private final String tool;
    private final List<EffectiveHook> preHooks;
    private final List<EffectiveHook> errorHooks;
    private final HookRateLimiter.RunCounters run;
    private final boolean markRan;

    HookingToolCallback(
        ToolCallback delegate, HookEngine engine, HookCtx ctx, String tool,
        List<EffectiveHook> preHooks, List<EffectiveHook> errorHooks, HookRateLimiter.RunCounters run, boolean markRan
    ) {
        this.delegate = delegate;
        this.engine = engine;
        this.ctx = ctx;
        this.tool = tool;
        this.preHooks = preHooks;
        this.errorHooks = errorHooks;
        this.run = run;
        this.markRan = markRan;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return invoke(toolInput, null);
    }

    /** Forwards Spring AI's {@link ToolContext} to the wrapped tool instead of dropping it. */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return invoke(toolInput, toolContext);
    }

    private String invoke(String toolInput, ToolContext toolContext) {
        String args = toolInput;
        if (!preHooks.isEmpty()) {
            HookEngine.ToolPre pre = engine.beforeTool(ctx, preHooks, tool, toolInput, run);
            if (pre.denied()) {
                return pre.denial();
            }
            args = pre.args();
        }
        String output;
        try {
            output = toolContext != null ? delegate.call(args, toolContext) : delegate.call(args);
        } catch (RuntimeException e) {
            if (!errorHooks.isEmpty()) {
                engine.onToolError(ctx, errorHooks, tool, e);
            }
            throw e;
        }
        if (markRan) {
            ToolRunMarker.ran();
        }
        return output;
    }
}
