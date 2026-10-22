package com.agenticai.core.codeintel;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code apply_code_changes} - writes full file contents (or deletes) into an EXISTING code-intel
 * scratch workspace (see {@code create_code_workspace}). This is a consequential write action, so
 * unlike every read-only tool in this package it does NOT call {@link CodeIntelClient} directly: it
 * ALWAYS routes through {@link HumanApprovalGateService#evaluate} first, for {@link
 * ApplyCodeWorkspaceApprovalExecutor#ACTION_TYPE}, and the actual write only happens later, when a
 * tenant-admin approves the resulting request from this platform's REAL Approvals screen - or, if
 * an operator has explicitly configured an {@code ApprovalPolicyOverride} to skip approval for this
 * action type, immediately and synchronously inside {@code evaluate()} itself (see {@code
 * HumanApprovalGateService}'s javadoc for that auto-approve path). This routing is unconditional:
 * it happens regardless of what code-intel's OWN internal guardrail policy ({@code policy.py})
 * would independently decide for this same apply - see {@link RunCodeWorkspaceApprovalExecutor}'s
 * javadoc for why two independent gates here is deliberate, not redundant.
 *
 * <p>Because approval may be asynchronous, this tool call NEVER returns the outcome of the write
 * inline - only whether a request is now pending, or (only when a tenant admin has explicitly opted
 * out of approval for this action type) that it already executed. Call {@code diff_code_workspace}
 * afterward to see what actually landed.
 *
 * <p>The stored payload is marked {@code adminOnly: true} - see {@code
 * ApprovalController#enforceApproverAuthorization} - so only a ROLE_tenant-admin, not any
 * authenticated tenant member, can approve an agent writing arbitrary file contents into a
 * workspace.
 */
public class ApplyCodeChangesToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ApplyCodeChangesToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int MAX_FILES_IN_SUMMARY = 5;

    private final HumanApprovalGateService gateService;
    private final Set<String> allowedRepoIds;

    public ApplyCodeChangesToolCallback(HumanApprovalGateService gateService) {
        this(gateService, Set.of());
    }

    /**
     * @param allowedRepoIds the calling agent's {@code Agent.allowedCodeIntelRepoIds} - EMPTY means
     *     deny-all here, same "opposite of the knowledge-collection convention" reasoning as {@link
     *     CreateCodeWorkspaceToolCallback}. Enforced BEFORE {@code gateService.evaluate} is ever
     *     called, so a disallowed repo never even files an approval request a tenant-admin could
     *     mistakenly approve - see migration V103's header comment.
     */
    public ApplyCodeChangesToolCallback(
            HumanApprovalGateService gateService, Set<String> allowedRepoIds) {
        this.gateService = gateService;
        this.allowedRepoIds = allowedRepoIds == null ? Set.of() : allowedRepoIds;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("apply_code_changes")
                .description(
                        "Writes full file contents (or deletes files) into an EXISTING code workspace created by "
                                + "create_code_workspace. This ALWAYS requires a tenant-admin's sign-off on this "
                                + "platform's Approvals screen before anything is written - this call only files the "
                                + "request and returns immediately; it does not write anything itself and does not "
                                + "return the outcome. After a human approves, the write happens automatically; call "
                                + "diff_code_workspace afterward to see the result.")
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
                        },
                        "files": {
                            "type": "array",
                            "description": "Full-content file writes or deletes to apply",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "path": {
                                        "type": "string",
                                        "description": "Repo-relative file path"
                                    },
                                    "content": {
                                        "type": "string",
                                        "description": "Full new file content (omit when delete is true)"
                                    },
                                    "delete": {
                                        "type": "boolean",
                                        "description": "Set true to delete this path instead of writing it"
                                    }
                                },
                                "required": ["path"]
                            }
                        }
                    },
                    "required": ["repo_id", "workspace_id", "files"]
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
            Object filesObj = args.get("files");
            if (repoId == null || repoId.toString().isBlank()) {
                return "{\"error\": \"repo_id is required\"}";
            }
            if (workspaceId == null || workspaceId.toString().isBlank()) {
                return "{\"error\": \"workspace_id is required (from create_code_workspace)\"}";
            }
            if (!allowedRepoIds.contains(repoId.toString())) {
                return "{\"error\": \"This agent is not permitted to target that repository\"}";
            }
            if (!(filesObj instanceof List)) {
                return "{\"error\": \"files is required and must be a non-empty array\"}";
            }
            List<Map<String, Object>> files = (List<Map<String, Object>>) filesObj;
            if (files.isEmpty()) {
                return "{\"error\": \"files must contain at least one entry\"}";
            }

            ApplyCodeWorkspaceApprovalExecutor.ApplyPayload payload =
                    new ApplyCodeWorkspaceApprovalExecutor.ApplyPayload(
                            repoId.toString(), workspaceId.toString(), files, true);
            String payloadJson = MAPPER.writeValueAsString(payload);
            String summary =
                    "Apply "
                            + files.size()
                            + " file change(s) to code workspace "
                            + workspaceId
                            + " (repo "
                            + repoId
                            + "): "
                            + summarizePaths(files);
            String requestedBy =
                    TenantContext.current().map(TenantContext::getSubject).orElse(null);

            ApprovalEvaluation evaluation =
                    gateService.evaluate(
                            ApplyCodeWorkspaceApprovalExecutor.ACTION_TYPE,
                            summary,
                            payloadJson,
                            requestedBy);

            return responseJson(evaluation);
        } catch (Exception e) {
            log.warn("apply_code_changes tool call failed", e);
            return "{\"error\": \"apply_code_changes failed: "
                    + SearchCodeToolCallback.safe(e.getMessage())
                    + "\"}";
        }
    }

    private String summarizePaths(List<Map<String, Object>> files) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(files.size(), MAX_FILES_IN_SUMMARY);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object path = files.get(i).get("path");
            sb.append(path == null ? "?" : path.toString().replace("\"", "'"));
        }
        if (files.size() > shown) {
            sb.append(", +").append(files.size() - shown).append(" more");
        }
        return sb.toString();
    }

    private String responseJson(ApprovalEvaluation evaluation) {
        if (evaluation.approved()) {
            return "{\"status\": \"auto_approved_and_executed\", "
                    + "\"message\": \"This tenant's policy does not require approval for this action type, "
                    + "so it already ran. This tool does not return live output - call diff_code_workspace "
                    + "to see the resulting change.\"}";
        }
        return "{\"status\": \"pending_approval\", "
                + "\"approval_request_id\": \""
                + evaluation.pendingRequestId()
                + "\", "
                + "\"message\": \"This write requires a tenant-admin's approval on the platform's Approvals "
                + "screen before it runs. It will execute automatically once approved - no further tool call "
                + "is needed. Call diff_code_workspace afterward to see the result.\"}";
    }
}
