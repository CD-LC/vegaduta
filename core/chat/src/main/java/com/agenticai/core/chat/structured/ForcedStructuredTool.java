package com.agenticai.core.chat.structured;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A tool that only exists to carry a schema for {@link StructuredOutput#withAnthropicForcedTool}:
 * never executed (the request is marked {@code CallerRunsToolLoop}).
 */
record ForcedStructuredTool(String name, String inputSchema) implements ToolCallback {
    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(name)
                .description(
                        "Return the final structured result. Call this tool exactly once with the"
                                + " complete answer.")
                .inputSchema(inputSchema)
                .build();
    }

    @Override
    public String call(String toolInput) {
        throw new IllegalStateException(
                "structured-output pseudo tool '" + name + "' is never executed");
    }
}
