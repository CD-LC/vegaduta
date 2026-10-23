package com.agenticai.core.agents.job.sentinel;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.approval.ApprovalDecisionAuthorizer;
import com.agenticai.core.approval.ApprovalNotAuthorizedException;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deciding an {@code agent.proposal} approval (an ambient-mode sentinel's proposed run, see {@link
 * SentinelProposalExecutor}) takes RUNNER on the proposal's agent: approving it enqueues a job of
 * that agent as the approver, so the approver must be someone who could run the agent anyway.
 * Without this any member but the requester could ratify, or reject, another team's agent's
 * proposal. Approval admins ({@code PERM_MANAGE_APPROVALS}) are not subject to this rule, and a
 * payload without a readable agent id is refused, the same default-deny as {@code
 * WorkflowApprovalDecisionAuthorizer}.
 */
@Component
class SentinelProposalDecisionAuthorizer implements ApprovalDecisionAuthorizer {

    private final AgentService agentService;
    private final ObjectMapper objectMapper;

    SentinelProposalDecisionAuthorizer(AgentService agentService, ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ApprovalRequest request) {
        return SentinelProposalExecutor.ACTION_TYPE.equals(request.getActionType());
    }

    @Override
    public void authorize(ApprovalRequest request) {
        UUID agentId =
                agentId(request)
                        .orElseThrow(
                                () ->
                                        new ApprovalNotAuthorizedException(
                                                request.getId(),
                                                "this proposal does not name a readable agent"));
        try {
            agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        } catch (ResourceAccessDeniedException | AgentNotFoundException e) {
            throw new ApprovalNotAuthorizedException(
                    request.getId(), "deciding it needs run access to the agent it proposes for");
        }
    }

    private Optional<UUID> agentId(ApprovalRequest request) {
        try {
            JsonNode payload = objectMapper.readTree(request.getPayloadJson());
            return Optional.of(UUID.fromString(payload.path("agentId").asText()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
