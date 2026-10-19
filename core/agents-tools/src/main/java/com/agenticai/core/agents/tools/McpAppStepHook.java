package com.agenticai.core.agents.tools;

import java.util.Map;

/**
 * Seam between {@link StepEventToolCallback} and the MCP Apps feature ({@code
 * app.mcp.apps.enabled}). Called once per SUCCESSFUL tool result of a turn that asked for step
 * events; returns the small additive {@code app} pointer to put on the {@code tool_result} step
 * event, or {@code null} when the tool has no MCP App (the overwhelmingly common case). Must never
 * throw and never block - the implementation only does in-memory bookkeeping; the HTML is fetched
 * later, on demand, by the browser through the tenant-scoped GET endpoint.
 */
@FunctionalInterface
public interface McpAppStepHook {

    /**
     * @param toolName the platform-visible (namespaced) tool name the model saw
     * @param argsJson the raw argument JSON of the call (the implementation redacts before keeping
     *     any of it)
     * @param output the guard-sanitised result text the model received
     */
    Map<String, Object> onToolResult(String toolName, String argsJson, String output);
}
