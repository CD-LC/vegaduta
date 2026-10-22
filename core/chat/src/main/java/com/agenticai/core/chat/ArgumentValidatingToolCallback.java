package com.agenticai.core.chat;

import java.util.function.BiConsumer;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs {@link ToolArgumentValidator} on the input and, when it fails, returns the feedback as the
 * tool's result instead of calling the tool. Delegates {@link #getToolMetadata()} so {@code
 * returnDirect} is preserved and forwards the {@link ToolContext}. Installed per request by {@link
 * ArgumentSanitizingToolCallingManager#withArgumentValidation(org.springframework.ai.chat.prompt.Prompt)}.
 */
final class ArgumentValidatingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ObjectMapper objectMapper;
    private final BiConsumer<String, ToolArgumentValidator.Result> onRejected;

    ArgumentValidatingToolCallback(
            ToolCallback delegate,
            ObjectMapper objectMapper,
            BiConsumer<String, ToolArgumentValidator.Result> onRejected) {
        this.delegate = delegate;
        this.objectMapper = objectMapper;
        this.onRejected = onRejected;
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
        String rejection = reject(toolInput);
        return rejection != null ? rejection : delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String rejection = reject(toolInput);
        return rejection != null ? rejection : delegate.call(toolInput, toolContext);
    }

    private String reject(String toolInput) {
        ToolDefinition definition = delegate.getToolDefinition();
        ToolArgumentValidator.Result result =
                ToolArgumentValidator.validate(toolInput, definition.inputSchema(), objectMapper);
        if (result.valid()) {
            return null;
        }
        onRejected.accept(definition.name(), result);
        return ToolArgumentValidator.feedback(
                definition.name(), result, definition.inputSchema(), objectMapper);
    }
}
