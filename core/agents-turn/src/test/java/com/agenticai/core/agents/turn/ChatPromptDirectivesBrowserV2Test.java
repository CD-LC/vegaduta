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
 * The browser agent v2 directive: only on a turn whose tool list carries {@code browser_snapshot} (so flag-off
 * and non-browser agents pay zero tokens), a byte-stable constant, wired into the inner-call path, and behind
 * its own kill-switch gate.
 */
class ChatPromptDirectivesBrowserV2Test {

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
    void noDirectiveWithoutTheSnapshotTool() {
        // the five legacy browser tools alone must not trigger it (flag-off parity)
        assertNull(ChatPromptDirectives.browserV2Directive(List.of(
            tool("browser_navigate"), tool("browser_click"), tool("browser_type"),
            tool("browser_read_page_text"), tool("browser_screenshot"))));
        assertNull(ChatPromptDirectives.browserV2Directive(List.of()));
        assertNull(ChatPromptDirectives.browserV2Directive(null));
    }

    @Test
    void directiveIsSentWhenTheSnapshotToolIsOnTheList() {
        String directive = ChatPromptDirectives.browserV2Directive(List.of(tool("browser_navigate"), tool("browser_snapshot")));
        assertNotNull(directive);
        assertTrue(directive.contains("browser_snapshot"));
        assertTrue(directive.contains("browser_confirm_click"));
        assertTrue(directive.contains("{{secret.NAME}}"));
    }

    @Test
    void directiveIsAByteStableConstantWithNoPerTurnData() {
        String first = ChatPromptDirectives.browserV2Directive(List.of(tool("browser_snapshot"), tool("x_one")));
        String second = ChatPromptDirectives.browserV2Directive(
            List.of(tool("browser_snapshot"), tool("browser_click_element"), tool("x_two"), tool("x_three")));
        assertSame(first, second, "must not interpolate tool names or counts (prompt-prefix caching)");
        assertFalse(first.contains("x_one"));
    }

    @Test
    void innerCallsCarryItOnlyWhenTheToolIsOfferedAndTheGateIsOn() {
        ChatPromptDirectives.DirectiveGates on = ChatPromptDirectives.DirectiveGates.allOn();
        ChatPromptDirectives.DirectiveGates browserOff = new ChatPromptDirectives.DirectiveGates(
            () -> true, () -> true, () -> true, () -> true, () -> false);

        String active = ChatPromptDirectives.delegationDirectiveBlock(on, List.of(tool("browser_snapshot")), false);
        String inactive = ChatPromptDirectives.delegationDirectiveBlock(on, List.of(tool("google_gmail")), false);
        String gatedOff = ChatPromptDirectives.delegationDirectiveBlock(browserOff, List.of(tool("browser_snapshot")), false);

        assertNotNull(active);
        assertTrue(active.contains("browser_snapshot"));
        assertTrue(inactive == null || !inactive.contains("browser_snapshot"));
        assertTrue(gatedOff == null || !gatedOff.contains("browser_confirm_click"));
    }

    @Test
    void theFourGateConstructorKeepsTheBrowserDirectiveOn() {
        ChatPromptDirectives.DirectiveGates legacy = new ChatPromptDirectives.DirectiveGates(
            () -> false, () -> false, () -> false, () -> false);
        assertTrue(legacy.browserV2().getAsBoolean());
        assertTrue(ChatPromptDirectives.DirectiveGates.allOn().browserV2().getAsBoolean());
    }
}
