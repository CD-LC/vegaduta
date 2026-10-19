package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** The per-call shaping of a delegated turn: a worker directive and a tool-name filter. */
class DelegateOptionsTest {

    private static ToolCallback tool(String name) {
        ToolDefinition definition =
                ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }

    @Test
    void noOptionsLeavesToolsAndPromptUntouched() {
        List<ToolCallback> tools = List.of(tool("web_search"));
        assertSame(tools, DelegateOptions.filter(null, tools));
        assertSame(tools, DelegateOptions.filter(new DelegateOptions("x", null), tools));
        assertEquals("base", DelegateOptions.append(null, "base"));
        assertEquals("base", DelegateOptions.append(new DelegateOptions("  ", null), "base"));
    }

    @Test
    void theFilterKeepsOnlyWhatThePredicateAccepts() {
        List<ToolCallback> tools =
                List.of(tool("web_search"), tool("run_subtasks"), tool("local_shell_read"));
        List<ToolCallback> kept =
                DelegateOptions.filter(
                        new DelegateOptions(null, name -> !name.equals("run_subtasks")), tools);
        assertEquals(
                List.of("web_search", "local_shell_read"),
                kept.stream().map(t -> t.getToolDefinition().name()).toList());
    }

    @Test
    void theAddendumIsAppendedAsItsOwnBlock() {
        assertEquals(
                "base\n\nYou are a worker.",
                DelegateOptions.append(new DelegateOptions("You are a worker.", null), "base"));
    }
}
