package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The on-demand tool loading directive: present only on a turn whose tool list carries {@code
 * search_tools} (i.e. deferral is active), byte-stable, wired into the inner-call path, gated by its
 * own kill switch.
 */
class ChatPromptDirectivesToolSearchTest {

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException("not exercised by this test");
            }
        };
    }

    @Test
    void noDirectiveWithoutSearchTools() {
        assertNull(ChatPromptDirectives.toolSearchDirective(List.of(tool("remember_fact"), tool("google_gmail"))));
        assertNull(ChatPromptDirectives.toolSearchDirective(List.of()));
        assertNull(ChatPromptDirectives.toolSearchDirective(null));
    }

    @Test
    void directiveIsSentWhenSearchToolsIsOnTheList() {
        String directive = ChatPromptDirectives.toolSearchDirective(List.of(tool("search_tools"), tool("call_tool")));

        assertNotNull(directive);
        assertTrue(directive.contains("search_tools"));
        assertTrue(directive.contains("call_tool"));
    }

    @Test
    void directiveIsAByteStableConstantWithNoPerTurnData() {
        String first = ChatPromptDirectives.toolSearchDirective(List.of(tool("search_tools"), tool("a_tool_name")));
        String second = ChatPromptDirectives.toolSearchDirective(
            List.of(tool("search_tools"), tool("call_tool"), tool("totally_different_tool"), tool("another")));

        assertSame(first, second, "the directive must not interpolate tool names or counts (prompt-prefix caching)");
        assertFalse(first.contains("a_tool_name"));
    }

    @Test
    void innerCallsCarryTheDirectiveOnlyWhenDeferralIsActiveAndTheGateIsOn() {
        ChatPromptDirectives.DirectiveGates on = ChatPromptDirectives.DirectiveGates.allOn();
        ChatPromptDirectives.DirectiveGates off = new ChatPromptDirectives.DirectiveGates(
            () -> false, () -> false, () -> false, () -> false);

        String active = ChatPromptDirectives.delegationDirectiveBlock(on, List.of(tool("search_tools")), false);
        String inactive = ChatPromptDirectives.delegationDirectiveBlock(on, List.of(tool("google_gmail")), false);
        String gatedOff = ChatPromptDirectives.delegationDirectiveBlock(off, List.of(tool("search_tools")), false);

        assertNotNull(active);
        assertTrue(active.contains("search_tools"));
        assertTrue(inactive == null || !inactive.contains("search_tools"));
        assertNull(gatedOff);
    }

    @Test
    void theThreeGateConstructorKeepsTheToolSearchDirectiveOn() {
        ChatPromptDirectives.DirectiveGates legacy = new ChatPromptDirectives.DirectiveGates(
            () -> false, () -> false, () -> false);

        assertTrue(legacy.toolSearch().getAsBoolean());
        assertTrue(ChatPromptDirectives.DirectiveGates.allOn().toolSearch().getAsBoolean());
    }

    @Test
    void aTurnWithSearchToolsIsTreatedAsMaybeOfferingAGenerationTool() {
        // call_tool can reach a deferred generate_image, so the fabricated-URL buffering must stay on.
        assertTrue(ChatPromptDirectives.offersGenerationTool(List.of(tool("search_tools"), tool("call_tool"))));
        assertTrue(ChatPromptDirectives.offersGenerationTool(List.of(tool("generate_image"))));
        assertFalse(ChatPromptDirectives.offersGenerationTool(List.of(tool("google_gmail"))));
        assertFalse(ChatPromptDirectives.offersGenerationTool(null));
        assertFalse(ChatPromptDirectives.offersGenerationTool(List.of()));
    }
}
