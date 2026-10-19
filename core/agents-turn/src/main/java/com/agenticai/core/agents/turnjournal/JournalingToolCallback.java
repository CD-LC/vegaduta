package com.agenticai.core.agents.turnjournal;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Decorator that journals each invocation of a tool (name, argument hash, outcome digest). */
final class JournalingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final TurnRecorder recorder;

    JournalingToolCallback(ToolCallback delegate, TurnRecorder recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return journaled(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return journaled(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String journaled(String input, java.util.function.Supplier<String> call) {
        long started = System.currentTimeMillis();
        String name = delegate.getToolDefinition().name();
        try {
            String result = call.get();
            recorder.toolCall(name, input, result, true, System.currentTimeMillis() - started);
            return result;
        } catch (RuntimeException e) {
            recorder.toolCall(name, input, e.getClass().getSimpleName(), false, System.currentTimeMillis() - started);
            throw e;
        }
    }
}
