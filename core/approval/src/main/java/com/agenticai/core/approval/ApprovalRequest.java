package com.agenticai.core.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A single action awaiting (or having received) a human decision -
 * Created by
 * {@link HumanApprovalGateService#evaluate} whenever
 * {@link ApprovalPolicyService#requiresApproval} says an action type needs
 * one; resolved by {@link HumanApprovalGateService#approve} or
 * {@link HumanApprovalGateService#reject}.
 *
 * <p>{@code payloadJson} carries whatever the eventual
 * {@link ApprovalActionExecutor} needs to actually perform the action once
 * approved (e.g. a channel adapter's message recipient/body) - this entity
 * itself has no idea what "channel.whatsapp.send" means, by design, so
 * adding a new gated action type never requires a change here.
 */
@Entity
@Table(name = "approval_request")
public class ApprovalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "action_type", nullable = false, length = 128)
    private String actionType;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ApprovalStatus status;

    @Column(name = "requested_by", length = 255)
    private String requestedBy;

    @Column(name = "decided_by", length = 255)
    private String decidedBy;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "execution_error", columnDefinition = "TEXT")
    private String executionError;

    /**
     * The actual outcome of a completed {@link ApprovalActionExecutor#execute}
     * call, when that executor chooses to record one - e.g. {@code
     * RunCodeWorkspaceApprovalExecutor} populates this with the approved
     * run's stdout/stderr/exit code once the command finishes, so the
     * approver isn't left blind after saying yes. Distinct from {@link
     * #executionError}: that field is set by {@code HumanApprovalGateService}
     * only when {@code execute} THROWS; this one is set by the executor
     * itself for a run that actually completed, success or not. Genuinely
     * nullable - most action types (channel sends, etc) never set it.
     */
    @Column(name = "execution_result", columnDefinition = "TEXT")
    private String executionResult;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    // Autonomy/Guardrails Strategy Stage C (migration V14).
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    protected ApprovalRequest() {
        // JPA
    }

    public ApprovalRequest(String actionType, String summary, String payloadJson, String requestedBy) {
        this.actionType = actionType;
        this.summary = summary;
        this.payloadJson = payloadJson;
        this.requestedBy = requestedBy;
        this.status = ApprovalStatus.PENDING;
        this.createdAt = Instant.now();
        this.retryCount = 0;
    }

    public void markApproved(String decidedBy) {
        this.status = ApprovalStatus.APPROVED;
        this.decidedBy = decidedBy;
        this.decidedAt = Instant.now();
    }

    public void markRejected(String decidedBy, String reason) {
        this.status = ApprovalStatus.REJECTED;
        this.decidedBy = decidedBy;
        this.rejectionReason = reason;
        this.decidedAt = Instant.now();
    }

    /** A non-retryable failure - terminal, no retry ever scheduled. */
    public void markExecutionFailed(String error) {
        this.status = ApprovalStatus.EXECUTION_FAILED;
        this.executionError = error;
        this.nextRetryAt = null;
    }

    /**
     * Autonomy/Guardrails Strategy Stage C - a retryable failure that will
     * be attempted again at {@code nextRetryAt}. Still {@code
     * EXECUTION_FAILED} (not a separate status) - {@code nextRetryAt}
     * being non-null is what distinguishes "will retry" from "gave up
     * without retrying," see {@link ApprovalStatus}'s javadoc.
     */
    public void scheduleRetry(String error, Instant nextRetryAt) {
        this.status = ApprovalStatus.EXECUTION_FAILED;
        this.executionError = error;
        this.retryCount++;
        this.nextRetryAt = nextRetryAt;
    }

    /** Retries exhausted - terminal, RetryFailedActionsJob never revisits this. */
    public void markDeadLettered(String error) {
        this.status = ApprovalStatus.DEAD_LETTERED;
        this.executionError = error;
        this.nextRetryAt = null;
    }

    /** A retry attempt succeeded - back to the same terminal state a first-try success would reach. */
    public void markExecutionSucceeded() {
        this.status = ApprovalStatus.APPROVED;
        this.executionError = null;
        this.nextRetryAt = null;
    }

    /**
     * Records the actual output of a completed action - see {@link
     * #executionResult}'s javadoc. Deliberately NOT a state transition (no
     * status change, no timestamp) - an executor may call this well after
     * {@link #markApproved}/{@link #markExecutionSucceeded} already ran,
     * and calling it never changes {@link #getStatus()}.
     */
    public void recordExecutionResult(String result) {
        this.executionResult = result;
    }

    public UUID getId() {
        return id;
    }

    public String getActionType() {
        return actionType;
    }

    public String getSummary() {
        return summary;
    }

    /** Only for {@link HumanApprovalGateService#approve(java.util.UUID, String, java.util.Map)} recording an approver's form answers. */
    void replacePayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public String getExecutionError() {
        return executionError;
    }

    public String getExecutionResult() {
        return executionResult;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }
}
