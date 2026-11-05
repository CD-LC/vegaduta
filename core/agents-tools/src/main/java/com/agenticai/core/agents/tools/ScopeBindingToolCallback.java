package com.agenticai.core.agents.tools;

import com.agenticai.core.guardrails.ToolResultMarker;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The outermost wrapper of a turn's tool chain: binds the turn's {@link ToolResultMarker.Scope} to
 * the calling thread for the whole call, so every layer below (the guard's chain, the redactor, the
 * sanitizers) registers and reads the platform's answers under THIS turn and a tool in another turn
 * that prints the same words is not mistaken for them. The binding is per call and thread-local; a
 * layer that hops threads registers unscoped (visible to all) there, which is the pre-scope
 * behaviour.
 */
final class ScopeBindingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolResultMarker.Scope scope;

    ScopeBindingToolCallback(ToolCallback delegate, ToolResultMarker.Scope scope) {
        this.delegate = delegate;
        this.scope = scope;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        try (ToolResultMarker.Binding ignored = ToolResultMarker.bind(scope)) {
            return delegate.call(toolInput);
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try (ToolResultMarker.Binding ignored = ToolResultMarker.bind(scope)) {
            return delegate.call(toolInput, toolContext);
        }
    }
}
