package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.tools.ResolvedToolList;
import com.agenticai.core.prompts.PromptResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The scratchpad directive: present only on a turn where the pad is reachable - the tools on the
 * list outright, or offered and deferred behind {@code search_tools} - and byte-stable (no per-turn
 * data, so the prompt prefix stays cacheable).
 */
class ChatPromptDirectivesScratchpadTest {

    /** Resolves the directive exactly as the composer does: registry key, then shipped text. */
    private static String scratchpadDirective(boolean reachable) {
        String key = DirectiveKeys.scratchpadKey(reachable);
        return key == null ? null : PromptResolver.defaultsOnly().catalog().defaultText(key);
    }

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException("not exercised by this test");
            }
        };
    }

    @Test
    void noDirectiveWhenThePadIsNotReachable() {
        // Scratchpad neither on the list nor recorded as offered-behind-search_tools.
        assertNull(
                scratchpadDirective(
                        DirectiveKeys.scratchpadReachable(List.of(tool("remember_fact")), null)));
        assertNull(scratchpadDirective(DirectiveKeys.scratchpadReachable(List.of(), null)));
        assertNull(scratchpadDirective(DirectiveKeys.scratchpadReachable(null, null)));
        // search_tools alone does not imply the pad: it may not have been offered at all.
        assertNull(
                scratchpadDirective(
                        DirectiveKeys.scratchpadReachable(
                                List.of(tool("search_tools"), tool("call_tool")), null)));
        assertNull(
                scratchpadDirective(
                        DirectiveKeys.scratchpadReachable(
                                List.of(tool("search_tools"), tool("call_tool")), false)));
    }

    @Test
    void directiveIsSentWhenTheToolsAreOnTheList() {
        String directive =
                scratchpadDirective(
                        DirectiveKeys.scratchpadReachable(
                                List.of(tool("scratchpad_write"), tool("scratchpad_read")), null));

        assertNotNull(directive);
        assertTrue(directive.contains("scratchpad_write"));
        assertTrue(directive.contains("scratchpad_list"));
    }

    @Test
    void directiveIsSentWhenThePadWasOfferedAndDeferred() {
        // The resolver recorded the pad in the candidate set; deferral moved it behind
        // search_tools, which is on the final list.
        List<ToolCallback> deferredList =
                ResolvedToolList.of(List.of(tool("search_tools"), tool("call_tool")), false, true);

        String directive =
                scratchpadDirective(
                        DirectiveKeys.scratchpadReachable(
                                deferredList,
                                ((ResolvedToolList) deferredList).scratchpadOffered()));

        assertNotNull(directive);
        assertTrue(directive.contains("scratchpad"));
    }

    @Test
    void theReachabilityFactSurvivesResolutionCopying() {
        ResolvedToolList resolved = ResolvedToolList.of(List.of(tool("search_tools")), false, true);
        ResolvedToolList copy = ResolvedToolList.copyOf(resolved);

        assertTrue(copy.scratchpadOffered());
        assertFalse(
                ResolvedToolList.copyOf(List.of(tool("search_tools"))).scratchpadOffered()
                        == Boolean.TRUE);
    }

    @Test
    void directiveIsAByteStableConstantWithNoPerTurnData() {
        String first =
                scratchpadDirective(
                        DirectiveKeys.scratchpadReachable(List.of(tool("scratchpad_write")), null));
        String second =
                scratchpadDirective(
                        DirectiveKeys.scratchpadReachable(
                                List.of(tool("scratchpad_write"), tool("anything_else")), null));

        assertSame(
                first,
                second,
                "the directive must not interpolate names or counts (prompt-prefix caching)");
    }
}
