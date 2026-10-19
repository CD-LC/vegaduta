package com.agenticai.core.codeintel;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code search_code} - semantic search over this tenant's indexed codebase.
 * Read-only; returns code passages with citations (path, line span, symbol).
 * Parallel in every respect to {@code KnowledgeSearchToolCallback}, just
 * pointed at code instead of uploaded documents.
 */
public class SearchCodeToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SearchCodeToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int DEFAULT_TOP_K = 6;

    private final CodeIntelClient client;
    private final Set<String> allowedRepoIds;

    public SearchCodeToolCallback(CodeIntelClient client) {
        this(client, Set.of());
    }

    /**
     * Bug fix (2026-08-16, direct report - a "search all repos" fan-out let
     * an agent scoped to one repo's task pull a hit from a completely
     * unrelated repo it was never granted): EMPTY {@code allowedRepoIds}
     * means UNRESTRICTED here - the opposite of {@code
     * CreateCodeWorkspaceToolCallback}'s deny-all-when-empty convention, and
     * deliberately so. This tier had NO scoping at all until this fix, so
     * every existing agent's {@code allowedCodeIntelRepoIds} is empty today
     * (nothing has ever populated it for a read-only use) - defaulting to
     * deny-all would silently break code search for every agent on the
     * platform the moment this ships. Matches {@code
     * KnowledgeSearchToolCallback}'s {@code allowedCollectionIds} convention
     * instead: empty = "no restriction has been configured," non-empty =
     * enforce it. A tenant-admin who wants an agent locked to specific
     * repos now has a real mechanism to do so (see {@code
     * AgentToolProvider#resolveCodeIntelTools}).
     */
    public SearchCodeToolCallback(CodeIntelClient client, Set<String> allowedRepoIds) {
        this.client = client;
        this.allowedRepoIds = allowedRepoIds == null ? Set.of() : allowedRepoIds;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("search_code")
            .description(
                "Searches this tenant's indexed source code for snippets relevant to a query and "
                    + "returns them with citations (file path, line span, enclosing symbol). Use this "
                    + "for 'where is X handled / implemented / configured' questions about the codebase. "
                    + "Omit repo_id to search across every indexed repository; pass repo_id to scope to one.")
            .inputSchema("""
                {
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "What to look for in the code (natural language or an identifier)"
                        },
                        "repo_id": {
                            "type": "string",
                            "description": "Optional - restrict to a single indexed repository by its id. Omit to search all."
                        }
                    },
                    "required": ["query"]
                }
                """)
            .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            Object query = args.get("query");
            if (query == null || query.toString().isBlank()) {
                return "{\"error\": \"query is required\"}";
            }
            Object repoId = args.get("repo_id");
            if (repoId != null && !repoId.toString().isBlank()) {
                if (!allowedRepoIds.isEmpty() && !allowedRepoIds.contains(repoId.toString())) {
                    return "{\"error\": \"This agent is not permitted to target that repository\"}";
                }
                return client.searchCode(query.toString(), repoId.toString(), DEFAULT_TOP_K);
            }
            // repo_id omitted - the cross-repo fan-out path. Unrestricted
            // agents keep today's "search everything" behavior; a scoped
            // agent gets a clear, actionable error instead of a silent
            // fan-out into repos it was never granted.
            if (!allowedRepoIds.isEmpty()) {
                return "{\"error\": \"This agent is restricted to specific repositories - pass repo_id "
                    + "(one of: " + String.join(", ", allowedRepoIds) + ") instead of searching all repositories.\"}";
            }
            return client.searchCode(query.toString(), null, DEFAULT_TOP_K);
        } catch (Exception e) {
            log.warn("search_code tool call failed", e);
            return "{\"error\": \"code search failed: " + safe(e.getMessage()) + "\"}";
        }
    }

    static String safe(String s) {
        return s == null ? "unknown error" : s.replace("\"", "'");
    }
}
