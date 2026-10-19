package com.agenticai.core.approval;

public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    // Autonomy/Guardrails Strategy Stage C: this status is now overloaded
    // to also mean "failed, retry scheduled" when nextRetryAt is set (see
    // ApprovalRequest.scheduleRetry) - kept as one status rather than a
    // separate RETRYING value so existing "EXECUTION_FAILED" callers/UI
    // don't need to learn a new state just to keep working; nextRetryAt
    // non-null is what actually distinguishes "will retry" from "gave up
    // without retrying" (the latter only happens for a non-retryable
    // failure, which never gets a nextRetryAt at all).
    EXECUTION_FAILED,
    // Retries exhausted (or the failure was never retryable to begin with
    // but still needs distinguishing once retries exist) - terminal, no
    // further automatic attempts. RetryFailedActionsJob never picks these
    // back up.
    DEAD_LETTERED
}
