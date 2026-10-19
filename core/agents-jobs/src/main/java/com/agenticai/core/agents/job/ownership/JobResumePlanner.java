package com.agenticai.core.agents.job.ownership;

import com.agenticai.core.agents.job.ownership.JobLeaseStore.LeasedJob;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Pure decision: what to do with a RUNNING job whose lease expired (its replica died, was replaced, or
 * released it on shutdown). No Spring, no I/O; the journal and tool-log facts arrive through a
 * {@link Supplier} that is only invoked when the decision actually needs them.
 *
 * <h2>The safety argument (unchanged from {@code ZeroWorkOrphanRecoveryPolicy}, extended with a bound)</h2>
 * A job may be handed to another replica only when running it again cannot double-charge or repeat a side
 * effect that already happened:
 * <ul>
 *   <li>{@code turns_used == 0} and no tool call logged: the previous attempt died before any model call
 *       (the counter is incremented BEFORE the call) - nothing was charged, nothing fired. {@code zero-work}.</li>
 *   <li>the journal shows the last started turn COMPLETED with a result: the new attempt reuses that result,
 *       no model call, no tool call. {@code journal-turn-completed}. Needs resume-from-journal.</li>
 *   <li>the last turn STARTED but never COMPLETED: only re-run when {@code resume-rerun-incomplete-turn} is
 *       on AND durable in-flight tracking proves no DURABLE (write/send) tool call began without a recorded
 *       completion. Completed durable effects replay from the effect journal; read-only tools may simply
 *       re-run. A DURABLE call that began but has no completion is NEVER re-fired: the job is failed with
 *       {@code ORPHANED_NEEDS_REVIEW} so a human checks the external system.</li>
 *   <li>everything else fails loudly, exactly as today.</li>
 * </ul>
 * <b>Bounded:</b> {@code attempt_count} counts claims; once it reaches {@code maxAttempts} the job fails with
 * {@code RESUME_ATTEMPTS_EXHAUSTED} so a poison job (one that kills its worker every time) cannot loop.
 */
public final class JobResumePlanner {

    public enum Action {
        /** RUNNING -&gt; QUEUED; another worker claims it and (per the journal) resumes. */
        REQUEUE,
        /** RUNNING -&gt; FAILED with {@link Plan#errorCode()}. */
        FAIL,
        /** RUNNING -&gt; FAILED {@code ORPHANED_NEEDS_REVIEW}: a human must check an external system. */
        NEEDS_REVIEW,
        /** RUNNING -&gt; CANCELLED: a cancel was requested and the owner that would have observed it is gone. */
        CANCEL
    }

    /** State of the journal row of the job's last STARTED turn. */
    public enum TurnState {
        NONE,
        STARTED,
        COMPLETED_BLANK,
        COMPLETED_WITH_RESULT
    }

    /**
     * Facts read from the journal and tool log.
     *
     * @param journalUsable step journal present, enabled and resume-from-journal on
     * @param loggedToolCalls rows in tool_call_log for the job's session, or {@code null} when the lookup failed
     *     (an unprovable zero-work claim is not a zero-work claim)
     */
    public record Facts(
        boolean journalUsable,
        TurnState lastTurn,
        boolean durableTrackingActive,
        boolean incompleteDurableCall,
        Long loggedToolCalls
    ) {
    }

    /**
     * Operator policy: the resume flags, the attempt bound and the queue TTL. {@code requeueOrphans} mirrors
     * {@code durability.requeue-zero-work-orphans}: when false nothing is ever re-queued (every orphan fails),
     * exactly what that flag means for the legacy orphan sweep.
     */
    public record Policy(
        boolean resumeFromJournal, boolean rerunIncompleteTurn, int maxAttempts, Duration queueTtl,
        boolean requeueOrphans
    ) {
        /** Re-queuing allowed (the default of {@code requeue-zero-work-orphans}). */
        public Policy(boolean resumeFromJournal, boolean rerunIncompleteTurn, int maxAttempts, Duration queueTtl) {
            this(resumeFromJournal, rerunIncompleteTurn, maxAttempts, queueTtl, true);
        }
    }

    /**
     * @param reason low-cardinality tag (metric-safe)
     * @param errorCode the {@code agent_job.error_code} for FAIL / NEEDS_REVIEW, else null
     * @param detail prose for logs and the job's error message
     */
    public record Plan(Action action, String reason, String errorCode, String detail) {
    }

    public static final String ERR_ORPHANED = "ORPHANED";
    public static final String ERR_NEEDS_REVIEW = "ORPHANED_NEEDS_REVIEW";
    public static final String ERR_ATTEMPTS_EXHAUSTED = "RESUME_ATTEMPTS_EXHAUSTED";

    private JobResumePlanner() {
    }

    public static Plan plan(LeasedJob job, Supplier<Facts> factsSupplier, Policy policy, Instant now) {
        if (job.cancelRequested()) {
            return new Plan(Action.CANCEL, "cancel-requested", null,
                "a cancel was requested and the replica that would have observed it is gone");
        }
        int maxAttempts = Math.max(1, policy.maxAttempts());
        if (job.attemptCount() >= maxAttempts) {
            return new Plan(Action.FAIL, "attempts-exhausted", ERR_ATTEMPTS_EXHAUSTED,
                "it was claimed " + job.attemptCount() + " time(s) and its worker was lost every time; the bound is "
                    + maxAttempts + " (app.agent-jobs.ownership.resume-max-attempts), so it is not tried again");
        }
        Instant queueCutoff = now.minus(policy.queueTtl());
        if (job.createdAt() == null || job.createdAt().isBefore(queueCutoff)) {
            return new Plan(Action.FAIL, "queue-ttl-expired", ERR_ORPHANED,
                "it was submitted before the queue-ttl cutoff, so the requester's frozen authority is stale and a "
                    + "re-queue would be expired by the next dispatch tick anyway");
        }

        if (!policy.requeueOrphans()) {
            return new Plan(Action.FAIL, "requeue-disabled", ERR_ORPHANED,
                "app.agent-jobs.durability.requeue-zero-work-orphans is off, so every orphan is failed, not re-queued");
        }

        Facts facts;
        try {
            facts = factsSupplier.get();
        } catch (RuntimeException lookupFailed) {
            facts = null;
        }
        if (facts == null) {
            return new Plan(Action.FAIL, "lookup-failed", ERR_ORPHANED,
                "the journal/tool-log lookup failed, and an unprovable safe-to-resume claim is not a safe-to-resume claim");
        }

        if (job.turnsUsed() <= 0) {
            if (facts.loggedToolCalls() == null) {
                return new Plan(Action.FAIL, "lookup-failed", ERR_ORPHANED,
                    "the tool-call lookup failed, so zero work cannot be proven");
            }
            if (facts.loggedToolCalls() > 0) {
                return new Plan(Action.FAIL, "tool-calls-logged", ERR_ORPHANED,
                    facts.loggedToolCalls() + " tool call(s) are already logged for the job's session and tool side "
                        + "effects are not idempotent");
            }
            return new Plan(Action.REQUEUE, "zero-work", null,
                "turns_used=0 and no tool calls logged: the previous attempt died before the first model call");
        }

        // A turn was STARTED: a model call was entered and may already be billed.
        if (!policy.resumeFromJournal() || !facts.journalUsable()) {
            return new Plan(Action.FAIL, "turn-started", ERR_ORPHANED,
                "it had already started a turn (turns_used=" + job.turnsUsed() + ") and resuming from the step "
                    + "journal is off, so a re-run would charge the requester's budget a second time");
        }
        TurnState state = facts.lastTurn() == null ? TurnState.NONE : facts.lastTurn();
        switch (state) {
            case COMPLETED_WITH_RESULT:
                return new Plan(Action.REQUEUE, "journal-turn-completed", null,
                    "the journal proves the last turn completed; the new attempt reuses its result");
            case NONE:
                return new Plan(Action.FAIL, "journal-no-record", ERR_ORPHANED,
                    "it had started a turn but the step journal has no record of it, so there is no safe resume point");
            case STARTED:
            case COMPLETED_BLANK:
            default:
                break;
        }
        if (facts.durableTrackingActive() && facts.incompleteDurableCall()) {
            return new Plan(Action.NEEDS_REVIEW, "durable-call-in-flight", ERR_NEEDS_REVIEW,
                "a write/send tool call of the last turn started but never recorded completion");
        }
        if (!policy.rerunIncompleteTurn()) {
            return new Plan(Action.FAIL, "turn-incomplete-no-rerun", ERR_ORPHANED,
                "its last turn started but did not complete and re-running incomplete turns is off "
                    + "(app.agent-jobs.durability.resume-rerun-incomplete-turn)");
        }
        if (!facts.durableTrackingActive()) {
            return new Plan(Action.NEEDS_REVIEW, "durable-tracking-off", ERR_NEEDS_REVIEW,
                "in-flight tracking of write/send tool calls is off (app.tools.effect-journal.enabled), so it cannot "
                    + "be proven that no write/send call was in flight");
        }
        return new Plan(Action.REQUEUE, "journal-rerun-incomplete-turn", null,
            "the last turn did not complete; no write/send call was in flight, so it is re-run (completed durable "
                + "effects replay from the effect journal, read-only tools simply run again)");
    }
}
