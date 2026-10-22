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
 * {@code code_callees} - what a given symbol itself calls, from the deterministic AST-derived code
 * graph (the reverse of {@code code_callers}). Read-only. Use to understand a function's own
 * dependencies before changing or removing it.
 */
public class CodeCalleesToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(CodeCalleesToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final CodeIntelClient client;
    private final Set<String> allowedRepoIds;

    public CodeCalleesToolCallback(CodeIntelClient client) {
        this(client, Set.of());
    }

    /**
     * EMPTY {@code allowedRepoIds} = unrestricted - see {@code SearchCodeToolCallback}'s
     * constructor javadoc for why.
     */
    public CodeCalleesToolCallback(CodeIntelClient client, Set<String> allowedRepoIds) {
        this.client = client;
        this.allowedRepoIds = allowedRepoIds == null ? Set.of() : allowedRepoIds;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("code_callees")
                .description(
                        "Lists everything a given function/method/symbol itself calls, from the deterministic "
                                + "code graph (not a guess) - the reverse of code_callers. Use to understand what a "
                                + "symbol depends on before changing or removing it. Requires the repo_id the symbol "
                                + "lives in.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "symbol": {
                            "type": "string",
                            "description": "The function/method/class name to find callees of (e.g. 'charge' or 'PaymentProcessor.charge')"
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
            return client.callees(repoId.toString(), symbol.toString());
        } catch (Exception e) {
            log.warn("code_callees tool call failed", e);
            return "{\"error\": \"code_callees failed: "
                    + SearchCodeToolCallback.safe(e.getMessage())
                    + "\"}";
        }
    }
}
