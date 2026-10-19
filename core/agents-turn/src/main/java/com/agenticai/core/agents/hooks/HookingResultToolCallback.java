package com.agenticai.core.agents.hooks;

import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The outer half of a tool's hook wrappers: sits around the guard chain ({@code GuardedToolCallback}) and
 * applies the {@code AFTER_TOOL} hooks to what the model is about to see - the result AFTER the guard's output
 * validation and content sanitisation. Running after the guard is deliberate on two counts:
 * <ul>
 *   <li>an appended note must not break the guard's "looks like JSON but does not parse" validation, which it
 *       would if it ran inside; and</li>
 *   <li>the hooks then act on exactly the text the model receives, so a redaction cannot be undone by a later
 *       stage and a withhold covers the final text.</li>
 * </ul>
 * It acts only when the raw tool really ran (see {@link ToolRunMarker}); a guard's own refusal or pending-approval
 * message passes through untouched. It can only redact, annotate or withhold - it never turns a refusal into a result.
 */
public final class HookingResultToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final HookEngine engine;
    private final HookCtx ctx;
    private final String tool;
    private final List<EffectiveHook> afterHooks;

    HookingResultToolCallback(ToolCallback delegate, HookEngine engine, HookCtx ctx, String tool, List<EffectiveHook> afterHooks) {
        this.delegate = delegate;
        this.engine = engine;
        this.ctx = ctx;
        this.tool = tool;
        this.afterHooks = afterHooks;
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
        boolean enclosing = ToolRunMarker.begin();
        String output;
        boolean ran;
        try {
            output = toolContext != null ? delegate.call(toolInput, toolContext) : delegate.call(toolInput);
        } finally {
            ran = ToolRunMarker.end(enclosing);
        }
        if (!ran) {
            return output;
        }
        return engine.afterTool(ctx, afterHooks, tool, output);
    }
}
