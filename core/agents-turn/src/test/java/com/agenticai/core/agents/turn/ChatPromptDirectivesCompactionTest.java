package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** The context-compaction directive exists only while the feature is on and the turn has tools. */
class ChatPromptDirectivesCompactionTest {

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("d").inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
    }

    @Test
    void flagOffMeansNoDirectiveWhateverTheTools() {
        assertNull(ChatPromptDirectives.contextCompactionDirective(false, List.of(tool("search"), tool("restore_tool_result"))));
    }

    @Test
    void aTurnWithoutToolsHasNoToolResultsToClearSoNoDirective() {
        assertNull(ChatPromptDirectives.contextCompactionDirective(true, List.of()));
        assertNull(ChatPromptDirectives.contextCompactionDirective(true, null));
    }

    @Test
    void withTheRestoreToolOfferedTheDirectiveNamesIt() {
        String directive = ChatPromptDirectives.contextCompactionDirective(true, List.of(tool("search"), tool("restore_tool_result")));

        assertNotNull(directive);
        assertTrue(directive.contains("restore_tool_result"));
        assertTrue(directive.contains("context_cleared"));
    }

    @Test
    void withoutTheRestoreToolTheDirectiveOnlySaysToRunTheToolAgain() {
        String directive = ChatPromptDirectives.contextCompactionDirective(true, List.of(tool("search")));

        assertNotNull(directive);
        assertTrue(!directive.contains("restore_tool_result"));
        assertTrue(directive.contains("run the tool again"));
    }

    @Test
    void theTextIsAByteStableConstantPerVariant() {
        List<ToolCallback> withRestore = List.of(tool("a"), tool("restore_tool_result"));
        List<ToolCallback> without = List.of(tool("b"));

        assertEquals(
            ChatPromptDirectives.contextCompactionDirective(true, withRestore),
            ChatPromptDirectives.contextCompactionDirective(true, List.of(tool("zzz"), tool("restore_tool_result"))));
        assertEquals(
            ChatPromptDirectives.contextCompactionDirective(true, without),
            ChatPromptDirectives.contextCompactionDirective(true, List.of(tool("c"), tool("d"))));
        assertNotEquals(
            ChatPromptDirectives.contextCompactionDirective(true, withRestore),
            ChatPromptDirectives.contextCompactionDirective(true, without));
    }
}
