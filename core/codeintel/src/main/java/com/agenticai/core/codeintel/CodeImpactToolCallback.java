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
 * {@code code_impact} - the transitive blast radius of changing a symbol or file: its
 * callers-of-callers, inheritors, and importers, level by level, from the deterministic code graph.
 * Read-only. The single most useful tool for "if I change this, what could break?" - the question
 * every refactor or modernization task is gated on.
 */
public class CodeImpactToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(CodeImpactToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int DEFAULT_DEPTH = 3;
    private static final int MAX_DEPTH = 6;

    private final CodeIntelClient client;
    private final Set<String> allowedRepoIds;

    public CodeImpactToolCallback(CodeIntelClient client) {
        this(client, Set.of());
    }

    /**
     * EMPTY {@code allowedRepoIds} = unrestricted - see {@code SearchCodeToolCallback}'s
     * constructor javadoc for why.
     */
    public CodeImpactToolCallback(CodeIntelClient client, Set<String> allowedRepoIds) {
        this.client = client;
        this.allowedRepoIds = allowedRepoIds == null ? Set.of() : allowedRepoIds;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("code_impact")
                .description(
                        "Computes the transitive blast radius of changing a symbol or file - everything that "
                                + "(directly or indirectly) calls it, inherits from it, or imports it, grouped by "
                                + "level. Use this before proposing a refactor, rename, or migration to understand "
                                + "what a change could affect. Requires the repo_id.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "symbol": {
                            "type": "string",
                            "description": "The symbol (function/class) or file path to analyze"
                        },
                        "repo_id": {
                            "type": "string",
                            "description": "The indexed repository id"
                        },
                        "depth": {
                            "type": "integer",
                            "description": "How many hops of transitive impact to follow (1-6, default 3)"
                        }
                    },
                    "required": ["symbol", "repo_id"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            Object symbol = args.get("symbol");
            Object repoId = args.get("repo_id");
            if (symbol == null || symbol.toString().isBlank()) {
                return "{\"error\": \"symbol is required\"}";
            }
            if (repoId == null || repoId.toString().isBlank()) {
                return "{\"error\": \"repo_id is required\"}";
            }
            if (!allowedRepoIds.isEmpty() && !allowedRepoIds.contains(repoId.toString())) {
                return "{\"error\": \"This agent is not permitted to target that repository\"}";
            }
            int depth = DEFAULT_DEPTH;
            Object d = args.get("depth");
            if (d instanceof Number n) {
                depth = Math.max(1, Math.min(MAX_DEPTH, n.intValue()));
            }
            return client.impact(repoId.toString(), symbol.toString(), depth);
        } catch (Exception e) {
            log.warn("code_impact tool call failed", e);
            return "{\"error\": \"code_impact failed: "
                    + SearchCodeToolCallback.safe(e.getMessage())
                    + "\"}";
        }
    }
}
