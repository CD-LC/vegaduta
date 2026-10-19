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
 * {@code delete_code_workspace} - tears down a scratch workspace (git
 * worktree remove + directory delete) once it is no longer needed. Only
 * removes the disposable scratch copy - it can never touch the primary
 * checkout or any other workspace, so this is intentionally NOT gated
 * behind human approval, same as {@code create_code_workspace}: this is a
 * risk-REDUCING cleanup action (freeing disk, removing a stale worktree),
 * not a write to anything that matters.
 */
public class DeleteCodeWorkspaceToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(DeleteCodeWorkspaceToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final CodeIntelClient client;
    private final Set<String> allowedRepoIds;

    public DeleteCodeWorkspaceToolCallback(CodeIntelClient client) {
        this(client, Set.of());
    }

    /**
     * @param allowedRepoIds the calling agent's {@code
     *     Agent.allowedCodeIntelRepoIds} - EMPTY means deny-all here, same
     *     "opposite of the knowledge-collection convention" reasoning as
     *     {@link CreateCodeWorkspaceToolCallback}. See migration V103's
     *     header comment.
     */
    public DeleteCodeWorkspaceToolCallback(CodeIntelClient client, Set<String> allowedRepoIds) {
        this.client = client;
        this.allowedRepoIds = allowedRepoIds == null ? Set.of() : allowedRepoIds;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("delete_code_workspace")
            .description(
                "Tears down a scratch code workspace once you're done with it (removes the git worktree "
                    + "or scratch copy). Does not touch the primary checkout or any other workspace. Use "
                    + "this to clean up after finishing a change.")
            .inputSchema("""
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
            return client.deleteWorkspace(repoId.toString(), workspaceId.toString());
        } catch (Exception e) {
            log.warn("delete_code_workspace tool call failed", e);
            return "{\"error\": \"delete_code_workspace failed: " + SearchCodeToolCallback.safe(e.getMessage()) + "\"}";
        }
    }
}
