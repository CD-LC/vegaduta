package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Gap-analysis item 6: MCP tool order must not depend on discovery order (prompt-cache prefix
 * stability).
 */
class AgentMcpToolResolverOrderTest {

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("d")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void orderIsIndependentOfDiscoveryOrder() {
        List<ToolCallback> a =
                new ArrayList<>(List.of(tool("mcp__b__x"), tool("mcp__a__z"), tool("mcp__a__y")));
        List<ToolCallback> b = new ArrayList<>(a);
        Collections.reverse(b);

        assertEquals(
                List.of("mcp__a__y", "mcp__a__z", "mcp__b__x"),
                names(AgentMcpToolResolver.sortedByName(a)));
        assertEquals(
                names(AgentMcpToolResolver.sortedByName(a)),
                names(AgentMcpToolResolver.sortedByName(b)));
    }

    @Test
    void emptyListStaysEmpty() {
        assertEquals(List.of(), AgentMcpToolResolver.sortedByName(List.of()));
    }
}
