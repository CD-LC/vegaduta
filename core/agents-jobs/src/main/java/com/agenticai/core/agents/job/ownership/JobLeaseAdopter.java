package com.agenticai.core.agents.job.ownership;

import com.agenticai.core.agents.job.ownership.JobLeaseStore.LeasedJob;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.Plan;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Applies {@link JobResumePlanner}'s decision to ONE expired-lease job, through the {@link JobLeaseStore}
 * compare-and-set. This is the whole adoption mechanism, deliberately a plain class so the same code runs in
 * production (wired by {@code AgentJobLeaseSweep}) and in the two-replica kill simulation.
 *
 * <p>Adoption is "re-queue, then let the normal claim path run it": RUNNING -&gt; QUEUED here, then any
 * replica's dispatch tick claims it with the existing QUEUED -&gt; RUNNING guarded UPDATE. So a job is only ever
 * executed by the winner of {@code claim}, and the ABA guard in {@link JobLeaseStore#requeueExpired} (observed
 * {@code attempt_count} + still-expired lease) means two sweepers racing on one row produce exactly one
 * re-queue.
 */
public final class JobLeaseAdopter {

    public enum Outcome {
        NOT_EXPIRED,
        ACTIVE_HERE,
        REQUEUED,
        FAILED,
        NEEDS_REVIEW,
        CANCELLED,
        /** The guarded UPDATE matched nothing: somebody else adopted/finished/heartbeated first. Not an error. */
        LOST_RACE
    }

    private final JobLeaseStore store;
    private final Function<LeasedJob, JobResumePlanner.Facts> factsReader;
    private final Supplier<JobResumePlanner.Policy> policy;
    private final IntSupplier leaseSeconds;
    private final Predicate<UUID> activeHere;
    private final Consumer<LeasedJob> dispatcher;

    /**
     * @param activeHere true when THIS replica is genuinely executing the job (its heartbeat writer is merely
     *     behind): never adopt that
     * @param dispatcher best-effort local hand-off of a job this call just re-queued; may be a no-op. Any
     *     replica's dispatch tick would also pick it up, so failure here loses nothing
     */
    public JobLeaseAdopter(
        JobLeaseStore store,
        Function<LeasedJob, JobResumePlanner.Facts> factsReader,
        Supplier<JobResumePlanner.Policy> policy,
        IntSupplier leaseSeconds,
        Predicate<UUID> activeHere,
        Consumer<LeasedJob> dispatcher
    ) {
        this.store = store;
        this.factsReader = factsReader;
        this.policy = policy;
        this.leaseSeconds = leaseSeconds;
        this.activeHere = activeHere;
        this.dispatcher = dispatcher;
    }

    public Outcome resolve(LeasedJob job, Instant now) {
        int lease = leaseSeconds.getAsInt();
        if (!JobLeasePolicy.isExpired(job.heartbeatAt(), now, lease)) {
            return Outcome.NOT_EXPIRED;
        }
        if (activeHere.test(job.id())) {
            return Outcome.ACTIVE_HERE;
        }
        Instant cutoff = JobLeasePolicy.expiryCutoff(now, lease);
        Plan plan = JobResumePlanner.plan(job, () -> factsReader.apply(job), policy.get(), now);
        switch (plan.action()) {
            case REQUEUE: {
                // Stable "lease-adopted:" tag: agent_job.resume_reason is shared with the approval-park reason
                // (written by parkForApproval, V211), so a reader can tell the two apart by this prefix.
                String reason = "lease-adopted: adopted after lease expiry (previous owner '" + job.ownerInstance() + "', claim "
                    + job.attemptCount() + "): " + plan.reason();
                if (!store.requeueExpired(job.id(), job.attemptCount(), cutoff, reason)) {
                    return Outcome.LOST_RACE;
                }
                dispatcher.accept(job);
                return Outcome.REQUEUED;
            }
            case CANCEL:
                return store.cancelExpired(job.id(), job.attemptCount(), cutoff, now)
                    ? Outcome.CANCELLED : Outcome.LOST_RACE;
            case NEEDS_REVIEW:
                return store.failExpired(
                    job.id(), job.attemptCount(), cutoff, plan.errorCode(),
                    "The worker running this job stopped reporting (its ownership lease expired) and it was NOT "
                        + "re-run because it may already have performed a write/send action: " + plan.detail()
                        + ". Check the external system, then re-submit the job if the work is still wanted.",
                    now)
                    ? Outcome.NEEDS_REVIEW : Outcome.LOST_RACE;
            case FAIL:
            default:
                return store.failExpired(
                    job.id(), job.attemptCount(), cutoff, plan.errorCode(),
                    "The worker running this job stopped reporting (its ownership lease expired) and it was not "
                        + "re-run: " + plan.detail(),
                    now)
                    ? Outcome.FAILED : Outcome.LOST_RACE;
        }
    }
}
