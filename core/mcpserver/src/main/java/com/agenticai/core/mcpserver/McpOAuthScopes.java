package com.agenticai.core.mcpserver;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Scope vocabulary of the OAuth 2.1 option of the {@code /mcp} server, and the mapping from a
 * JSON-RPC request to the scopes it needs.
 *
 * <p><b>No wildcards, by design.</b> A scope is satisfied only by that exact string. A token
 * carrying {@code *}, {@code mcp:*} or {@code mcp:agents:*} gets nothing from it, and none of these
 * is ever advertised in {@code scopes_supported}: the MCP authorization spec asks for least
 * privilege and incremental scope elevation, which a wildcard defeats. Methods that do not touch a
 * tool ({@code initialize}, {@code tools/list}, notifications) need only a valid token; the tools
 * the caller may then see or call are still confined by the grant the token is linked to.
 */
final class McpOAuthScopes {

    static final String AGENTS_READ = "mcp:agents:read";
    static final String AGENTS_CALL = "mcp:agents:call";
    static final String WORKFLOWS_READ = "mcp:workflows:read";
    static final String WORKFLOWS_CALL = "mcp:workflows:call";
    static final String KNOWLEDGE_SEARCH = "mcp:knowledge:search";
    static final String TASKS = "mcp:tasks";
    /** Fallback for a tool this table does not know; not advertised, so a stock token never has it. */
    static final String TOOLS_CALL = "mcp:tools:call";

    /** What {@code scopes_supported} advertises: every concrete scope, never a pattern. */
    static final List<String> SUPPORTED = List.of(
        AGENTS_READ, AGENTS_CALL, WORKFLOWS_READ, WORKFLOWS_CALL, KNOWLEDGE_SEARCH, TASKS);

    private McpOAuthScopes() {}

    /** Scopes required by a JSON-RPC body (single message or batch). Empty set = valid token suffices. */
    static Set<String> requiredFor(JsonNode body) {
        Set<String> required = new LinkedHashSet<>();
        if (body == null) {
            return required;
        }
        if (body.isArray()) {
            body.forEach(m -> required.addAll(requiredFor(m)));
            return required;
        }
        if (body.isObject() && "tools/call".equals(body.path("method").asText())) {
            required.add(scopeForTool(body.path("params").path("name").asText("")));
        }
        return required;
    }

    static String scopeForTool(String tool) {
        return switch (tool) {
            case "list_my_agents" -> AGENTS_READ;
            case "call_agent" -> AGENTS_CALL;
            case "list_my_workflows" -> WORKFLOWS_READ;
            case "call_workflow", "get_run" -> WORKFLOWS_CALL;
            case "search_knowledge" -> KNOWLEDGE_SEARCH;
            case "start_agent_task", "get_task", "cancel_task" -> TASKS;
            default -> {
                if (tool.startsWith("agent_")) {
                    yield AGENTS_CALL;
                }
                if (tool.startsWith("workflow_")) {
                    yield WORKFLOWS_CALL;
                }
                yield TOOLS_CALL;
            }
        };
    }

    /** Exact-match check; the granted set is never pattern-expanded. */
    static Set<String> missing(Set<String> granted, Set<String> required) {
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(granted);
        return missing;
    }
}
