package com.agenticai.core.agents.job.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.job.ownership.JobLeaseStore.LeasedJob;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.Action;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.Facts;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.Plan;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.Policy;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.TurnState;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JobResumePlannerTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final Policy ZERO_WORK_ONLY = new Policy(false, false, 3, Duration.ofHours(1));
    private static final Policy RESUME = new Policy(true, false, 3, Duration.ofHours(1));
    private static final Policy RESUME_RERUN = new Policy(true, true, 3, Duration.ofHours(1));

    private static LeasedJob job(int attempt, int turnsUsed, boolean cancel, Instant createdAt) {
        return new LeasedJob(UUID.randomUUID(), "t", UUID.randomUUID(), "job:x", "dead-replica",
            NOW.minusSeconds(600), attempt, turnsUsed, cancel, createdAt);
    }

    private static LeasedJob job(int attempt, int turnsUsed) {
        return job(attempt, turnsUsed, false, NOW.minusSeconds(120));
    }

    private static Facts facts(boolean usable, TurnState turn, boolean tracking, boolean incomplete, Long toolCalls) {
        return new Facts(usable, turn, tracking, incomplete, toolCalls);
    }

    private static Plan plan(LeasedJob j, Facts f, Policy p) {
        return JobResumePlanner.plan(j, () -> f, p, NOW);
    }

    @Test
    void aCancelRequestWinsAndNeverTouchesTheFacts() {
        AtomicInteger calls = new AtomicInteger();
        Plan p = JobResumePlanner.plan(job(1, 0, true, NOW.minusSeconds(5)),
            () -> {
                calls.incrementAndGet();
                return facts(true, TurnState.NONE, false, false, 0L);
            }, RESUME_RERUN, NOW);
        assertThat(p.action()).isEqualTo(Action.CANCEL);
        assertThat(calls.get()).isZero();
    }

    @Test
    void zeroWorkIsRequeuedEvenWithTheResumeFlagsOff() {
        Plan p = plan(job(1, 0), facts(false, TurnState.NONE, false, false, 0L), ZERO_WORK_ONLY);
        assertThat(p.action()).isEqualTo(Action.REQUEUE);
        assertThat(p.reason()).isEqualTo("zero-work");
    }

    @Test
    void zeroTurnsButLoggedToolCallsFails() {
        Plan p = plan(job(1, 0), facts(false, TurnState.NONE, false, false, 2L), ZERO_WORK_ONLY);
        assertThat(p.action()).isEqualTo(Action.FAIL);
        assertThat(p.reason()).isEqualTo("tool-calls-logged");
        assertThat(p.errorCode()).isEqualTo(JobResumePlanner.ERR_ORPHANED);
    }

    @Test
    void anUnprovableZeroWorkClaimFails() {
        Plan p = plan(job(1, 0), facts(false, TurnState.NONE, false, false, null), ZERO_WORK_ONLY);
        assertThat(p.action()).isEqualTo(Action.FAIL);
        assertThat(p.reason()).isEqualTo("lookup-failed");
    }

    @Test
    void aFactsLookupThatThrowsFails() {
        Plan p = JobResumePlanner.plan(job(1, 1), () -> {
            throw new IllegalStateException("db down");
        }, RESUME_RERUN, NOW);
        assertThat(p.action()).isEqualTo(Action.FAIL);
        assertThat(p.reason()).isEqualTo("lookup-failed");
    }

    @Test
    void aNullFactsObjectFails() {
        Plan p = JobResumePlanner.plan(job(1, 1), () -> null, RESUME_RERUN, NOW);
        assertThat(p.action()).isEqualTo(Action.FAIL);
    }

    @Test
    void aStartedTurnFailsWhenResumeFromJournalIsOffTheShippedBehaviour() {
        Plan p = plan(job(1, 1), facts(false, TurnState.COMPLETED_WITH_RESULT, true, false, 3L), ZERO_WORK_ONLY);
        assertThat(p.action()).isEqualTo(Action.FAIL);
        assertThat(p.reason()).isEqualTo("turn-started");
    }

    @Test
    void aStartedTurnFailsWhenTheJournalIsNotUsable() {
        Plan p = plan(job(1, 1), facts(false, TurnState.NONE, false, false, 0L), RESUME);
        assertThat(p.action()).isEqualTo(Action.FAIL);
        assertThat(p.reason()).isEqualTo("turn-started");
    }

    @Test
    void aCompletedTurnWithAResultIsRequeuedToReuseIt() {
        Plan p = plan(job(1, 1), facts(true, TurnState.COMPLETED_WITH_RESULT, true, false, 4L), RESUME);
        assertThat(p.action()).isEqualTo(Action.REQUEUE);
        assertThat(p.reason()).isEqualTo("journal-turn-completed");
    }

    @Test
    void aStartedTurnWithNoJournalRowHasNoResumePoint() {
        Plan p = plan(job(1, 1), facts(true, TurnState.NONE, true, false, 0L), RESUME_RERUN);
        assertThat(p.action()).isEqualTo(Action.FAIL);
        assertThat(p.reason()).isEqualTo("journal-no-record");
    }

    @Test
    void aDurableCallThatBeganWithoutCompletionIsNeverRefiredItNeedsReview() {
        Plan p = plan(job(1, 1), facts(true, TurnState.STARTED, true, true, 2L), RESUME_RERUN);
        assertThat(p.action()).isEqualTo(Action.NEEDS_REVIEW);
        assertThat(p.errorCode()).isEqualTo(JobResumePlanner.ERR_NEEDS_REVIEW);
        assertThat(p.reason()).isEqualTo("durable-call-in-flight");
    }

    @Test
    void aDurableCallInFlightNeedsReviewEvenWhenRerunIsOff() {
        Plan p = plan(job(1, 1), facts(true, TurnState.STARTED, true, true, 2L), RESUME);
        assertThat(p.action()).isEqualTo(Action.NEEDS_REVIEW);
    }

    @Test
    void anIncompleteTurnWithNothingInFlightIsOnlyRerunWhenTheRerunFlagIsOn() {
        Facts f = facts(true, TurnState.STARTED, true, false, 2L);
        assertThat(plan(job(1, 1), f, RESUME).action()).isEqualTo(Action.FAIL);
        assertThat(plan(job(1, 1), f, RESUME).reason()).isEqualTo("turn-incomplete-no-rerun");
        Plan rerun = plan(job(1, 1), f, RESUME_RERUN);
        assertThat(rerun.action()).isEqualTo(Action.REQUEUE);
        assertThat(rerun.reason()).isEqualTo("journal-rerun-incomplete-turn");
    }

    @Test
    void aBlankCompletedTurnIsTreatedAsIncomplete() {
        Plan p = plan(job(1, 1), facts(true, TurnState.COMPLETED_BLANK, true, false, 0L), RESUME_RERUN);
        assertThat(p.action()).isEqualTo(Action.REQUEUE);
        assertThat(p.reason()).isEqualTo("journal-rerun-incomplete-turn");
    }

    @Test
    void withoutInFlightTrackingARerunCannotBeProvenSafeSoItNeedsReview() {
        Plan p = plan(job(1, 1), facts(true, TurnState.STARTED, false, false, 2L), RESUME_RERUN);
        assertThat(p.action()).isEqualTo(Action.NEEDS_REVIEW);
        assertThat(p.reason()).isEqualTo("durable-tracking-off");
    }

    @Test
    void theAttemptBoundStopsAPoisonJob() {
        assertThat(plan(job(2, 0), facts(false, TurnState.NONE, false, false, 0L), RESUME).action())
            .isEqualTo(Action.REQUEUE);
        Plan exhausted = plan(job(3, 0), facts(false, TurnState.NONE, false, false, 0L), RESUME);
        assertThat(exhausted.action()).isEqualTo(Action.FAIL);
        assertThat(exhausted.reason()).isEqualTo("attempts-exhausted");
        assertThat(exhausted.errorCode()).isEqualTo(JobResumePlanner.ERR_ATTEMPTS_EXHAUSTED);
    }

    @Test
    void theAttemptBoundIsCheckedBeforeAnyLookup() {
        AtomicInteger calls = new AtomicInteger();
        JobResumePlanner.plan(job(5, 1), () -> {
            calls.incrementAndGet();
            return null;
        }, RESUME_RERUN, NOW);
        assertThat(calls.get()).isZero();
    }

    @Test
    void aBoundBelowOneIsTreatedAsOne() {
        Policy tiny = new Policy(true, true, 0, Duration.ofHours(1));
        assertThat(plan(job(1, 0), facts(false, TurnState.NONE, false, false, 0L), tiny).action())
            .isEqualTo(Action.FAIL);
    }

    @Test
    void aJobOlderThanTheQueueTtlIsFailedNotRequeued() {
        Plan p = plan(job(1, 0, false, NOW.minus(Duration.ofHours(2))),
            facts(false, TurnState.NONE, false, false, 0L), RESUME);
        assertThat(p.action()).isEqualTo(Action.FAIL);
        assertThat(p.reason()).isEqualTo("queue-ttl-expired");
    }

    @Test
    void aJobWithoutACreatedAtIsFailedFailSafe() {
        Plan p = plan(job(1, 0, false, null), facts(false, TurnState.NONE, false, false, 0L), RESUME);
        assertThat(p.action()).isEqualTo(Action.FAIL);
    }

    @Test
    void whenRequeueingIsDisabledEveryOrphanFailsAndTheFactsAreNeverRead() {
        AtomicInteger calls = new AtomicInteger();
        Policy noRequeue = new Policy(true, true, 3, Duration.ofHours(1), false);
        Plan p = JobResumePlanner.plan(job(1, 0), () -> {
            calls.incrementAndGet();
            return facts(true, TurnState.NONE, false, false, 0L);
        }, noRequeue, NOW);
        assertThat(p.action()).isEqualTo(Action.FAIL);
        assertThat(p.reason()).isEqualTo("requeue-disabled");
        assertThat(p.errorCode()).isEqualTo(JobResumePlanner.ERR_ORPHANED);
        assertThat(calls.get()).isZero();
    }

    @Test
    void aCancelRequestStillWinsOverDisabledRequeueing() {
        Policy noRequeue = new Policy(true, true, 3, Duration.ofHours(1), false);
        assertThat(plan(job(1, 0, true, NOW.minusSeconds(5)), facts(true, TurnState.NONE, false, false, 0L), noRequeue)
            .action()).isEqualTo(Action.CANCEL);
    }
}
