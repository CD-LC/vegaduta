package com.agenticai.core.workflow;

import com.agenticai.core.approval.ApprovalDecisionAuthorizer;
import com.agenticai.core.approval.ApprovalNotAuthorizedException;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Deciding a workflow node's approval ({@code workflow.node.*}, raised by {@code
 * OrchestratorService#authorizeSensitiveNode}) takes RUNNER on the workflow the run belongs to, the
 * same bar as running it - the "RUNNER-or-better" the run view's approval card already assumes. An
 * Approval node names no approvers of its own, so without this any member but the requester could
 * approve another team's workflow step. adminOnly nodes still need PERM_MANAGE_APPROVALS
 * (ApprovalController), and approval admins are not subject to this rule. A run that cannot be
 * found, or a payload without a run id, is refused.
 */
@Component
class WorkflowApprovalDecisionAuthorizer implements ApprovalDecisionAuthorizer {

    private final WorkflowRunRepository runRepository;
    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;

    WorkflowApprovalDecisionAuthorizer(
            WorkflowRunRepository runRepository,
            WorkflowService workflowService,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.workflowService = workflowService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ApprovalRequest request) {
        String actionType = request.getActionType();
        return actionType != null
                && actionType.startsWith(WorkflowApprovalActionExecutor.ACTION_TYPE_PREFIX);
    }

    @Override
    public void authorize(ApprovalRequest request) {
        WorkflowRun run =
                runId(request)
                        .flatMap(runRepository::findById)
                        .orElseThrow(
                                () ->
                                        new ApprovalNotAuthorizedException(
                                                request.getId(),
                                                "this approval's workflow run cannot be found"));
        try {
            workflowService.requireAccess(run.getWorkflowId(), PermissionLevel.RUNNER);
        } catch (ResourceAccessDeniedException e) {
            throw new ApprovalNotAuthorizedException(
                    request.getId(), "deciding it needs run access to its workflow");
        }
    }

    private Optional<UUID> runId(ApprovalRequest request) {
        try {
            JsonNode payload = objectMapper.readTree(request.getPayloadJson());
            return Optional.of(UUID.fromString(payload.path("runId").asString()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
