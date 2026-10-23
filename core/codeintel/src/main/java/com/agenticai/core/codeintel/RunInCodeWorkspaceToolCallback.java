package com.agenticai.core.codeintel;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code run_in_code_workspace} - runs a shell command inside an EXISTING
 * code-intel scratch workspace (see {@code create_code_workspace}). Same
 * consequential-write posture as {@link ApplyCodeChangesToolCallback} - see
 * that class's javadoc for the full gate design (this tool ALWAYS routes
 * through {@link HumanApprovalGateService#evaluate} first, for {@link
 * RunCodeWorkspaceApprovalExecutor#ACTION_TYPE}, unconditionally, and never
 * returns command output inline).
 */
public class RunInCodeWorkspaceToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RunInCodeWorkspaceToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int SUMMARY_COMMAND_CHARS = 200;

    private final HumanApprovalGateService gateService;
    private final Set<String> allowedRepoIds;

    public RunInCodeWorkspaceToolCallback(HumanApprovalGateService gateService) {
        this(gateService, Set.of());
    }

    /**
     * @param allowedRepoIds the calling agent's {@code
     *     Agent.allowedCodeIntelRepoIds} - EMPTY means deny-all here, same
     *     reasoning as {@link ApplyCodeChangesToolCallback}. Enforced
     *     BEFORE {@code gateService.evaluate} is ever called, so a
     *     disallowed repo never even files an approval request.
     */
    public RunInCodeWorkspaceToolCallback(HumanApprovalGateService gateService, Set<String> allowedRepoIds) {
        this.gateService = gateService;
        this.allowedRepoIds = allowedRepoIds == null ? Set.of() : allowedRepoIds;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("run_in_code_workspace")
            .description(
                "Runs a shell command (e.g. a test suite or a build) inside an EXISTING code workspace "
                    + "created by create_code_workspace. This ALWAYS requires a tenant-admin's sign-off "
                    + "on this platform's Approvals screen before anything runs - this call only files "
                    + "the request and returns immediately; it does not run anything itself and does not "
                    + "return exit code/stdout/stderr. After a human approves, the command runs "
                    + "automatically.")
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
                        },
                        "command": {
                            "type": "string",
                            "description": "The shell command to run inside the workspace, e.g. 'mvn -q test'"
                        },
                        "timeout": {
                            "type": "integer",
                            "description": "Optional hard timeout in seconds (the service caps this regardless)"
                        },
                        "attempts": {
                            "type": "integer",
                            "description": "Optional reruns of UNCHANGED code on failure to absorb flaky tests (default 1, max 5)"
                        }
                    },
                    "required": ["repo_id", "workspace_id", "command"]
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
            Object command = args.get("command");
            if (repoId == null || repoId.toString().isBlank()) {
                return "{\"error\": \"repo_id is required\"}";
            }
            if (workspaceId == null || workspaceId.toString().isBlank()) {
                return "{\"error\": \"workspace_id is required (from create_code_workspace)\"}";
            }
            if (command == null || command.toString().isBlank()) {
                return "{\"error\": \"command is required\"}";
            }
            if (!allowedRepoIds.contains(repoId.toString())) {
                return "{\"error\": \"This agent is not permitted to target that repository\"}";
            }
            Integer timeout = asInteger(args.get("timeout"));
            Integer attemptsArg = asInteger(args.get("attempts"));
            int attempts = attemptsArg == null ? 1 : attemptsArg;

            RunCodeWorkspaceApprovalExecutor.RunPayload payload = new RunCodeWorkspaceApprovalExecutor.RunPayload(
                repoId.toString(), workspaceId.toString(), command.toString(), timeout, attempts, true);
            String payloadJson = MAPPER.writeValueAsString(payload);
            String cmd = command.toString();
            String summary = "Run in code workspace " + workspaceId + " (repo " + repoId + "): "
                + (cmd.length() > SUMMARY_COMMAND_CHARS ? cmd.substring(0, SUMMARY_COMMAND_CHARS) + "..." : cmd);
            String requestedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);

            // evaluateInTurn: the request carries the conversation it came from, so the human's decision
            // puts the outcome back into it and the agent carries on (see ApprovalOutcomePort).
            ApprovalEvaluation evaluation = gateService.evaluateInTurn(
                RunCodeWorkspaceApprovalExecutor.ACTION_TYPE, "run_in_code_workspace", summary, payloadJson,
                requestedBy);

            return responseJson(evaluation);
        } catch (Exception e) {
            log.warn("run_in_code_workspace tool call failed", e);
            return "{\"error\": \"run_in_code_workspace failed: " + SearchCodeToolCallback.safe(e.getMessage()) + "\"}";
        }
    }

    private Integer asInteger(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String responseJson(ApprovalEvaluation evaluation) {
        if (evaluation.approved()) {
            return "{\"status\": \"auto_approved_and_executed\", "
                + "\"message\": \"This tenant's policy does not require approval for this action type, "
                + "so it already ran. This tool does not return live stdout/stderr/exit code - call "
                + "diff_code_workspace to see any resulting file changes.\"}";
        }
        return "{\"status\": \"pending_approval\", "
            + "\"approval_request_id\": \"" + evaluation.pendingRequestId() + "\", "
            + "\"message\": \"This run requires a tenant-admin's approval on the platform's Approvals "
            + "screen before it executes. It will run automatically once approved - no further tool call "
            + "is needed.\"}";
    }
}
