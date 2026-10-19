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
