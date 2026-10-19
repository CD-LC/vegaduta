package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The full transition matrix, plus the two predicates the rest of the system
 * branches on.
 *
 * <p>This is the highest-value test in the foundation despite testing an enum:
 * {@code WorkflowRunStatus}'s javadoc records that the workflow engine shipped
 * its approval state as effectively terminal and had to be corrected once
 * "completed" counts, run lists and polling clients had all already been written
 * against the wrong answer. The assertions here are what stop that correction
 * from being needed a second time - {@link AgentJobStatus#AWAITING_APPROVAL} is
 * non-terminal and occupies no worker, and this holds now, before the deferred
 * approval-parking PR produces a single parked job.
 */
class AgentJobStatusTransitionTest {

    /**
     * The contract's table, transcribed. Written as data rather than as one
     * assertion per legal move so the ILLEGAL moves are checked too: the loop
     * below asserts every pair NOT listed here is rejected, which is the half a
     * hand-written test always forgets.
     */
    private static final Map<AgentJobStatus, Set<AgentJobStatus>> ALLOWED = Map.of(
        AgentJobStatus.QUEUED, EnumSet.of(AgentJobStatus.RUNNING, AgentJobStatus.CANCELLED, AgentJobStatus.FAILED),
        // RUNNING -> QUEUED is the ONE backwards edge, and it exists for exactly
        // one caller: the durability policy's bounded re-queue of an orphan that
        // provably did zero chargeable work. Every other RUNNING edge is
        // unchanged.
        AgentJobStatus.RUNNING, EnumSet.of(
            AgentJobStatus.AWAITING_APPROVAL, AgentJobStatus.QUEUED, AgentJobStatus.SUCCEEDED,
            AgentJobStatus.FAILED, AgentJobStatus.CANCELLED),
        AgentJobStatus.AWAITING_APPROVAL, EnumSet.of(
            AgentJobStatus.RUNNING, AgentJobStatus.FAILED, AgentJobStatus.CANCELLED),
        AgentJobStatus.SUCCEEDED, EnumSet.noneOf(AgentJobStatus.class),
        AgentJobStatus.FAILED, EnumSet.noneOf(AgentJobStatus.class),
        AgentJobStatus.CANCELLED, EnumSet.noneOf(AgentJobStatus.class)
    );

    @Test
    void transitionMatrixMatchesTheContractInBothDirections() {
        for (AgentJobStatus from : AgentJobStatus.values()) {
            Set<AgentJobStatus> legal = ALLOWED.get(from);
            for (AgentJobStatus to : AgentJobStatus.values()) {
                assertEquals(
                    legal.contains(to), from.canTransitionTo(to),
                    from + " -> " + to + " disagrees with the contract's transition table");
            }
        }
    }

    @Test
    void selfTransitionAndNullAreNeverLegal() {
        for (AgentJobStatus status : AgentJobStatus.values()) {
            assertFalse(status.canTransitionTo(status), status + " must not transition to itself");
            assertFalse(status.canTransitionTo(null), status + " must not transition to null");
        }
    }

    @Test
    void awaitingApprovalIsNotTerminalAndHoldsNoWorker() {
        assertFalse(AgentJobStatus.AWAITING_APPROVAL.isTerminal());
        // The distinction that lets a job wait days for a human without ever
        // starving the bounded pool.
        assertFalse(AgentJobStatus.AWAITING_APPROVAL.occupiesWorker());
        assertTrue(AgentJobStatus.RUNNING.occupiesWorker());
        assertFalse(AgentJobStatus.QUEUED.occupiesWorker());
    }

    @Test
    void terminalIsExactlySucceededFailedCancelled() {
        assertTrue(AgentJobStatus.SUCCEEDED.isTerminal());
        assertTrue(AgentJobStatus.FAILED.isTerminal());
        assertTrue(AgentJobStatus.CANCELLED.isTerminal());
        assertFalse(AgentJobStatus.QUEUED.isTerminal());
        assertFalse(AgentJobStatus.RUNNING.isTerminal());
        assertFalse(AgentJobStatus.AWAITING_APPROVAL.isTerminal());
    }

    @Test
    void liveIsTheThreeNonTerminalStates() {
        // Same set the schedule overlap guard and V208's partial unique indexes
        // are defined on; if this ever loses AWAITING_APPROVAL, a parked job
        // stops blocking its schedule and the next tick double-fires.
        assertEquals(
            EnumSet.of(AgentJobStatus.QUEUED, AgentJobStatus.RUNNING, AgentJobStatus.AWAITING_APPROVAL),
            EnumSet.copyOf(AgentJobStatus.live()));
        for (AgentJobStatus status : AgentJobStatus.values()) {
            assertEquals(!status.isTerminal(), AgentJobStatus.live().contains(status));
        }
    }

    @Test
    void aTerminalJobCannotTransitionAgain() {
        Instant now = Instant.now();

        AgentJob succeeded = AgentJobTestSupport.queuedJob(60, 15);
        succeeded.markRunning("host:1", now);
        succeeded.succeed("done", null, null, 0, now);
        assertThrows(AgentJobIllegalTransitionException.class, () -> succeeded.fail("X", "x", now));
        assertThrows(AgentJobIllegalTransitionException.class, () -> succeeded.markCancelled(now));
        assertEquals(AgentJobStatus.SUCCEEDED, succeeded.getStatus());

        AgentJob cancelled = AgentJobTestSupport.queuedJob(60, 15);
        cancelled.markRunning("host:1", now);
        cancelled.markCancelled(now);
        // The specific corruption this guard exists to prevent: a worker
        // finishing normally AFTER a cancel already landed would otherwise
        // report SUCCEEDED for a job an operator was told had stopped.
        assertThrows(
            AgentJobIllegalTransitionException.class,
            () -> cancelled.succeed("late result", null, null, 0, now));
        assertEquals(AgentJobStatus.CANCELLED, cancelled.getStatus());
    }

    @Test
    void aQueuedJobCanExpireStraightToFailed() {
        // Queue-TTL expiry: the requester's frozen authority went stale, so the
        // job is failed rather than run. QUEUED -> FAILED must stay legal.
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        job.fail("QUEUE_TTL_EXPIRED", "waited too long", Instant.now());
        assertEquals(AgentJobStatus.FAILED, job.getStatus());
    }

    @Test
    void parkingReleasesTheWorkerAndResumingTakesAFreshOne() {
        Instant now = Instant.now();
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        job.markRunning("host-a:1", now);

        job.parkForApproval(now, now.plusSeconds(3600));

        assertEquals(AgentJobStatus.AWAITING_APPROVAL, job.getStatus());
        // A parked job belongs to no worker: leaving a stale owner behind would
        // make the orphan sweep's "is replica B still running this?" question
        // unanswerable.
        assertNull(job.getOwnerInstance());
        assertNull(job.getHeartbeatAt());
        assertEquals(1, job.getParkCount());

        job.resumeFromApproval("host-b:2", now.plusSeconds(10));
        assertEquals(AgentJobStatus.RUNNING, job.getStatus());
        assertEquals("host-b:2", job.getOwnerInstance());
        assertNull(job.getParkedAt());
    }

    @Test
    void requeueingAnOrphanClearsEveryFieldThatIdentifiesTheLostAttempt() {
        Instant now = Instant.now();
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        job.markRunning("host-a:1", now);
        assertEquals(1, job.getAttemptCount());

        job.requeue(now);

        assertEquals(AgentJobStatus.QUEUED, job.getStatus());
        assertNull(job.getOwnerInstance());
        assertNull(job.getStartedAt());
        assertNull(job.getDeadlineAt());
        // The subtler of the two clears: a re-queued row that kept its
        // heartbeat_at would keep matching the orphan sweep's
        // "heartbeat_at < cutoff" query while sitting harmlessly QUEUED.
        assertNull(job.getHeartbeatAt());
        // NOT reset - attempt_count is what bounds how many times a job may be
        // recovered, so resetting it here would make the bound unenforceable.
        assertEquals(1, job.getAttemptCount());

        // And it is genuinely runnable again, not a dead end.
        job.markRunning("host-b:2", now);
        assertEquals(AgentJobStatus.RUNNING, job.getStatus());
        assertEquals(2, job.getAttemptCount());
    }

    @Test
    void aTerminalJobCannotBeRequeued() {
        Instant now = Instant.now();
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        job.markRunning("host-a:1", now);
        job.succeed("done", null, null, 0, now);

        assertThrows(AgentJobIllegalTransitionException.class, () -> job.requeue(now));
        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
    }

    @Test
    void bindingToATriggerWritesTheColumnsNothingCouldWriteBefore() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        assertNull(job.getTriggerId());
        assertEquals(0, job.getTriggerDepth());

        java.util.UUID triggerId = java.util.UUID.randomUUID();
        job.bindToTrigger(triggerId, 0);

        assertEquals(triggerId, job.getTriggerId());
        // Recorded as 0 and documented as RESERVED: there is no reliable lineage
        // signal today, so this must not be mistaken for working loop detection.
        assertEquals(0, job.getTriggerDepth());
    }

    @Test
    void turnsUsedIsDurableAndMonotonic() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        assertEquals(0, job.getTurnsUsed());
        job.incrementTurn();
        job.incrementTurn();
        assertEquals(2, job.getTurnsUsed());
    }

    @Test
    void rolesRoundTripAndAbsentRolesMeanNoAuthorityNotAllAuthority() {
        AgentJob job = AgentJob.queued(
            java.util.UUID.randomUUID(), "t", java.util.UUID.randomUUID(), "goal",
            "u", "u", null, AgentJobOrigin.API, null, 60, 1, 15, null);
        assertTrue(job.getRequestedByRoleSet().isEmpty());

        AgentJob withRoles = AgentJob.queued(
            java.util.UUID.randomUUID(), "t", java.util.UUID.randomUUID(), "goal",
            "u", "u", Set.of("ROLE_user", "ROLE_tenant-admin"),
            AgentJobOrigin.API, null, 60, 1, 15, null);
        assertEquals(Set.of("ROLE_user", "ROLE_tenant-admin"), withRoles.getRequestedByRoleSet());
    }
}
