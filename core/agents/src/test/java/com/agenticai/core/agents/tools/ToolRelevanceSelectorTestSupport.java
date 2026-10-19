package com.agenticai.core.agents.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Shared fake {@link ToolCallback} builders for the {@link ToolRelevanceSelector} tests. Split from the former ToolRelevanceSelectorTest. */
abstract class ToolRelevanceSelectorTestSupport {

    static ToolCallback tool(String name, String description) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(description)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException("not exercised by this test");
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    /**
     * Same as {@link #tool} but with a caller-controlled schema, for exercising {@code
     * estimateSchemaTokens}.
     */
    static ToolCallback toolWithSchema(
            String name, String description, String inputSchema) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(inputSchema)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException("not exercised by this test");
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }
}
