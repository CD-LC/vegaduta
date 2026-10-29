package com.agenticai.core.agents.learning.preferences;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The readable name of an approval's action type, for text a person reads ("You approved \"Search
 * the web\" 6 times"). A gated tool call is recorded as {@code mcp_tool.<name>} and an MCP server's
 * tool as {@code mcp__<server>__<tool>}; neither wire name belongs in a sentence addressed to a
 * person.
 *
 * <p>Built-in tools get the same label the console shows on its approval cards (keep in sync with
 * {@code web/app/lib/toolLabels.ts} and the {@code toolLabels} namespace of {@code
 * web/messages/en.json}); an MCP tool reads "tool (server)"; anything else is its tool name without
 * the {@code mcp_tool.} prefix. The input is already restricted to {@code [A-Za-z0-9._:-]} by the
 * caller, so the result is plain text with no markup.
 */
final class ApprovalActionLabel {

    /** Must stay in sync with GuardedToolCallback.ACTION_TYPE_PREFIX. */
    private static final String TOOL_CALL_PREFIX = "mcp_tool.";

    private static final Pattern MCP_NAME = Pattern.compile("^mcp__(.+?)__(.+)$");

    private static final Map<String, String> BUILT_IN =
            Map.ofEntries(
                    Map.entry("web_search", "Search the web"),
                    Map.entry("fetch_web_page", "Open a web page"),
                    Map.entry("deep_research", "Research a topic on the web"),
                    Map.entry("verify_citations", "Check quotes against sources"),
                    Map.entry("http_request", "Call a web API"),
                    Map.entry("http_request_write", "Send data to a web API"),
                    Map.entry("schedule_task", "Schedule a task"),
                    Map.entry("create_workflow", "Create a workflow"),
                    Map.entry("data_table_query", "Look up a data table"),
                    Map.entry("data_table_write", "Change a data table"),
                    Map.entry("coding_task", "Run a coding task"),
                    Map.entry("delegate_to_specialist", "Ask a specialist agent"),
                    Map.entry("delegate_parallel", "Ask several agents at once"),
                    Map.entry("web_task", "Use a browser on a website"),
                    // Not an mcp_tool.* action: the approval request that enables a
                    // model-authored custom code tool (agents-tools' create_custom_tool).
                    Map.entry("custom_code_tool.enable", "Enable an agent-written tool"));

    private ApprovalActionLabel() {}

    /**
     * "mcp_tool.web_search" -> "Search the web"; "mcp_tool.mcp__github__create_issue" ->
     * "create_issue (github)".
     */
    static String of(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return "";
        }
        String name = actionType.trim();
        if (name.toLowerCase(Locale.ROOT).startsWith(TOOL_CALL_PREFIX)) {
            name = name.substring(TOOL_CALL_PREFIX.length());
        }
        String builtIn = BUILT_IN.get(name.toLowerCase(Locale.ROOT));
        if (builtIn != null) {
            return builtIn;
        }
        Matcher mcp = MCP_NAME.matcher(name);
        if (mcp.matches()) {
            return mcp.group(2) + " (" + mcp.group(1) + ")";
        }
        return name.isEmpty() ? actionType.trim() : name;
    }
}
