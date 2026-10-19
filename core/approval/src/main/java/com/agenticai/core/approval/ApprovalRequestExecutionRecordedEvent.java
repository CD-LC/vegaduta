package com.agenticai.core.approval;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Published the instant an {@link ApprovalRequest}'s execution OUTCOME
 * becomes durable: a terminal, non-retryable failure ({@link
 * ApprovalRequestRecorder#recordExecutionFailed}), retries exhausted
 * ({@link ApprovalRequestRecorder#recordDeadLettered}), a retry that finally
 * succeeded ({@link ApprovalRequestRecorder#recordRetrySucceeded}), or - the
 * most common case by far - a first-attempt {@link
 * ApprovalActionExecutor#execute} call returning normally and recording its
 * {@link ApprovalRequest#getExecutionResult()} (e.g. {@code
 * AgentToolCallApprovalExecutor}, {@code RunCodeWorkspaceApprovalExecutor}).
 *
 * <p>This module has no idea agent jobs exist and never will - it only
 * publishes a fact about an approval request's own state. {@code
 * AgentJobApprovalSweep} (core/agents) listens for it purely as an
 * OPTIMISATION: it lets a parked job resume the instant its blocking gate
 * finishes settling instead of waiting out that sweep's 90s replay-grace
 * window or its next 60s poll tick. The poll tick remains the mechanism of
 * record - it is what survives a JVM restart between the write below and
 * whatever consumes this event, and what makes correctness independent of
 * any listener existing at all.
 *
 * <p><b>Not published from every write - three are deliberately excluded</b>
 * because publishing from them would either be premature or a no-op:
 * <ul>
 *   <li>{@link ApprovalRequestRecorder#recordApproved} - the executor has
 *       not run yet at that point (see {@code HumanApprovalGateService
 *       #approve}), so nothing has "settled".</li>
 *   <li>{@link ApprovalRequestRecorder#recordRetryScheduled} - a NEW retry
 *       attempt is now pending, not finished; {@code recordRetrySucceeded}
 *       or a later terminal failure fires this event when that retry
 *       actually completes.</li>
 *   <li>{@link ApprovalRequestRecorder#recordNoExecutorRegistered} - never
 *       records an {@code executionResult} or changes {@code status}, so
 *       nothing about "settled" actually changed; a job parked on this
 *       outcome can only ever be resolved by the sweep's existing grace-
 *       window fallback, unchanged by this event.</li>
 * </ul>
 *
 * <p>Carries {@code requestedBy} alongside the id specifically so a listener
 * never has to re-read the row to learn it: that field is set once at
 * {@link ApprovalRequest} creation and never changes for its lifetime, and
 * it is the only piece of the row {@code AgentJobApprovalSweep} needs (to
 * recover the parked job's id via {@code AgentJobSession#jobIdOf}) to decide
 * whether this event is even job-related at all - most approval requests
 * (channel sends, workflow-node approvals, console tool-call gates) are not.
 */
public final class ApprovalRequestExecutionRecordedEvent extends ApplicationEvent {

    private final UUID requestId;
    private final String requestedBy;

    public ApprovalRequestExecutionRecordedEvent(Object source, UUID requestId, String requestedBy) {
        super(source);
        this.requestId = requestId;
        this.requestedBy = requestedBy;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getRequestedBy() {
        return requestedBy;
    }
}
