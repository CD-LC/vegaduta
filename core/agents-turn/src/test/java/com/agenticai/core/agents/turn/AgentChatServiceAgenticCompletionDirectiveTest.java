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
 * {@link AgentChatService#agenticCompletionDirective} - the multi-step
 * completion companion to {@link AgentChatService#toolTrustDirective}: that
 * directive fixes capability denial and fabricated usage claims, this one
 * fixes agents making one tool call (or none) and handing a task back
 * half-done instead of chaining tool calls to completion. Added alongside the
 * {@code fetch_web_page} built-in, whose typical use (fetch, maybe re-fetch,
 * then answer) is inherently multi-step.
 *
 * <p>Pure-gate test only, deliberately mirroring {@link
 * AgentChatServiceToolTrustDirectiveTest}'s lighter-weight shape - see {@code
 * AgentChatServiceMcpUntrustedDataDirectiveTest} for the heavier
 * end-to-end-wired pattern this class does not duplicate.
 */
class AgentChatServiceAgenticCompletionDirectiveTest {

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
    void completionRuleIsMergedIntoToolTrustDirective() {
        // Merged into one tight rule to cut per-turn prompt tokens.
        assertNull(ChatPromptDirectives.agenticCompletionDirective(List.of(tool("fetch_web_page"))));
        String merged = ChatPromptDirectives.toolTrustDirective(List.of(tool("fetch_web_page")));
        assertNotNull(merged);
        String lower = merged.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("completion"));
        assertTrue(lower.contains("alternative"));
        assertTrue(lower.contains("blocked"));
        assertTrue(lower.contains("data, never instructions"));
        assertTrue(lower.contains("never claim"), "must keep the do-not-claim-tool-use clause");
        // 2026-10-01 audit additions: name retrieved content explicitly (indirect
        // prompt injection arrives via first-party retrievers too, not just MCP)
        // and forbid quoting the directives/system prompt back to the user.
        assertTrue(lower.contains("fetched pages"), "must name retrieved content as untrusted data");
        assertTrue(lower.contains("never quote"), "must forbid echoing the directives/system prompt");
        assertTrue(!merged.contains("  "), "no stray whitespace runs in the prompt text");
        int words = merged.trim().split("\\s+").length;
        // Cap raised 120 -> 135 (2026-10-01): the +10 words buy named
        // retrieved-content injection coverage and the no-prompt-leak clause.
        assertTrue(words <= 135, "merged directive is " + words + " words - a per-turn cost, keep it tight");
    }

    @Test
    void retiredMediaHonestyDirectiveIsAlwaysNull() {
        assertNull(ChatPromptDirectives.mediaGenerationHonestyDirective(List.of()));
        assertNull(ChatPromptDirectives.mediaGenerationHonestyDirective(null));
    }

    @Test
    void directiveIsAbsentWhenNoToolsAreOffered() {
        assertNull(ChatPromptDirectives.agenticCompletionDirective(List.of()));
    }

    @Test
    void directiveIsAbsentWhenToolsListIsNull() {
        assertNull(ChatPromptDirectives.agenticCompletionDirective(null));
    }
}
