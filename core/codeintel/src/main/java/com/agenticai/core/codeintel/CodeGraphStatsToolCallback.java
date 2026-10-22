package com.agenticai.core.codeintel;

import com.agenticai.core.json.JsonMappers;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code code_graph_stats} - repo-wide summary of the deterministic code graph: file/symbol/edge
 * counts, broken down by edge type, parser engine, and call-resolution confidence. Read-only. Use
 * to sanity-check that a repo has actually been graph-indexed (and how richly) before relying on
 * {@code code_callers}/{@code code_callees}/{@code code_impact} results, or to get a quick
 * size/shape overview of an unfamiliar repo.
 */
public class CodeGraphStatsToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(CodeGraphStatsToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final CodeIntelClient client;
    private final Set<String> allowedRepoIds;

    public CodeGraphStatsToolCallback(CodeIntelClient client) {
        this(client, Set.of());
    }

    /**
     * EMPTY {@code allowedRepoIds} = unrestricted - see {@code SearchCodeToolCallback}'s
     * constructor javadoc for why.
     */
    public CodeGraphStatsToolCallback(CodeIntelClient client, Set<String> allowedRepoIds) {
        this.client = client;
        this.allowedRepoIds = allowedRepoIds == null ? Set.of() : allowedRepoIds;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("code_graph_stats")
                .description(
                        "Returns repo-wide code graph statistics: total indexed files and symbols, edge counts "
                                + "grouped by edge type (e.g. calls/inherits), files grouped by parser engine, and "
                                + "call edges grouped by resolution confidence (local/global/ambiguous). Use this to "
                                + "check whether a repo's code graph is built/populated before trusting other graph "
                                + "tools' results, or to get a quick overview of an unfamiliar repo's size and shape. "
                                + "Requires the repo_id.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "repo_id": {
                            "type": "string",
                            "description": "The indexed repository id to get graph stats for"
                        }
                    },
                    "required": ["repo_id"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            Object repoId = args.get("repo_id");
            if (repoId == null || repoId.toString().isBlank()) {
                return "{\"error\": \"repo_id is required\"}";
            }
            if (!allowedRepoIds.isEmpty() && !allowedRepoIds.contains(repoId.toString())) {
                return "{\"error\": \"This agent is not permitted to target that repository\"}";
            }
            return client.graphStats(repoId.toString());
        } catch (Exception e) {
            log.warn("code_graph_stats tool call failed", e);
            return "{\"error\": \"code_graph_stats failed: "
                    + SearchCodeToolCallback.safe(e.getMessage())
                    + "\"}";
        }
    }
}
