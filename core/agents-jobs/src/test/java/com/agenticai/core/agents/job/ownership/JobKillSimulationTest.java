package com.agenticai.core.agents.job.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.job.ownership.JobLeaseAdopter.Outcome;
import com.agenticai.core.agents.job.ownership.JobLeasePolicy.RenewOutcome;
import com.agenticai.core.agents.job.ownership.JobLeaseStore.LeasedJob;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.Facts;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.Policy;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.TurnState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Deterministic two-replica kill simulation. Replicas A and B share one in-memory {@code agent_job} table
 * ({@link InMemoryJobLeaseStore}, same compare-and-set semantics as the SQL) and a virtual clock. The code
 * under test is the REAL {@link JobLeaseAdopter} + {@link JobResumePlanner} + {@link JobLeasePolicy}; what is
 * modelled is the worker around them (claim, step journal, replay of completed steps), which in production is
 * {@code AgentJobExecution} + {@code StepJournal} + {@code EffectJournal}. The model's replay rules mirror
 * those classes' documented semantics (COMPLETED steps are replayed from the journal; a DURABLE step that is
 * STARTED without COMPLETED must never be fired again), so a green run proves the decisions, not the
 * journal implementation itself.
 */
class JobKillSimulationTest {

    private static final int LEASE = 60;
    private static final Instant T0 = Instant.parse("2026-10-04T12:00:00Z");
    private static final String CRASH_BEFORE_FINISH = "#finish";

    private static final Policy RESUME_RERUN = new Policy(true, true, 3, Duration.ofHours(1));
    private static final Policy RESUME_NO_RERUN = new Policy(true, false, 3, Duration.ofHours(1));
    private static final Policy ZERO_WORK_ONLY = new Policy(false, false, 3, Duration.ofHours(1));

    // ------------------------------------------------------------------ simulation scaffolding

    private final AtomicReference<Instant> clock = new AtomicReference<>(T0);
    private final InMemoryJobLeaseStore store = new InMemoryJobLeaseStore();
    private final SimJournal journal = new SimJournal();
    /** How many times each step's side effect was actually performed, across both replicas. */
    private final Map<String, Integer> fired = new HashMap<>();

    private Instant now() {
        return clock.get();
    }

    private void advance(long seconds) {
        clock.set(now().plusSeconds(seconds));
    }

    private record Step(String name, boolean durable) {
        static Step read(String name) {
            return new Step(name, false);
        }

        static Step durable(String name) {
            return new Step(name, true);
        }
    }

    private enum Run { SUCCEEDED, CRASHED, NOT_OWNER }

    private enum StepState { STARTED, COMPLETED }

    /** The journal as the planner sees it: one turn per job plus named tool steps. */
    private static final class SimJournal {
        private final Map<UUID, TurnState> turns = new HashMap<>();
        private final Map<UUID, Map<String, StepState>> steps = new HashMap<>();
        private final Map<UUID, Map<String, Boolean>> durableFlags = new HashMap<>();

        TurnState turn(UUID job) {
            return turns.getOrDefault(job, TurnState.NONE);
        }

        void beginTurn(UUID job) {
            turns.putIfAbsent(job, TurnState.STARTED);
        }

        void completeTurn(UUID job) {
            turns.put(job, TurnState.COMPLETED_WITH_RESULT);
        }

        StepState step(UUID job, String name) {
            return steps.getOrDefault(job, Map.of()).get(name);
        }

        void begin(UUID job, String name, boolean durable) {
            steps.computeIfAbsent(job, k -> new LinkedHashMap<>()).put(name, StepState.STARTED);
            durableFlags.computeIfAbsent(job, k -> new HashMap<>()).put(name, durable);
        }

        void complete(UUID job, String name) {
            steps.get(job).put(name, StepState.COMPLETED);
        }

        Facts facts(LeasedJob job) {
            boolean incompleteDurable = false;
            for (Map.Entry<String, StepState> e : steps.getOrDefault(job.id(), Map.of()).entrySet()) {
                if (e.getValue() == StepState.STARTED && durableFlags.get(job.id()).get(e.getKey())) {
                    incompleteDurable = true;
                }
            }
            TurnState last = job.turnsUsed() > 0 ? turn(job.id()) : TurnState.NONE;
            long logged = steps.getOrDefault(job.id(), Map.of()).size();
            return new Facts(true, last, true, incompleteDurable, logged);
        }
    }

    private final class Replica {
        final String id;
        final List<UUID> dispatched = new ArrayList<>();
        private final JobLeaseAdopter adopter;

        Replica(String id, Policy policy) {
            this.id = id;
            this.adopter = new JobLeaseAdopter(
                store, journal::facts, () -> policy, () -> LEASE, jobId -> false, job -> dispatched.add(job.id()));
        }

        boolean claim(UUID jobId) {
            return store.claim(jobId, id, now());
        }

        boolean renew(UUID jobId) {
            return store.renew(jobId, id, now());
        }

        /** One lease-sweep tick, shaped exactly like AgentJobLeaseSweep#sweepExclusively. */
        List<Outcome> sweep() {
            Instant at = now();
            List<Outcome> outcomes = new ArrayList<>();
            for (LeasedJob job : store.findExpired(JobLeasePolicy.expiryCutoff(at, LEASE), 50)) {
                outcomes.add(adopter.resolve(job, at));
            }
            return outcomes;
        }

        /** Model of AgentJobExecution for one claimed job: replay completed steps, never refire durable ones. */
        Run run(UUID jobId, List<Step> steps, String crashDuring) {
            if (journal.turn(jobId) == TurnState.COMPLETED_WITH_RESULT) {
                // journal-turn-completed: reuse the recorded result, no step runs at all
                return store.succeed(jobId, id) ? Run.SUCCEEDED : Run.NOT_OWNER;
            }
            if (journal.turn(jobId) == TurnState.NONE) {
                store.incrementTurnsUsed(jobId);
            }
            journal.beginTurn(jobId);
            for (Step s : steps) {
                StepState state = journal.step(jobId, s.name());
                if (state == StepState.COMPLETED) {
                    continue; // replayed from the journal, NOT re-executed
                }
                if (s.durable() && state == StepState.STARTED) {
                    throw new AssertionError("durable step '" + s.name() + "' was blindly re-fired");
                }
                journal.begin(jobId, s.name(), s.durable());
                fired.merge(s.name(), 1, Integer::sum); // the side effect happens
                if (s.name().equals(crashDuring)) {
                    return Run.CRASHED; // replica dies before recording completion
                }
                journal.complete(jobId, s.name());
            }
            journal.completeTurn(jobId);
            if (CRASH_BEFORE_FINISH.equals(crashDuring)) {
                return Run.CRASHED;
            }
            return store.succeed(jobId, id) ? Run.SUCCEEDED : Run.NOT_OWNER;
        }
    }

    private int firedCount(String step) {
        return fired.getOrDefault(step, 0);
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    void killedMidDurableStepIsNeverRefiredAndIsHandedToAHuman() {
        Replica a = new Replica("A", RESUME_RERUN);
        Replica b = new Replica("B", RESUME_RERUN);
        InMemoryJobLeaseStore.Row row = store.enqueue(now());
        List<Step> steps = List.of(Step.read("s1"), Step.durable("s2"), Step.durable("s3"));

        assertThat(a.claim(row.id)).isTrue();
        assertThat(a.run(row.id, steps, "s3")).isEqualTo(Run.CRASHED);
        assertThat(firedCount("s1")).isEqualTo(1);
        assertThat(firedCount("s2")).isEqualTo(1);
        assertThat(firedCount("s3")).isEqualTo(1);

        advance(10);
        assertThat(a.renew(row.id)).isTrue(); // last sign of life
        advance(60);
        // 60s after the last heartbeat: still inside lease + skew grace, nobody may adopt yet
        assertThat(b.sweep()).isEmpty();

        advance(6);
        List<Outcome> outcomes = b.sweep();
        assertThat(outcomes).containsExactly(Outcome.NEEDS_REVIEW);
        assertThat(row.status).isEqualTo(AgentJobStatus.FAILED);
        assertThat(row.errorCode).isEqualTo(JobResumePlanner.ERR_NEEDS_REVIEW);
        assertThat(row.errorMessage).contains("write/send");
        assertThat(b.dispatched).isEmpty();
        // nothing was executed a second time, in particular not the durable step that never recorded completion
        assertThat(firedCount("s1")).isEqualTo(1);
        assertThat(firedCount("s2")).isEqualTo(1);
        assertThat(firedCount("s3")).isEqualTo(1);
    }

    @Test
    void killedMidReadOnlyStepIsResumedByTheOtherReplicaWithoutRerunningCompletedSteps() {
        Replica a = new Replica("A", RESUME_RERUN);
        Replica b = new Replica("B", RESUME_RERUN);
        InMemoryJobLeaseStore.Row row = store.enqueue(now());
        List<Step> steps = List.of(Step.read("s1"), Step.durable("s2"), Step.read("s3"));

        assertThat(a.claim(row.id)).isTrue();
        assertThat(a.run(row.id, steps, "s3")).isEqualTo(Run.CRASHED);

        advance(71);
        assertThat(b.sweep()).containsExactly(Outcome.REQUEUED);
        assertThat(b.dispatched).containsExactly(row.id);
        assertThat(row.status).isEqualTo(AgentJobStatus.QUEUED);
        assertThat(row.owner).isNull();
        assertThat(row.attempt).isEqualTo(1); // claims are never reset: it is the bound
        assertThat(row.resumeReason).contains("journal-rerun-incomplete-turn").contains("'A'");

        assertThat(b.claim(row.id)).isTrue();
        assertThat(row.attempt).isEqualTo(2);
        assertThat(b.run(row.id, steps, null)).isEqualTo(Run.SUCCEEDED);

        assertThat(row.status).isEqualTo(AgentJobStatus.SUCCEEDED);
        assertThat(row.turnsUsed).isEqualTo(1); // the rerun did not start a second turn
        assertThat(firedCount("s1")).isEqualTo(1); // completed steps were replayed, not re-executed
        assertThat(firedCount("s2")).isEqualTo(1);
        assertThat(firedCount("s3")).isEqualTo(2); // the read-only step that was in flight ran again
    }

    @Test
    void aTurnThatCompletedBeforeTheCrashIsReusedWithoutRunningAnything() {
        Replica a = new Replica("A", RESUME_NO_RERUN);
        Replica b = new Replica("B", RESUME_NO_RERUN);
        InMemoryJobLeaseStore.Row row = store.enqueue(now());
        List<Step> steps = List.of(Step.read("s1"), Step.durable("s2"));

        assertThat(a.claim(row.id)).isTrue();
        assertThat(a.run(row.id, steps, CRASH_BEFORE_FINISH)).isEqualTo(Run.CRASHED);

        advance(71);
        assertThat(b.sweep()).containsExactly(Outcome.REQUEUED);
        assertThat(row.resumeReason).contains("journal-turn-completed");
        assertThat(b.claim(row.id)).isTrue();
        assertThat(b.run(row.id, steps, null)).isEqualTo(Run.SUCCEEDED);

        assertThat(row.status).isEqualTo(AgentJobStatus.SUCCEEDED);
        assertThat(firedCount("s1")).isEqualTo(1);
        assertThat(firedCount("s2")).isEqualTo(1);
    }

    @Test
    void aPoisonJobThatKillsEveryWorkerIsFailedAfterTheBoundedNumberOfAttempts() {
        Replica a = new Replica("A", RESUME_RERUN);
        Replica b = new Replica("B", RESUME_RERUN);
        InMemoryJobLeaseStore.Row row = store.enqueue(now());
        List<Step> steps = List.of(Step.read("s1"));
        Replica[] order = {a, b, a};

        for (int attempt = 1; attempt <= 3; attempt++) {
            Replica worker = order[attempt - 1];
            Replica other = attempt % 2 == 1 ? b : a;
            assertThat(worker.claim(row.id)).as("claim %d", attempt).isTrue();
            assertThat(worker.run(row.id, steps, "s1")).isEqualTo(Run.CRASHED);
            advance(71);
            List<Outcome> outcomes = other.sweep();
            if (attempt < 3) {
                assertThat(outcomes).containsExactly(Outcome.REQUEUED);
            } else {
                assertThat(outcomes).containsExactly(Outcome.FAILED);
            }
        }

        assertThat(row.status).isEqualTo(AgentJobStatus.FAILED);
        assertThat(row.errorCode).isEqualTo(JobResumePlanner.ERR_ATTEMPTS_EXHAUSTED);
        assertThat(row.attempt).isEqualTo(3);
        assertThat(firedCount("s1")).isEqualTo(3);
        // and it stays failed: a further sweep finds nothing to do
        assertThat(a.sweep()).isEmpty();
        assertThat(b.sweep()).isEmpty();
    }

    @Test
    void twoSweepersRacingOnOneExpiredLeaseProduceExactlyOneAdoption() {
        Replica a = new Replica("A", RESUME_RERUN);
        Replica b = new Replica("B", RESUME_RERUN);
        Replica c = new Replica("C", RESUME_RERUN);
        InMemoryJobLeaseStore.Row row = store.enqueue(now());
        assertThat(a.claim(row.id)).isTrue(); // A dies right after claiming: zero work
        advance(71);

        // both sweepers read the expired list BEFORE either acts, exactly as concurrent sweeps would
        Instant at = now();
        Instant cutoff = JobLeasePolicy.expiryCutoff(at, LEASE);
        List<LeasedJob> seenByB = store.findExpired(cutoff, 50);
        List<LeasedJob> seenByC = store.findExpired(cutoff, 50);
        assertThat(seenByB).hasSize(1);
        assertThat(seenByC).hasSize(1);

        JobLeaseAdopter adopterB = new JobLeaseAdopter(store, journal::facts, () -> RESUME_RERUN, () -> LEASE,
            id -> false, job -> b.dispatched.add(job.id()));
        JobLeaseAdopter adopterC = new JobLeaseAdopter(store, journal::facts, () -> RESUME_RERUN, () -> LEASE,
            id -> false, job -> c.dispatched.add(job.id()));
        Outcome first = adopterB.resolve(seenByB.get(0), at);
        Outcome second = adopterC.resolve(seenByC.get(0), at);

        assertThat(first).isEqualTo(Outcome.REQUEUED);
        assertThat(second).isEqualTo(Outcome.LOST_RACE);
        assertThat(b.dispatched).hasSize(1);
        assertThat(c.dispatched).isEmpty();

        // the claim is the second compare-and-set: only one replica runs it
        boolean claimedByB = b.claim(row.id);
        boolean claimedByC = c.claim(row.id);
        assertThat(claimedByB ^ claimedByC).isTrue();
        assertThat(row.attempt).isEqualTo(2);

        // a late, stale resolve of the OLD snapshot (attempt 1) must not touch the now-live job (ABA guard)
        advance(1);
        assertThat(adopterC.resolve(seenByC.get(0), now())).isEqualTo(Outcome.LOST_RACE);
        assertThat(row.status).isEqualTo(AgentJobStatus.RUNNING);
        assertThat(row.attempt).isEqualTo(2);
    }

    @Test
    void aGracefullyReleasedJobIsAdoptedImmediatelyWithoutWaitingForTheLeaseToExpire() {
        Replica a = new Replica("A", RESUME_RERUN);
        Replica b = new Replica("B", RESUME_RERUN);
        InMemoryJobLeaseStore.Row row = store.enqueue(now());
        assertThat(a.claim(row.id)).isTrue();
        advance(5);
        assertThat(a.renew(row.id)).isTrue();
        assertThat(b.sweep()).isEmpty(); // healthy lease: nothing to adopt

        // SIGTERM on A: only the owner can release
        assertThat(store.release(row.id, "B", JobLeasePolicy.RELEASED_AT)).isFalse();
        assertThat(store.release(row.id, "A", JobLeasePolicy.RELEASED_AT)).isTrue();

        assertThat(b.sweep()).containsExactly(Outcome.REQUEUED); // same instant, no 60s wait
        assertThat(b.claim(row.id)).isTrue();
        assertThat(b.run(row.id, List.of(Step.read("s1")), null)).isEqualTo(Run.SUCCEEDED);
    }

    @Test
    void aZombieWorkerThatWakesAfterBeingAdoptedIsFencedAndCannotOverwriteTheAdoptersRow() {
        Replica a = new Replica("A", RESUME_RERUN);
        Replica b = new Replica("B", RESUME_RERUN);
        InMemoryJobLeaseStore.Row row = store.enqueue(now());
        assertThat(a.claim(row.id)).isTrue();

        advance(71); // A is paused (stop-the-world GC, frozen VM) for longer than the lease
        assertThat(b.sweep()).containsExactly(Outcome.REQUEUED);
        assertThat(b.claim(row.id)).isTrue();

        // A wakes up: its renewal matches nothing and the row proves another replica owns it
        assertThat(a.renew(row.id)).isFalse();
        JobLeaseStore.LeaseView view = store.view(row.id).orElseThrow();
        assertThat(JobLeasePolicy.classifyFailedRenewal(view.status(), view.owner(), "A")).isEqualTo(RenewOutcome.LOST);
        // and even if its worker finishes anyway, the terminal write is refused
        assertThat(store.succeed(row.id, "A")).isFalse();
        assertThat(row.status).isEqualTo(AgentJobStatus.RUNNING);
        assertThat(row.owner).isEqualTo("B");
    }

    @Test
    void aCancelRequestedWhileTheOwnerIsDeadIsHonouredNotFailed() {
        Replica a = new Replica("A", RESUME_RERUN);
        Replica b = new Replica("B", RESUME_RERUN);
        InMemoryJobLeaseStore.Row row = store.enqueue(now());
        assertThat(a.claim(row.id)).isTrue();
        store.requestCancel(row.id);

        advance(71);
        assertThat(b.sweep()).containsExactly(Outcome.CANCELLED);
        assertThat(row.status).isEqualTo(AgentJobStatus.CANCELLED);
    }

    @Test
    void withTheResumeFlagsOffOnlyZeroWorkOrphansAreRequeuedExactlyAsToday() {
        Replica a = new Replica("A", ZERO_WORK_ONLY);
        Replica b = new Replica("B", ZERO_WORK_ONLY);
        List<Step> steps = List.of(Step.read("s1"), Step.durable("s2"));

        InMemoryJobLeaseStore.Row zeroWork = store.enqueue(now());
        assertThat(a.claim(zeroWork.id)).isTrue(); // died before any turn

        InMemoryJobLeaseStore.Row midTurn = store.enqueue(now());
        assertThat(a.claim(midTurn.id)).isTrue();
        assertThat(a.run(midTurn.id, steps, "s2")).isEqualTo(Run.CRASHED);

        advance(71);
        List<Outcome> outcomes = b.sweep();
        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.REQUEUED, Outcome.FAILED);
        assertThat(zeroWork.status).isEqualTo(AgentJobStatus.QUEUED);
        assertThat(midTurn.status).isEqualTo(AgentJobStatus.FAILED);
        assertThat(midTurn.errorCode).isEqualTo(JobResumePlanner.ERR_ORPHANED);
        assertThat(firedCount("s2")).isEqualTo(1);
    }

    @Test
    void aJobThisReplicaIsGenuinelyRunningIsNeverAdoptedByItself() {
        InMemoryJobLeaseStore.Row row = store.enqueue(now());
        assertThat(store.claim(row.id, "A", now())).isTrue();
        advance(200); // heartbeat writer is behind (GC / slow DB) but the worker is alive
        JobLeaseAdopter self = new JobLeaseAdopter(store, journal::facts, () -> RESUME_RERUN, () -> LEASE,
            id -> true, job -> { });
        for (LeasedJob job : store.findExpired(JobLeasePolicy.expiryCutoff(now(), LEASE), 50)) {
            assertThat(self.resolve(job, now())).isEqualTo(Outcome.ACTIVE_HERE);
        }
        assertThat(row.status).isEqualTo(AgentJobStatus.RUNNING);
        assertThat(row.owner).isEqualTo("A");
    }

    @Test
    void aFreshLeaseIsNeverAdoptedEvenIfAnotherReplicaSweepsRepeatedly() {
        Replica a = new Replica("A", RESUME_RERUN);
        Replica b = new Replica("B", RESUME_RERUN);
        InMemoryJobLeaseStore.Row row = store.enqueue(now());
        assertThat(a.claim(row.id)).isTrue();
        for (int i = 0; i < 10; i++) {
            advance(15);
            assertThat(a.renew(row.id)).isTrue();
            assertThat(b.sweep()).isEmpty();
        }
        assertThat(row.status).isEqualTo(AgentJobStatus.RUNNING);
        assertThat(row.owner).isEqualTo("A");
    }
}
