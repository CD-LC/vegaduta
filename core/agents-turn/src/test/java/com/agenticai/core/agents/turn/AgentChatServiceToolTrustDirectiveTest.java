package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@link AgentChatService#toolTrustDirective} - live incident, 2026-08-09: an agent with {@code
 * search_code} and {@code generate_document} both offered still asked the user for {@code
 * repo_id} (that tool's own description says it's optional) and separately denied being able to
 * generate a PDF at all, despite {@code generate_document} sitting unused on the same turn's tool
 * list. Neither tool was missing; nothing told the model to trust and use what it already had.
 *
 * <p>Pure-gate test only, same lighter-weight shape {@link AgentChatService#memoryUsageDirective}
 * and {@link AgentChatService#mediaGenerationHonestyDirective} get - see {@code
 * AgentChatServiceMcpUntrustedDataDirectiveTest} for the heavier end-to-end-wired pattern this
 * class deliberately does not duplicate.
 */
class AgentChatServiceToolTrustDirectiveTest {

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
    void directiveIsPresentWhenAnyToolIsOffered() {
        String directive = ChatPromptDirectives.toolTrustDirective(List.of(tool("search_code")));

        assertNotNull(directive);
        assertTrue(directive.toLowerCase(java.util.Locale.ROOT).contains("optional"));
    }

    @Test
    void directiveForbidsFabricatingAFileUrl() {
        // Live incident, 2026-08-10: asked to generate_document, an agent
        // replied with a fabricated oaidalleapiprodscus.blob.core.windows.net
        // URL - the tool WAS offered (generate_document is unconditionally
        // resolved), the model just never called it and invented a result
        // instead. Distinct from mediaGenerationHonestyDirective, which only
        // fires when a media tool is ABSENT - this must hold regardless of
        // what's offered.
        String directive = ChatPromptDirectives.toolTrustDirective(List.of(tool("generate_document")));

        String lower = directive.toLowerCase(java.util.Locale.ROOT);
        assertTrue(lower.contains("url"), "must explicitly address fabricated URLs, not just described capabilities");
        assertTrue(lower.contains("processing"), "must tell the model to say a job is still in progress rather than invent a result");
    }

    @Test
    void directiveForbidsClaimingPastToolUsageThatNeverHappened() {
        // Live incident, 2026-08-10: asked "are you using code intel?", an
        // agent replied "for your previous request... I used the search_code
        // tool to locate the file" with 0 actual tool calls made (confirmed
        // via telemetry) - a PAST-TENSE fabricated usage claim for a
        // non-media tool. Distinct from both the future-tense
        // ("I will call X" then stopping) and fabricated-URL clauses above -
        // this generalizes across tense and tool category.
        String directive = ChatPromptDirectives.toolTrustDirective(List.of(tool("search_code")));

        String lower = directive.toLowerCase(java.util.Locale.ROOT);
        assertTrue(lower.contains("past or future"), "must explicitly forbid past-tense fabricated tool-usage claims, not just future-tense narration");
    }

    @Test
    void directiveIsAbsentWhenNoToolsAreOffered() {
        assertNull(ChatPromptDirectives.toolTrustDirective(List.of()));
    }

    @Test
    void directiveIsAbsentWhenToolsListIsNull() {
        assertNull(ChatPromptDirectives.toolTrustDirective(null));
    }
}
