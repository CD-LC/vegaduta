package com.agenticai.core.agents.job;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The seam through which non-blocking human-in-the-loop approval plugs into
 * {@link AgentJobExecution} WITHOUT that class importing {@code job/approval/}
 * or {@code core/approval}.
 *
 * <p><b>Absent implementation == today's behaviour, exactly.</b> The default
 * never parks, so a deployment with no implementing bean runs every job straight
 * through to a terminal state as it does today. {@link AgentJobExecution}
 * resolves this via {@code ObjectProvider#getIfAvailable(() -> NEVER_PARKS)}.
 *
 * <p><b>Intended implementer: F-PR5</b>, as a {@code @Component} in {@code
 * com.agenticai.core.agents.job.approval}.
 */
public interface AgentJobApprovalPark {

    /**
     * Asks "did this turn stop because it hit a human approval gate, rather than
     * because it finished the work?".
     *
     * <p>Called after the turn returns and after the cancel checkpoint, and
     * BEFORE the output contract finalizes anything - a gated job has no final
     * answer to validate, so validating it first would fail the job for a schema
     * violation it never had a chance to satisfy.
     *
     * <p><b>An implementation MUST NOT write any {@code agent_job} status
     * itself.</b> It answers a question; the caller performs the durable park
     * (the guarded {@code AgentJobRepository#parkForApproval} UPDATE) and then
     * returns WITHOUT calling {@code finish()}, which is mechanically what
     * "releases the worker without terminating the job" means: the pool wrapper's
     * {@code finally} frees the slot, the heartbeat stops touching the row, and
     * {@code AgentJobExecutor#isActiveHere} goes false. Two status writers for
     * one transition is precisely the corruption the guarded-UPDATE design of
     * this whole package exists to prevent.
     *
     * <p>It must also be cheap - it runs on a worker thread that still holds its
     * slot - and it must not throw; a failure to detect a gate should leave the
     * job to finish normally, not kill it.
     *
     * @param turnStartedAt when THIS execution's turn began. An implementation
     *     uses it to ignore approval requests left behind by an earlier turn or
     *     an earlier park of the same job.
     * @return empty when the job did not hit a gate (the overwhelmingly common
     *     case, and the only case the default knows about).
     */
    default Optional<ParkDecision> parkIfGated(AgentJob job, Instant turnStartedAt) {
        return Optional.empty();
    }

    /**
     * @param approvalDeadlineAt when an unanswered approval should fail the job.
     *     Written to {@code agent_job.approval_deadline_at} and read by the
     *     approval sweep - a park with no deadline is a job that waits forever.
     * @param approvalRequestId the blocking {@code approval_request} row, for the
     *     sweep to re-read and for operators to correlate. May be {@code null} if
     *     the implementation cannot identify a single blocking request.
     * @param reason short human-readable prose recorded alongside the park.
     */
    record ParkDecision(Instant approvalDeadlineAt, UUID approvalRequestId, String reason) {
    }

    /** The no-op default used when no implementation is registered. */
    AgentJobApprovalPark NEVER_PARKS = new AgentJobApprovalPark() {
    };
}
