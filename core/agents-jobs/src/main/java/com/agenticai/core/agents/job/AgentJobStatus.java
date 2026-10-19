package com.agenticai.core.agents.job;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of one {@link AgentJob}.
 *
 * <p><b>{@link #AWAITING_APPROVAL} is NOT terminal, and that is the single most
 * important sentence in this file.</b> {@code WorkflowRunStatus}'s own javadoc
 * records that the workflow engine originally shipped its approval state as
 * effectively terminal and had to be corrected afterwards, because "finished"
 * counts, "completed" filters and the run list all quietly treated a run that
 * was merely waiting for a human as a run that was over. This enum copies the
 * correction rather than the mistake: a parked job holds NO worker
 * ({@link #occupiesWorker} is {@code true} only for {@link #RUNNING}), is not
 * billed as finished, and must never appear in a "completed" count. The
 * predicates below ship with tests even though this PR produces no parked jobs
 * at all - the deferred approval-parking PR inherits a state machine that
 * already treats its state correctly instead of having to retrofit it.
 *
 * <p>Transitions are enforced INSIDE {@link AgentJob} (see its private
 * {@code transitionTo}), never in callers, so there is exactly one place that
 * can be wrong and exactly one place to read to know what is legal.
 */
public enum AgentJobStatus {

    /** Durably enqueued, not yet claimed by any worker. The table IS the queue. */
    QUEUED,

    /** Claimed by exactly one worker (see {@code agent_job.owner_instance}) and executing. */
    RUNNING,

    /**
     * Parked on a human approval gate. Non-terminal, holds no worker - the
     * deferred approval-parking PR is what actually produces this state.
     */
    AWAITING_APPROVAL,

    SUCCEEDED,
    FAILED,
    CANCELLED;

    private static final Set<AgentJobStatus> TERMINAL =
        Collections.unmodifiableSet(EnumSet.of(SUCCEEDED, FAILED, CANCELLED));

    private static final Set<AgentJobStatus> LIVE =
        Collections.unmodifiableSet(EnumSet.of(QUEUED, RUNNING, AWAITING_APPROVAL));

    /** {@code true} for {@link #SUCCEEDED}, {@link #FAILED}, {@link #CANCELLED} only. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * Whether a job in this state is occupying one of the bounded worker
     * threads. {@link #RUNNING} only - deliberately NOT {@link
     * #AWAITING_APPROVAL}, which is exactly the distinction that lets a job
     * wait days for a human without starving the pool.
     */
    public boolean occupiesWorker() {
        return this == RUNNING;
    }

    /**
     * The complete transition table:
     * <pre>
     *   QUEUED            -&gt; RUNNING, CANCELLED, FAILED
     *   RUNNING           -&gt; AWAITING_APPROVAL, QUEUED, SUCCEEDED, FAILED, CANCELLED
     *   AWAITING_APPROVAL -&gt; RUNNING, FAILED, CANCELLED
     *   SUCCEEDED/FAILED/CANCELLED -&gt; {}
     * </pre>
     *
     * <p>{@code QUEUED -> FAILED} is legal (and used) for a job that expires in
     * the queue past {@code app.agent-jobs.queue-ttl}: its frozen requester
     * authority has gone stale, so running it later would mean acting on a
     * snapshot of permissions nobody re-checked.
     *
     * <p>{@code RUNNING -> QUEUED} is the ONE backwards edge, and it exists for
     * exactly one caller: the durability policy's bounded re-queue of an ORPHAN
     * that provably did zero chargeable work (no turns used, no tool calls
     * logged). It is not a general "restart this job" move - re-queuing a job
     * that had already started a turn would re-charge the requester's budget and
     * re-fire non-idempotent tool side effects, since there is no job-level cost
     * ledger and {@code tool_call_log} is a log, not a dedupe key.
     *
     * <p><b>No new constant is added for any of this.</b> V207's {@code
     * agent_job_status_chk} CHECK and three status-literal partial indexes
     * enumerate exactly these six values, and this switch is exhaustive with no
     * {@code default} - a seventh constant would break the build in one place and
     * fail silently in half a dozen others.
     */
    public boolean canTransitionTo(AgentJobStatus next) {
        if (next == null || next == this) {
            return false;
        }
        return switch (this) {
            case QUEUED -> next == RUNNING || next == CANCELLED || next == FAILED;
            case RUNNING -> next == AWAITING_APPROVAL || next == QUEUED
                || next == SUCCEEDED || next == FAILED || next == CANCELLED;
            case AWAITING_APPROVAL -> next == RUNNING || next == FAILED || next == CANCELLED;
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
    }

    /**
     * The non-terminal states: {@link #QUEUED}, {@link #RUNNING}, {@link
     * #AWAITING_APPROVAL}. This exact set is the schedule overlap guard's
     * definition of "there is already a live job for this schedule" (and the
     * {@code WHERE} clause of V208's unique index), so it lives here rather
     * than being re-listed at each call site where one of the three could be
     * forgotten.
     */
    public static Set<AgentJobStatus> live() {
        return LIVE;
    }
}
