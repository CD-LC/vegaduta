package com.agenticai.core.chat;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Re-throws a callback's non-{@link ToolExecutionException} runtime failure
 * as a {@code ToolExecutionException}, so Spring AI's manager routes it
 * through the exception processor instead of letting it escape and kill the
 * turn. Deliberate stop conditions ({@link
 * RedactingToolExecutionExceptionProcessor#isPropagated}) and {@link Error}s
 * pass through untouched. Delegates {@link #getToolMetadata()} so a tool's
 * {@code returnDirect} flag is preserved, and forwards the {@link
 * ToolContext} rather than dropping it. Installed per request by
 * {@link ArgumentSanitizingToolCallingManager#withExceptionTranslation}.
 */
final class ToolExceptionTranslatingCallback implements ToolCallback {

    private final ToolCallback delegate;

    ToolExceptionTranslatingCallback(ToolCallback delegate) {
        this.delegate = delegate;
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
        try {
            return delegate.call(toolInput);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            return delegate.call(toolInput, toolContext);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    private RuntimeException translate(RuntimeException e) {
        if (RedactingToolExecutionExceptionProcessor.isPropagated(e)) {
            return e;
        }
        return new ToolExecutionException(delegate.getToolDefinition(), e);
    }
}
