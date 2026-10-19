package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@link ChatPromptDirectives#delegationDirectiveBlock} - the 2026-10-01 audit
 * fix that puts the standing directives onto INNER model calls (agent-to-agent
 * delegation, specialist handoffs). Those calls resolve their own tool lists -
 * MCP tools included - but previously carried none of block 2, so every
 * incident-driven rail (fabricated claims, untrusted tool data, work-to-
 * completion) applied to outer turns only and stayed reproducible one
 * delegation hop deep.
 *
 * <p>Pure-gate tests only, same lighter-weight shape {@link
 * AgentChatServiceToolTrustDirectiveTest} uses.
 */
class ChatPromptDirectivesDelegationTest {

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
    void innerCallGetsToolTrustDirectiveWhenToolsAreOffered() {
        String block = ChatPromptDirectives.delegationDirectiveBlock(
            ChatPromptDirectives.DirectiveGates.allOn(), List.of(tool("search_code")), false);

        assertNotNull(block);
        assertTrue(block.toLowerCase(Locale.ROOT).contains("data, never instructions"),
            "delegated/specialist calls must carry the same tool-output honesty rail as outer turns");
    }

    @Test
    void innerCallGetsMcpWarningOnlyWhenMcpToolsArePresent() {
        String withMcp = ChatPromptDirectives.delegationDirectiveBlock(
            ChatPromptDirectives.DirectiveGates.allOn(), List.of(tool("github_list_issues")), true);
        String withoutMcp = ChatPromptDirectives.delegationDirectiveBlock(
            ChatPromptDirectives.DirectiveGates.allOn(), List.of(tool("search_code")), false);

        assertNotNull(withMcp);
        assertTrue(withMcp.contains("MCP servers"), "an inner call with MCP tools needs the untrusted-data warning");
        assertTrue(withoutMcp == null || !withoutMcp.contains("MCP servers"),
            "no MCP tools on the inner call means no MCP warning");
    }

    @Test
    void innerCallGetsMemoryDirectiveWhenRememberFactIsOffered() {
        String block = ChatPromptDirectives.delegationDirectiveBlock(
            ChatPromptDirectives.DirectiveGates.allOn(), List.of(tool("remember_fact")), false);

        assertNotNull(block);
        assertTrue(block.contains("remember_fact"));
    }

    @Test
    void killSwitchesSuppressTheirDirectivesOnInnerCalls() {
        // Every gate off -> nothing joins -> null, the same "no extra block at
        // all" shape joinMemoryBlocks produces on the outer path.
        ChatPromptDirectives.DirectiveGates allOff = new ChatPromptDirectives.DirectiveGates(
            () -> false, () -> false, () -> false);

        assertNull(ChatPromptDirectives.delegationDirectiveBlock(
            allOff, List.of(tool("remember_fact")), true));
    }

    @Test
    void innerCallWithNoToolsGetsNoDirectives() {
        // anyMcp=false: an empty/null tool list means no MCP tool was offered either.
        assertNull(ChatPromptDirectives.delegationDirectiveBlock(
            ChatPromptDirectives.DirectiveGates.allOn(), List.of(), false));
        assertNull(ChatPromptDirectives.delegationDirectiveBlock(
            ChatPromptDirectives.DirectiveGates.allOn(), null, false));
    }
}
