package com.agenticai.core.agents.job;

/**
 * The seam through which durability/recovery plugs into {@link
 * AgentJobRunner#reapOrphans} WITHOUT that class importing {@code
 * job/durability/}.
 *
 * <p><b>Absent implementation == today's behaviour, exactly.</b> The default
 * verdict is {@link Verdict#FAIL}, which is the shipped behaviour: an orphan -
 * a RUNNING row whose heartbeat stopped and which is not active on this replica -
 * is failed loudly with {@code error_code = ORPHANED}. {@link AgentJobRunner}
 * resolves this via {@code ObjectProvider#getIfAvailable(() -> FAIL_LOUDLY)}, so
 * a deployment with no implementing bean reaps exactly as it does today.
 *
 * <p><b>Why one decision point rather than a second sweep.</b> Two sweeps racing
 * the same rows would produce a nondeterministic verdict for the same job. The
 * existing orphan sweep asks this question once, in the one place that already
 * holds the advisory lock, already iterates tenant schemas, and already skips
 * jobs live on this replica.
 *
 * <p><b>Intended implementer: F-PR4</b>, as a {@code @Component} in {@code
 * com.agenticai.core.agents.job.durability}. The safety bar for returning {@link
 * Verdict#REQUEUE} is high and is not this interface's to relax: there is no
 * job-level cost ledger, so a re-queue is only safe for an orphan that provably
 * did ZERO chargeable work (no turns used, no tool calls logged for its session)
 * and is still within its attempt and queue-TTL budgets.
 */
public interface AgentJobOrphanRecoveryPolicy {

    /**
     * {@link #FAIL} terminates the orphan with {@code ORPHANED} (today's
     * behaviour); {@link #REQUEUE} returns it to QUEUED for another attempt.
     */
    enum Verdict {
        FAIL,
        REQUEUE
    }

    /**
     * @param orphan a RUNNING row whose heartbeat has gone stale and which is NOT
     *     running on this replica. Already loaded by the sweep, so an
     *     implementation can read {@code turnsUsed}, {@code attemptCount},
     *     {@code createdAt}, {@code sessionId} and {@code cancelRequested}
     *     without a second query.
     * @return {@link Verdict#FAIL} by default - a re-queue must be argued for,
     *     never assumed. A {@link Verdict#REQUEUE} that the guarded UPDATE then
     *     loses falls back to the FAIL path, so the job always reaches a decided
     *     state.
     */
    default Verdict decide(AgentJob orphan) {
        return Verdict.FAIL;
    }

    /**
     * Called by the sweep only AFTER {@link #decide} answered {@link Verdict#FAIL} for the same orphan: a
     * human-readable reason when the job must be reviewed by a person rather than simply retried (for example a
     * write/send tool call that may already have happened). Non-null makes the sweep record
     * {@code ORPHANED_NEEDS_REVIEW} with this message instead of the generic {@code ORPHANED}. Default null.
     */
    default String takeNeedsReviewReason(AgentJob orphan) {
        return null;
    }

    /** The default used when no implementation is registered. */
    AgentJobOrphanRecoveryPolicy FAIL_LOUDLY = new AgentJobOrphanRecoveryPolicy() {
    };
}
