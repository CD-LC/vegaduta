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
 * {@code diff_code_workspace} - returns the unified diff of everything changed so far in a scratch
 * workspace (empty for non-git repos). Purely READ-ONLY: it inspects the workspace filesystem, it
 * never writes or runs anything, so no human-approval gate applies here, same reasoning as {@code
 * search_code}/{@code code_callers}/etc being auto-allowed. Use this after an approved
 * apply_code_changes/run_in_code_workspace call to see what actually happened.
 */
public class DiffCodeWorkspaceToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(DiffCodeWorkspaceToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final CodeIntelClient client;
    private final Set<String> allowedRepoIds;

    public DiffCodeWorkspaceToolCallback(CodeIntelClient client) {
        this(client, Set.of());
    }

    /**
     * @param allowedRepoIds the calling agent's {@code Agent.allowedCodeIntelRepoIds} - EMPTY means
     *     deny-all here, same "opposite of the knowledge-collection convention" reasoning as {@link
     *     CreateCodeWorkspaceToolCallback}. See migration V103's header comment.
     */
    public DiffCodeWorkspaceToolCallback(CodeIntelClient client, Set<String> allowedRepoIds) {
        this.client = client;
        this.allowedRepoIds = allowedRepoIds == null ? Set.of() : allowedRepoIds;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("diff_code_workspace")
                .description(
                        "Returns the unified diff of everything changed so far in a code workspace (empty for "
                                + "non-git repos). Read-only. Use this to see what an apply_code_changes or "
                                + "run_in_code_workspace call actually did, once approved and executed.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "repo_id": {
                            "type": "string",
                            "description": "The indexed repository id the workspace belongs to"
                        },
                        "workspace_id": {
                            "type": "string",
                            "description": "The workspace id returned by create_code_workspace"
                        }
                    },
                    "required": ["repo_id", "workspace_id"]
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
            Object workspaceId = args.get("workspace_id");
            if (repoId == null || repoId.toString().isBlank()) {
                return "{\"error\": \"repo_id is required\"}";
            }
            if (workspaceId == null || workspaceId.toString().isBlank()) {
                return "{\"error\": \"workspace_id is required (from create_code_workspace)\"}";
            }
            if (!allowedRepoIds.contains(repoId.toString())) {
                return "{\"error\": \"This agent is not permitted to target that repository\"}";
            }
            return client.diffWorkspace(repoId.toString(), workspaceId.toString());
        } catch (Exception e) {
            log.warn("diff_code_workspace tool call failed", e);
            return "{\"error\": \"diff_code_workspace failed: "
                    + SearchCodeToolCallback.safe(e.getMessage())
                    + "\"}";
        }
    }
}
