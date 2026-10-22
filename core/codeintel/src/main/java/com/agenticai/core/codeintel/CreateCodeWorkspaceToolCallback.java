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
 * {@code create_code_workspace} - creates an isolated scratch copy of a repo (a git worktree when
 * the source is a git checkout, an rsync-style copy otherwise; the primary checkout is NEVER
 * touched) to run commands or apply file changes against. This is the required first step before
 * {@code apply_code_changes}/{@code run_in_code_workspace}/{@code diff_code_workspace}/{@code
 * delete_code_workspace} can be used - those all take the {@code workspace_id} this returns.
 *
 * <p>Not gated behind human approval, unlike apply/run: creating an empty scratch copy has no
 * effect on the primary checkout or any existing code - code-intel's own {@code workspace_create}
 * never runs through {@code evaluate_command}/{@code evaluate_apply} either (see {@code
 * service.py}), so there is nothing for this platform's own guardrail policy layer to usefully add
 * here. Matches this codebase's "read/search/graph auto-allow, run/write escalate" guardrail
 * philosophy - this is closer to a checkout than to a write.
 */
public class CreateCodeWorkspaceToolCallback implements ToolCallback {

    private static final Logger log =
            LoggerFactory.getLogger(CreateCodeWorkspaceToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final CodeIntelClient client;
    private final Set<String> allowedRepoIds;

    public CreateCodeWorkspaceToolCallback(CodeIntelClient client) {
        this(client, Set.of());
    }

    /**
     * @param allowedRepoIds the calling agent's {@code Agent.allowedCodeIntelRepoIds} - EMPTY means
     *     deny-all here (opposite of the knowledge-collection allow-list convention), so an agent
     *     with {@code codeWorkspaceEnabled=true} but no granted repos can create zero workspaces,
     *     not every tenant repo. See migration V103's header comment.
     */
    public CreateCodeWorkspaceToolCallback(CodeIntelClient client, Set<String> allowedRepoIds) {
        this.client = client;
        this.allowedRepoIds = allowedRepoIds == null ? Set.of() : allowedRepoIds;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("create_code_workspace")
                .description(
                        "Creates a new isolated scratch workspace for a repo (a git worktree or a copy - never "
                                + "the primary checkout). Returns a workspace_id to pass to apply_code_changes, "
                                + "run_in_code_workspace, diff_code_workspace, and delete_code_workspace. Call this "
                                + "first before trying to change or run anything against a repo's code.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "repo_id": {
                            "type": "string",
                            "description": "The indexed repository id to create a scratch workspace for"
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
            if (!allowedRepoIds.contains(repoId.toString())) {
                return "{\"error\": \"This agent is not permitted to target that repository\"}";
            }
            return client.createWorkspace(repoId.toString());
        } catch (Exception e) {
            log.warn("create_code_workspace tool call failed", e);
            return "{\"error\": \"create_code_workspace failed: "
                    + SearchCodeToolCallback.safe(e.getMessage())
                    + "\"}";
        }
    }
}
