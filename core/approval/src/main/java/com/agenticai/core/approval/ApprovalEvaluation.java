package com.agenticai.core.approval;

import java.util.UUID;

/**
 * Result of {@link HumanApprovalGateService#evaluate}: either the action was
 * auto-approved (the caller should proceed immediately, no
 * {@link ApprovalRequest} was created) or it's pending (the caller must NOT
 * proceed - a request was created and {@code pendingRequestId} identifies
 * it).
 */
public record ApprovalEvaluation(boolean approved, UUID pendingRequestId) {

    public static ApprovalEvaluation autoApproved() {
        return new ApprovalEvaluation(true, null);
    }

    public static ApprovalEvaluation pending(UUID requestId) {
        return new ApprovalEvaluation(false, requestId);
    }
}
