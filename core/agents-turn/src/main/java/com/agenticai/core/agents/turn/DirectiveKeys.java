package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.turn.PromptDirectiveRegistry.Keys;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;

/**
 * Which prompt-registry key a standing directive uses on THIS turn, or null when it does not apply.
 * The applicability rules (the tool the directive is about is on the turn's list, compaction is on,
 * the agent opted in) live here and nowhere else; what the text IS (the shipped default, or an
 * override of the agent, tenant or platform) is the registry's business ({@link ResolvedDirectives}).
 * Tool names are literals on purpose: the tools package is not imported here for a name.
 */
final class DirectiveKeys {

    private DirectiveKeys() {}

    private static final String REMEMBER_FACT_TOOL = "remember_fact";
    private static final String BROWSER_SNAPSHOT_TOOL = "browser_snapshot";
    private static final String RESTORE_TOOL_RESULT = "restore_tool_result";

    private static boolean offers(List<ToolCallback> tools, String name) {
        return tools != null && tools.stream().anyMatch(tool -> name.equals(tool.getToolDefinition().name()));
    }

    /** {@code directive.memory_usage} when {@code remember_fact} is on the list. */
    static String memoryUsageKey(List<ToolCallback> tools) {
        return offers(tools, REMEMBER_FACT_TOOL) ? Keys.MEMORY_USAGE : null;
    }

    /** {@code directive.tool_trust} (or its {@code .compact} form in small-model mode) whenever the turn has any tool. */
    static String toolTrustKey(List<ToolCallback> tools, boolean compact) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        return compact ? Keys.TOOL_TRUST_COMPACT : Keys.TOOL_TRUST;
    }

    /** {@code directive.mcp_untrusted} when the turn offers an MCP tool. */
    static String mcpUntrustedKey(boolean anyMcpToolsOffered) {
        return anyMcpToolsOffered ? Keys.MCP_UNTRUSTED : null;
    }

    /** {@code directive.tool_search} when {@code search_tools} is on the list. */
    static String toolSearchKey(List<ToolCallback> tools) {
        return offers(tools, com.agenticai.core.agents.tools.ToolSearchToolNames.SEARCH_TOOLS) ? Keys.TOOL_SEARCH : null;
    }

    /**
     * {@code directive.context_compaction} (the restore tool is on the list) or {@code .no_restore},
     * only when compaction is on and the turn has tools.
     */
    static String contextCompactionKey(boolean compactionEnabled, List<ToolCallback> tools) {
        if (!compactionEnabled || tools == null || tools.isEmpty()) {
            return null;
        }
        return offers(tools, RESTORE_TOOL_RESULT) ? Keys.CONTEXT_COMPACTION : Keys.CONTEXT_COMPACTION_NO_RESTORE;
    }

    /** {@code directive.browser_v2} when {@code browser_snapshot} is on the list. */
    static String browserV2Key(List<ToolCallback> tools) {
        return offers(tools, BROWSER_SNAPSHOT_TOOL) ? Keys.BROWSER_V2 : null;
    }

    /** {@code directive.artifacts} when the directive applies to this agent. */
    static String artifactsKey(boolean enabled, boolean allAgents, String agentIdsCsv, UUID agentId) {
        return ArtifactsDirective.keyForAgent(enabled, allAgents, agentIdsCsv, agentId);
    }
}
