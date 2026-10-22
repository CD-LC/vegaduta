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
 * {@code code_callers} - who calls a given symbol, from the deterministic AST-derived code graph.
 * Read-only. Use before changing a function to see everywhere it is used.
 */
public class CodeCallersToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(CodeCallersToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final CodeIntelClient client;
    private final Set<String> allowedRepoIds;

    public CodeCallersToolCallback(CodeIntelClient client) {
        this(client, Set.of());
    }

    /**
     * EMPTY {@code allowedRepoIds} = unrestricted - see {@code SearchCodeToolCallback}'s
     * constructor javadoc for why.
     */
    public CodeCallersToolCallback(CodeIntelClient client, Set<String> allowedRepoIds) {
        this.client = client;
        this.allowedRepoIds = allowedRepoIds == null ? Set.of() : allowedRepoIds;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("code_callers")
                .description(
                        "Lists every place that calls a given function/method/symbol, from the deterministic "
                                + "code graph (not a guess). Use before modifying a symbol to understand its usage. "
                                + "Requires the repo_id the symbol lives in.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "symbol": {
                            "type": "string",
                            "description": "The function/method/class name to find callers of (e.g. 'charge' or 'PaymentProcessor.charge')"
                        },
                        "repo_id": {
                            "type": "string",
                            "description": "The indexed repository id the symbol belongs to"
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
                return "{\"error\": \"repo_id is required (which repository does this symbol live in?)\"}";
            }
            if (!allowedRepoIds.isEmpty() && !allowedRepoIds.contains(repoId.toString())) {
                return "{\"error\": \"This agent is not permitted to target that repository\"}";
            }
            return client.callers(repoId.toString(), symbol.toString());
        } catch (Exception e) {
            log.warn("code_callers tool call failed", e);
            return "{\"error\": \"code_callers failed: "
                    + SearchCodeToolCallback.safe(e.getMessage())
                    + "\"}";
        }
    }
}
