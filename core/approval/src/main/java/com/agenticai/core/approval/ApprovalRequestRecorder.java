package com.agenticai.core.approval;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomically persists an {@link ApprovalRequest}'s state transition together
 * with its corresponding {@link AuditLogService} entry - pulled out of
 * {@link HumanApprovalGateService} specifically so the {@code @Transactional}
 * boundary covers <b>only</b> these two DB writes, never the external side
 * effect ({@link ApprovalActionExecutor#execute}, which can be a slow
 * network call to Twilio/Telegram/whatever channel comes next).
 *
 * <p>Holding a database transaction open across an outbound HTTP call is a
 * well-known anti-pattern: it ties up a pooled connection for the duration
 * of a third party's network latency, and risks a transaction timeout under
 * load. {@link HumanApprovalGateService} deliberately calls the executor
 * only <i>after</i> one of these methods has already returned (and its
 * transaction committed) - found and fixed during a design-principles
 * review pass, not something the original Stage 1 implementation got right
 * (it had no transaction boundary around these writes at all).
 */
@Component
class ApprovalRequestRecorder {

    private static final String TARGET_TYPE = "approval_request";

    private final ApprovalRequestRepository requestRepository;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    // Tenant webhooks (V241): approval lifecycle transitions are republished
    // through the platform event bus so a tenant's webhook subscription
    // ("approval.*" pattern) sees them - requested / resolved /
    // execution.recorded. publish() never throws into the business flow.
    private final WorkflowEventPublisher workflowEventPublisher;

    ApprovalRequestRecorder(
        ApprovalRequestRepository requestRepository, AuditLogService auditLogService,
        ApplicationEventPublisher eventPublisher, WorkflowEventPublisher workflowEventPublisher
    ) {
        this.requestRepository = requestRepository;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
        this.workflowEventPublisher = workflowEventPublisher;
    }

    /**
     * See {@link ApprovalRequestExecutionRecordedEvent}'s javadoc for exactly
     * which of this class's methods call this (and, as importantly, which
     * three deliberately do not).
     */
    private void publishExecutionRecorded(ApprovalRequest request) {
        eventPublisher.publishEvent(
            new ApprovalRequestExecutionRecordedEvent(this, request.getId(), request.getRequestedBy()));
        workflowEventPublisher.publish("approval.execution.recorded", request.getId().toString(), Map.of(
            "requestId", request.getId().toString(),
            "actionType", request.getActionType(),
            "status", request.getStatus() == null ? "" : request.getStatus().name()));
    }

    /**
     * The platform-event emission behind {@code approval.requested} /
     * {@code approval.resolved} - runId is the approval request's own id,
     * which correlates the whole lifecycle for a webhook consumer.
     */
    private void publishLifecycle(String eventType, ApprovalRequest request, Map<String, Object> extra) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("requestId", request.getId().toString());
        payload.put("actionType", request.getActionType());
        payload.put("summary", request.getSummary());
        payload.put("requestedBy", request.getRequestedBy());
        if (extra != null) {
            payload.putAll(extra);
        }
        workflowEventPublisher.publish(eventType, request.getId().toString(), payload);
    }

    @Transactional
    ApprovalRequest recordNew(
        String actionType, String summary, String payloadJson, String requestedBy, boolean autoApproved
    ) {
        ApprovalRequest request = new ApprovalRequest(actionType, summary, payloadJson, requestedBy);
        if (autoApproved) {
            request.markApproved(requestedBy);
        }
        request = requestRepository.save(request);
        auditLogService.record(
            requestedBy, actionType, TARGET_TYPE, request.getId().toString(),
            autoApproved ? AuditOutcome.AUTO_APPROVED : AuditOutcome.PENDING_APPROVAL, summary
        );
        publishLifecycle("approval.requested", request, Map.of("autoApproved", autoApproved));
        return request;
    }

    /**
     * Re-reads the request under a row lock and refuses if it is no longer PENDING. The caller's
     * own check ({@code HumanApprovalGateService#getPendingOrThrow}) runs outside any transaction,
     * so it cannot stop two concurrent deciders; this is the check that can.
     */
    private ApprovalRequest lockPending(ApprovalRequest request) {
        ApprovalRequest locked =
            requestRepository.findByIdForUpdate(request.getId())
                .orElseThrow(() -> new ApprovalRequestNotFoundException(request.getId()));
        if (locked.getStatus() != ApprovalStatus.PENDING) {
            throw new ApprovalRequestAlreadyDecidedException(locked.getId(), locked.getStatus());
        }
        return locked;
    }

    @Transactional
    ApprovalRequest recordApproved(ApprovalRequest request, String decidedBy) {
        request = lockPending(request);
        request.markApproved(decidedBy);
        request = requestRepository.save(request);
        auditLogService.record(
            decidedBy, request.getActionType(), TARGET_TYPE, request.getId().toString(), AuditOutcome.APPROVED, request.getSummary()
        );
        publishLifecycle("approval.resolved", request, Map.of("decision", "approved", "decidedBy", decidedBy == null ? "" : decidedBy));
        return request;
    }

    @Transactional
    ApprovalRequest recordRejected(ApprovalRequest request, String decidedBy, String reason) {
        request = lockPending(request);
        request.markRejected(decidedBy, reason);
        request = requestRepository.save(request);
        auditLogService.record(
            decidedBy, request.getActionType(), TARGET_TYPE, request.getId().toString(), AuditOutcome.REJECTED, reason
        );
        publishLifecycle("approval.resolved", request, Map.of(
            "decision", "rejected",
            "decidedBy", decidedBy == null ? "" : decidedBy,
            "reason", reason == null ? "" : reason));
        return request;
    }

    /**
     * A request nobody decided in time. Same lock-and-check as an approve or reject (a human deciding
     * in the same instant wins and this throws {@link ApprovalRequestAlreadyDecidedException}); recorded
     * as a rejection by {@link ApprovalRequest#EXPIRY_ACTOR}, audited as {@code EXPIRED}, and published
     * both as the lifecycle {@code approval.resolved} (decision {@code expired}) and as a settled
     * request, so a parked agent job notices without waiting for its next sweep tick.
     */
    @Transactional
    ApprovalRequest recordExpired(ApprovalRequest request, String reason) {
        request = lockPending(request);
        request.markExpired(reason);
        request = requestRepository.save(request);
        auditLogService.record(
            ApprovalRequest.EXPIRY_ACTOR, request.getActionType(), TARGET_TYPE, request.getId().toString(),
            AuditOutcome.EXPIRED, reason
        );
        publishLifecycle("approval.resolved", request, Map.of(
            "decision", "expired",
            "decidedBy", ApprovalRequest.EXPIRY_ACTOR,
            "reason", reason == null ? "" : reason));
        publishExecutionRecorded(request);
        return request;
    }

    @Transactional
    void recordExecutionFailed(ApprovalRequest request, String decidedBy, String errorMessage) {
        request.markExecutionFailed(errorMessage);
        requestRepository.save(request);
        auditLogService.record(
            decidedBy, request.getActionType(), TARGET_TYPE, request.getId().toString(), AuditOutcome.FAILURE, errorMessage
        );
        // Terminal, non-retryable - the request has finished settling.
        publishExecutionRecorded(request);
    }

    @Transactional
    void recordNoExecutorRegistered(ApprovalRequest request, String decidedBy) {
        auditLogService.record(
            decidedBy, request.getActionType(), TARGET_TYPE, request.getId().toString(), AuditOutcome.FAILURE,
            "No executor registered for action type " + request.getActionType()
        );
        // Nothing will ever run it, so it is not "approved and waiting": the recovery sweep must not keep finding it.
        finishLocked(request);
    }

    static final String RECOVERY_ACTOR = "system:approval-recovery";

    /**
     * Makes the caller the one that runs an approved action. Under the row lock it refuses unless the request is still
     * APPROVED and nobody has started or finished it, so the thread that was just handed the decision and the recovery
     * sweep cannot both run it. {@code request} gets the markers the database now holds (see {@link
     * ApprovalRequest#copyExecutionMarkersFrom}).
     */
    @Transactional
    boolean claimExecution(ApprovalRequest request) {
        ApprovalRequest locked = requestRepository.findByIdForUpdate(request.getId()).orElse(null);
        if (locked == null || locked.getStatus() != ApprovalStatus.APPROVED
            || locked.getExecutionStartedAt() != null || locked.getExecutionCompletedAt() != null) {
            return false;
        }
        locked.markExecutionStarted();
        requestRepository.save(locked);
        request.copyExecutionMarkersFrom(locked);
        return true;
    }

    /** The action ended and its outcome is recorded elsewhere (or is success): stop the sweep looking at it. */
    @Transactional
    void recordExecutionFinished(ApprovalRequest request) {
        finishLocked(request);
    }

    private void finishLocked(ApprovalRequest request) {
        ApprovalRequest locked = requestRepository.findByIdForUpdate(request.getId()).orElse(null);
        if (locked == null || locked.getStatus() != ApprovalStatus.APPROVED || locked.getExecutionCompletedAt() != null) {
            return;
        }
        locked.markExecutionCompleted();
        requestRepository.save(locked);
        request.copyExecutionMarkersFrom(locked);
    }

    /**
     * An approved action nobody started and that is too old to run. Same lock-and-check as a claim, so a live caller
     * that starts it at this moment wins and this returns false; recorded as dead-lettered with {@code reason}.
     */
    @Transactional
    boolean recordNeverRan(ApprovalRequest request, String reason) {
        ApprovalRequest locked = requestRepository.findByIdForUpdate(request.getId()).orElse(null);
        if (locked == null || locked.getStatus() != ApprovalStatus.APPROVED
            || locked.getExecutionStartedAt() != null || locked.getExecutionCompletedAt() != null) {
            return false;
        }
        locked.markDeadLettered(reason);
        locked.markExecutionCompleted();
        requestRepository.save(locked);
        request.markDeadLettered(reason);
        request.copyExecutionMarkersFrom(locked);
        auditLogService.record(
            RECOVERY_ACTOR, locked.getActionType(), TARGET_TYPE, locked.getId().toString(), AuditOutcome.FAILURE, reason
        );
        publishExecutionRecorded(locked);
        return true;
    }

    /**
     * An approved action that started and never reported how it ended (the process died mid-run). What it did is
     * unknown, so it is NOT run again - a second send, charge or delete is worse than a reported gap. Recorded as
     * dead-lettered so a person or the agent can decide. Returns false when it finished or was handled first.
     */
    @Transactional
    boolean recordInterrupted(ApprovalRequest request, String reason) {
        ApprovalRequest locked = requestRepository.findByIdForUpdate(request.getId()).orElse(null);
        if (locked == null || locked.getStatus() != ApprovalStatus.APPROVED || locked.getExecutionCompletedAt() != null) {
            return false;
        }
        locked.markDeadLettered(reason);
        locked.markExecutionCompleted();
        requestRepository.save(locked);
        request.markDeadLettered(reason);
        request.copyExecutionMarkersFrom(locked);
        auditLogService.record(
            RECOVERY_ACTOR, locked.getActionType(), TARGET_TYPE, locked.getId().toString(), AuditOutcome.FAILURE, reason
        );
        publishExecutionRecorded(locked);
        return true;
    }

    // Autonomy/Guardrails Strategy Stage C. The three methods below reuse
    // AuditOutcome.FAILURE (rather than adding RETRY_SCHEDULED/DEAD_LETTERED
    // enum values) - same "don't touch a shared enum for a narrow new case"
    // reasoning as ApprovalStatus's EXECUTION_FAILED overload; the audit
    // trail's free-text summary is what actually distinguishes them, same
    // as it already does for "rejected" vs "no executor registered" above.
    // "system:retry-job" is used as decidedBy since these transitions are
    // never actor-initiated - there's no human/agent "who" to attribute them to.

    static final String RETRY_JOB_ACTOR = "system:retry-job";

    @Transactional
    void recordRetryScheduled(ApprovalRequest request, String errorMessage, java.time.Instant nextRetryAt) {
        request.scheduleRetry(errorMessage, nextRetryAt);
        requestRepository.save(request);
        auditLogService.record(
            RETRY_JOB_ACTOR, request.getActionType(), TARGET_TYPE, request.getId().toString(), AuditOutcome.FAILURE,
            "Retry " + request.getRetryCount() + " scheduled for " + nextRetryAt + ": " + errorMessage
        );
    }

    @Transactional
    void recordDeadLettered(ApprovalRequest request, String errorMessage) {
        request.markDeadLettered(errorMessage);
        requestRepository.save(request);
        auditLogService.record(
            RETRY_JOB_ACTOR, request.getActionType(), TARGET_TYPE, request.getId().toString(), AuditOutcome.FAILURE,
            "Dead-lettered after " + request.getRetryCount() + " retries: " + errorMessage
        );
        // Terminal - RetryFailedActionsJob never revisits this row.
        publishExecutionRecorded(request);
    }

    @Transactional
    void recordRetrySucceeded(ApprovalRequest request) {
        request.markExecutionSucceeded();
        requestRepository.save(request);
        auditLogService.record(
            RETRY_JOB_ACTOR, request.getActionType(), TARGET_TYPE, request.getId().toString(), AuditOutcome.APPROVED,
            "Retry succeeded after " + request.getRetryCount() + " attempt(s)"
        );
        // Terminal-success via retry. Note the executor's OWN direct write of
        // executionResult (e.g. AgentToolCallApprovalExecutor#persistExecutionResult)
        // already published this same event moments earlier in this exact call
        // stack (HumanApprovalGateService#retryExecution calls execute() before
        // calling this method) - a harmless duplicate, not a second real settle.
        publishExecutionRecorded(request);
    }
}
