package com.agenticai.core.agents.tools;

/**
 * The names of the two tool-search meta tools, for kernel and turn code that recognises them
 * without depending on their implementations ({@code search.SearchToolsToolCallback} and {@code
 * search.CallToolToolCallback} in core/agents-tools, whose {@code NAME} constants are these).
 */
public final class ToolSearchToolNames {

    /** {@code search_tools}: finds deferred tools by query. */
    public static final String SEARCH_TOOLS = "search_tools";

    /** {@code call_tool}: calls a tool found by {@code search_tools}. */
    public static final String CALL_TOOL = "call_tool";

    private ToolSearchToolNames() {}
}
