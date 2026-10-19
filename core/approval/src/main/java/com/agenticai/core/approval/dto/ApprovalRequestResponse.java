package com.agenticai.core.approval.dto;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalStatus;
import java.time.Instant;
import java.util.UUID;

public record ApprovalRequestResponse(
    UUID id,
    String actionType,
    String summary,
    ApprovalStatus status,
    String requestedBy,
    String decidedBy,
    String rejectionReason,
    String executionError,
    Instant createdAt,
    Instant decidedAt,
    // Autonomy/Guardrails Strategy Stage C - lets a tenant-admin see how
    // many retries a failed action has been through and when the next one
    // is due, straight from the existing GET /api/approvals list/get
    // endpoints (no new endpoint needed).
    int retryCount,
    Instant nextRetryAt,
    // AXON Admin.dc.html "PROPOSED ACTION" code block (#474) - the entity
    // already carries this (ApprovalActionExecutor's own input), it just was
    // never echoed back to a caller before. Raw JSON text, not re-parsed
    // here - the frontend pretty-prints it. Genuinely nullable: older rows
    // predating a given action type's payload shape, or an action type that
    // never set one, come back null and the panel shows "no payload
    // recorded" rather than an empty {}.
    String payload,
    // Closes a known gap (RunCodeWorkspaceApprovalExecutor's approval flow
    // previously discarded the run's actual stdout/stderr/exit code) -
    // ApprovalRequest#executionResult, echoed back straight from the
    // existing GET /api/approvals list/get endpoints, same pattern as
    // #474's payload field above. Null for action types whose executor
    // never calls ApprovalRequest#recordExecutionResult.
    String executionResult
) {
    public static ApprovalRequestResponse from(ApprovalRequest request) {
        return new ApprovalRequestResponse(
            request.getId(),
            request.getActionType(),
            request.getSummary(),
            request.getStatus(),
            request.getRequestedBy(),
            request.getDecidedBy(),
            request.getRejectionReason(),
            request.getExecutionError(),
            request.getCreatedAt(),
            request.getDecidedAt(),
            request.getRetryCount(),
            request.getNextRetryAt(),
            request.getPayloadJson(),
            request.getExecutionResult()
        );
    }
}
