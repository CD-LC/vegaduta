package com.agenticai.core.approval;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The retry/dead-letter path of {@link HumanApprovalGateService} - {@code RetryFailedActionsJob}'s
 * re-execution plus the failure branching and backoff - extracted so the service stays under the
 * file-size cap. Package-private; callers still go through {@link HumanApprovalGateService}.
 */
final class HumanApprovalGateRetry {

    private static final Logger log = LoggerFactory.getLogger(HumanApprovalGateRetry.class);

    private final HumanApprovalGateService owner;

    HumanApprovalGateRetry(HumanApprovalGateService owner) {
        this.owner = owner;
    }

    /**
     * Autonomy/Guardrails Strategy Stage C - called only by {@code RetryFailedActionsJob} (via
     * {@code TenantSchemaIterator}, so {@code TenantContext} is already correctly set for this
     * request's tenant before this method runs), never by a live synchronous caller. Unlike {@link
     * #runExecutor}, this deliberately does NOT rethrow - a scheduled job has no HTTP caller
     * waiting to see a 5xx, and letting one request's exception propagate would abort the whole
     * batch for every other tenant/request {@code RetryFailedActionsJob} still needs to process.
     */
    void retryExecution(ApprovalRequest request) {
        ApprovalActionExecutor executor =
                owner.executorRegistry.find(request.getActionType()).orElse(null);
        if (executor == null) {
            log.warn(
                    "Action type '{}' has no registered executor - retry for request {} abandoned",
                    request.getActionType(),
                    request.getId());
            owner.recorder.recordNoExecutorRegistered(
                    request, ApprovalRequestRecorder.RETRY_JOB_ACTOR);
            return;
        }
        try {
            executor.execute(request);
            owner.recorder.recordRetrySucceeded(request);
            reportOutcome(
                    executor,
                    request,
                    ApprovalOutcomePort.Outcome.COMPLETED,
                    request.getExecutionResult());
        } catch (RuntimeException e) {
            recordFailureAndMaybeScheduleRetry(request, e, ApprovalRequestRecorder.RETRY_JOB_ACTOR);
        }
    }

    /**
     * Branches a failed {@link ApprovalActionExecutor#execute} call three ways: retryable and under
     * the attempt cap schedules another try with exponential backoff; retryable but exhausted
     * dead-letters the request (terminal - {@code RetryFailedActionsJob} never revisits a
     * dead-lettered row); anything else (the pre-Stage-C behavior) fails permanently on the first
     * attempt, unchanged.
     */
    void recordFailureAndMaybeScheduleRetry(
            ApprovalRequest request, RuntimeException e, String decidedBy) {
        if (!(e instanceof RetryableActionFailure)) {
            owner.recorder.recordExecutionFailed(request, decidedBy, e.getMessage());
            reportOutcome(request, ApprovalOutcomePort.Outcome.FAILED, failureText(e));
            return;
        }
        if (request.getRetryCount() >= owner.maxRetryAttempts) {
            owner.recorder.recordDeadLettered(request, e.getMessage());
            // The last attempt failed: the call is over, which is what the conversation should
            // hear. A
            // failure that will be retried is not the end of the call and is not reported.
            reportOutcome(request, ApprovalOutcomePort.Outcome.FAILED, failureText(e));
            return;
        }
        Instant nextRetryAt =
                Instant.now().plusSeconds(backoffDelaySeconds(request.getRetryCount() + 1));
        owner.recorder.recordRetryScheduled(request, e.getMessage(), nextRetryAt);
    }

    /**
     * Exponential backoff, capped at {@link #owner.maxDelaySeconds}: attempt 1 -> {@code
     * owner.baseDelaySeconds}, attempt 2 -> {@code owner.baseDelaySeconds * 2}, attempt 3 -> {@code
     * * 4}, and so on. The shift is capped at 20 purely to keep the intermediate {@code long} from
     * ever overflowing before the {@code Math.min} clamp runs - {@code owner.maxDelaySeconds} is
     * what actually bounds the real delay in practice.
     */
    long backoffDelaySeconds(int attemptNumber) {
        long delay = owner.baseDelaySeconds * (1L << Math.min(attemptNumber - 1, 20));
        return Math.min(delay, owner.maxDelaySeconds);
    }

    static String failureText(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    void reportOutcome(
            ApprovalActionExecutor executor,
            ApprovalRequest request,
            ApprovalOutcomePort.Outcome outcome,
            String text) {
        ApprovalOutcomePort port = owner.outcomePort;
        if (port == null || executor.reportsOutcomeItself()) {
            return;
        }
        try {
            port.afterDecision(request, outcome, text);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not report the {} outcome of approval request {} (action type '{}') to its conversation: {}",
                    outcome,
                    request.getId(),
                    request.getActionType(),
                    e.getMessage());
        }
    }

    void reportOutcome(ApprovalRequest request, ApprovalOutcomePort.Outcome outcome, String text) {
        owner.executorRegistry
                .find(request.getActionType())
                .ifPresent(executor -> reportOutcome(executor, request, outcome, text));
    }

    ApprovalRequest getPendingOrThrow(UUID id) {
        ApprovalRequest request =
                owner.requestRepository
                        .findById(id)
                        .orElseThrow(() -> new ApprovalRequestNotFoundException(id));
        if (request.getStatus() != ApprovalStatus.PENDING) {
            throw new ApprovalRequestAlreadyDecidedException(id, request.getStatus());
        }
        return request;
    }
}
