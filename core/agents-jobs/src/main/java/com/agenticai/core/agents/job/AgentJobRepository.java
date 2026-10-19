package com.agenticai.core.agents.job;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for {@link AgentJob}.
 *
 * <p><b>Claiming is a conditional UPDATE, not {@code SELECT ... FOR UPDATE SKIP
 * LOCKED}.</b> Three reasons, in order of weight: (1) this repo has a standing,
 * documented dependency on PgBouncer running in SESSION pooling mode - under
 * transaction pooling, row locks held across statements become a correctness
 * hazard, and a queue built on {@code SKIP LOCKED} would silently make that
 * config choice load-bearing in a new place; (2) there is zero {@code SKIP
 * LOCKED} precedent anywhere in this codebase, while {@code
 * WorkflowRunRepository#compareAndSetStatus} already ships exactly the
 * compare-and-set idiom used here, so this is the shape reviewers already know
 * how to reason about; (3) with a bounded pool of four workers there is no
 * throughput case for the more exotic mechanism. Losers of a claim race simply
 * observe a rowcount of {@code 0} and move on to the next row.
 *
 * <p><b>Why several targeted single-column writes exist instead of {@code
 * save(entity)}.</b> The worker holds a long-lived detached {@link AgentJob}
 * across an LLM call that can run for minutes. A full {@code save} of that stale
 * copy would clobber whatever an operator concurrently wrote - most importantly
 * {@code cancel_requested}, which is the entire cross-replica cancel signal.
 * {@link #touchHeartbeat}, {@link #updateProgressNote} and {@link #requestCancel}
 * therefore write only their own columns.
 *
 * <p>{@code @Transactional} on the {@code @Modifying} methods, unusual on a
 * repository and deliberate for the same reason {@code WorkflowRunRepository}
 * gives: JPQL updates need a transaction, and the callers here (a pool worker
 * mid-LLM-call, a {@code @Scheduled} sweep) run outside one on purpose so that
 * nothing holds a Hikari connection for the duration of a job.
 */
public interface AgentJobRepository extends JpaRepository<AgentJob, UUID> {

    /** {@code agent_job.resume_reason} is TEXT, but an unbounded log line does not belong in a status column. */
    int MAX_RESUME_REASON_CHARS = 500;

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * The ONLY single-job finder any request-scoped path may use - never bare
     * {@code findById}. Every per-job endpoint is shaped {@code
     * /api/agents/{agentId}/jobs/{jobId}} and enforces access against the PATH's
     * {@code agentId}; with {@code findById} the two ids would never be tied
     * together, so a caller could pair an agent they own with somebody else's
     * job id and read that job's goal, result and error. Same defect, same fix,
     * and the same "make it a query so a future endpoint cannot forget it"
     * reasoning as {@code WorkflowRunRepository#findByIdAndWorkflowId}.
     */
    Optional<AgentJob> findByIdAndAgentId(UUID id, UUID agentId);

    Page<AgentJob> findByAgentIdOrderByCreatedAtDesc(UUID agentId, Pageable pageable);

    /** Newest-first jobs of several agents whose goal starts with a prefix (research history); windowed by the pageable, no count query. */
    List<AgentJob> findByAgentIdInAndGoalStartingWithOrderByCreatedAtDesc(
        Collection<UUID> agentIds, String goalPrefix, Pageable pageable);

    Page<AgentJob> findByStatusInOrderByCreatedAtDesc(Collection<AgentJobStatus> statuses, Pageable pageable);

    Page<AgentJob> findByAgentIdAndStatusInOrderByCreatedAtDesc(
        UUID agentId, Collection<AgentJobStatus> statuses, Pageable pageable);

    Page<AgentJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * The dispatcher's poll. Oldest first (FIFO) and hard-capped at 50 so one
     * tenant with a thousand queued jobs cannot make a single tick allocate an
     * unbounded list; the next tick picks up where this one left off.
     */
    List<AgentJob> findTop50ByStatusOrderByCreatedAtAsc(AgentJobStatus status);

    /** Wall-clock deadline sweep - see {@link AgentJobRunner#enforceDeadlines}. */
    List<AgentJob> findByStatusAndDeadlineAtBefore(AgentJobStatus status, Instant cutoff);

    /** Queue-TTL sweep: QUEUED far longer than {@code app.agent-jobs.queue-ttl} means stale frozen authority. */
    List<AgentJob> findByStatusAndCreatedAtBefore(AgentJobStatus status, Instant cutoff);

    /**
     * The approval sweep's read (APPROVAL-PARKING PR): parked jobs whose
     * {@code approval_deadline_at} has passed.
     *
     * <p>Added because {@code approval_deadline_at} has been WRITTEN since V207
     * and read by absolutely nothing - which is the difference between "an
     * ignored approval times out with a clear status" and "a parked job waits
     * forever holding its schedule's overlap guard shut". Indexed by V211.
     */
    List<AgentJob> findByStatusAndApprovalDeadlineAtBefore(AgentJobStatus status, Instant cutoff);

    /**
     * Orphan sweep. Not in the original contract's finder list; added because
     * "RUNNING with a heartbeat that stopped" is a genuinely distinct condition
     * from "RUNNING past its deadline" - a replica that was SIGKILLed leaves a
     * row that will not hit its deadline for another 59 minutes, and until then
     * it holds the schedule overlap guard shut.
     */
    List<AgentJob> findByStatusAndHeartbeatAtBefore(AgentJobStatus status, Instant cutoff);

    /**
     * The schedule overlap guard's read (SCHEDULES PR). Queried BY
     * {@code schedule_id}, never by following {@code agent_schedule.last_job_id}:
     * that pointer goes stale the instant someone starts a manual run for the
     * same agent, and a stale pointer here is a MISSED overlap - precisely the
     * bug the guard exists to prevent.
     */
    Optional<AgentJob> findFirstByScheduleIdAndStatusInOrderByCreatedAtDesc(
        UUID scheduleId, Collection<AgentJobStatus> statuses);

    long countByAgentIdAndStatusIn(UUID agentId, Collection<AgentJobStatus> statuses);

    long countByAgentId(UUID agentId);

    long countByStatusIn(Collection<AgentJobStatus> statuses);

    long countByTenantIdAndStatusIn(String tenantId, Collection<AgentJobStatus> statuses);

    /**
     * The ambient digest's "what ran while you were away" read: recent jobs in
     * this tenant schema, newest first, hard-capped - the digest aggregates
     * statuses in memory from this bounded page rather than running one count
     * query per status.
     */
    List<AgentJob> findTop50ByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(Instant since);

    // ------------------------------------------------------------------ guarded writes

    /**
     * QUEUED -&gt; RUNNING, atomically. Exactly one caller across every replica
     * sees a rowcount of {@code 1}; everyone else sees {@code 0} and must treat
     * the job as somebody else's.
     *
     * <p>{@code deadlineAt} is stamped HERE, at claim, rather than at enqueue,
     * so a job that waited in a backed-up queue still gets its full execution
     * budget instead of being born already half-expired.
     */
    default int claim(UUID id, String ownerInstance, Instant now, Instant deadlineAt) {
        return claimInternal(id, ownerInstance, now, deadlineAt, AgentJobStatus.QUEUED, AgentJobStatus.RUNNING);
    }

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.status = :to, j.ownerInstance = :ownerInstance, j.startedAt = :now, "
        + "j.heartbeatAt = :now, j.deadlineAt = :deadlineAt, j.attemptCount = j.attemptCount + 1 "
        + "where j.id = :id and j.status = :from")
    int claimInternal(
        @Param("id") UUID id,
        @Param("ownerInstance") String ownerInstance,
        @Param("now") Instant now,
        @Param("deadlineAt") Instant deadlineAt,
        @Param("from") AgentJobStatus from,
        @Param("to") AgentJobStatus to
    );

    /**
     * The fast half of cancel: a job nobody has claimed yet can be cancelled
     * outright, with no worker to interrupt and no cooperative checkpoint to
     * wait for. Guarded on QUEUED so it can never race a worker that claimed
     * the job a microsecond earlier - that case falls through to {@link
     * #requestCancel}.
     */
    default int cancelIfStillQueued(UUID id, String by, Instant now) {
        return cancelIfStillQueuedInternal(id, by, now, AgentJobStatus.QUEUED, AgentJobStatus.CANCELLED);
    }

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.status = :to, j.cancelRequested = true, j.cancelRequestedBy = :by, "
        + "j.cancelRequestedAt = :now, j.finishedAt = :now where j.id = :id and j.status = :from")
    int cancelIfStillQueuedInternal(
        @Param("id") UUID id,
        @Param("by") String by,
        @Param("now") Instant now,
        @Param("from") AgentJobStatus from,
        @Param("to") AgentJobStatus to
    );

    /**
     * The cross-replica cancel SIGNAL. Writes ONLY the flag columns and never
     * {@code status}, so it can never race the owning worker's own status write -
     * which is what keeps this whole table free of optimistic locking. The
     * owning worker observes the flag at its next heartbeat tick and at its own
     * cooperative checkpoints; if the owner is a different replica, this durable
     * flag is the entire mechanism, and honestly so: there is no cross-replica
     * interrupt without a message bus, and this workstream adds no bus.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.cancelRequested = true, j.cancelRequestedBy = :by, j.cancelRequestedAt = :now "
        + "where j.id = :id and j.cancelRequested = false")
    int requestCancel(@Param("id") UUID id, @Param("by") String by, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.heartbeatAt = :now where j.id = :id")
    int touchHeartbeat(@Param("id") UUID id, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.progressNote = :note, j.progressUpdatedAt = :now where j.id = :id")
    int updateProgressNote(@Param("id") UUID id, @Param("note") String note, @Param("now") Instant now);

    /**
     * Turn accounting, as a targeted increment rather than a {@code save} of the
     * worker's loaded copy. Saving that copy at a turn boundary would write back
     * every column as it looked BEFORE the turn - including {@code
     * cancel_requested}, silently undoing a cancel an operator requested while
     * the turn was running, so the very next cooperative checkpoint would read
     * {@code false} and the job would carry on. Incrementing in the database also
     * makes the counter correct if two paths ever touch it at once.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.turnsUsed = j.turnsUsed + 1 where j.id = :id")
    int incrementTurnsUsed(@Param("id") UUID id);

    /**
     * Reads the cancel flag alone. A projection rather than {@code findById}
     * because this runs on the heartbeat tick for every locally-active job and
     * must not drag a full entity (goal, result, trace) through the persistence
     * context every few seconds. Empty when the row is gone.
     */
    @Query("select j.cancelRequested from AgentJob j where j.id = :id")
    Optional<Boolean> isCancelRequested(@Param("id") UUID id);

    /**
     * Reads the status column alone, for the same reason as {@link
     * #isCancelRequested}: the heartbeat tick uses it to notice that ANOTHER
     * replica's sweep already terminalised a job this replica is still running.
     * Empty when the row is gone.
     */
    @Query("select j.status from AgentJob j where j.id = :id")
    Optional<AgentJobStatus> findStatusById(@Param("id") UUID id);

    /**
     * Generic guarded status move - the seam the deferred approval-parking PR
     * compares-and-sets through, kept here so it does not have to add its own.
     */
    default int compareAndSetStatus(UUID id, AgentJobStatus from, AgentJobStatus to, Instant now) {
        // `now` is part of the agreed cross-PR signature but is deliberately not
        // bound into the statement: a bare status move writes no timestamp of
        // its own. The transitions that DO stamp a time (finish, park, claim)
        // each have their own dedicated statement above, so this one stays a
        // pure guard and cannot accidentally overwrite finishedAt/parkedAt.
        return compareAndSetStatusInternal(id, from, to);
    }

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.status = :to where j.id = :id and j.status = :from")
    int compareAndSetStatusInternal(
        @Param("id") UUID id,
        @Param("from") AgentJobStatus from,
        @Param("to") AgentJobStatus to
    );

    /**
     * How the out-of-band sweeps terminate a job they did not run.
     *
     * <p>Guarded on RUNNING and issued as one statement precisely because the
     * owning worker may be finishing normally at this exact moment: whichever
     * of the two writes lands first wins, the other sees rowcount 0, and the
     * row never ends up half-written by both. Doing this as a load-mutate-save
     * through the entity would reintroduce the last-writer-wins window the rest
     * of this design is built to avoid.
     */
    default int failIfStillRunning(UUID id, String errorCode, String errorMessage, Instant now) {
        return failIfStillRunningInternal(id, errorCode, errorMessage, now, AgentJobStatus.RUNNING, AgentJobStatus.FAILED);
    }

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.status = :to, j.errorCode = :errorCode, j.errorMessage = :errorMessage, "
        + "j.finishedAt = :now, j.ownerInstance = null where j.id = :id and j.status = :from")
    int failIfStillRunningInternal(
        @Param("id") UUID id,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("now") Instant now,
        @Param("from") AgentJobStatus from,
        @Param("to") AgentJobStatus to
    );

    // ------------------------------------------------- approval parking / recovery

    /**
     * RUNNING -&gt; AWAITING_APPROVAL, the durable park.
     *
     * <p><b>Not {@link #compareAndSetStatus}.</b> That statement writes {@code
     * status} and nothing else, by design - using it here would leave a stale
     * {@code owner_instance} on a job that belongs to no worker (making the
     * orphan sweep's "is another replica still running this?" question
     * unanswerable) and would never stamp {@code approval_deadline_at}, so the
     * approval sweep would have nothing to time out against.
     *
     * <p>{@code heartbeat_at} is NULLed for the same reason: a parked row must
     * stop looking like a live one to {@code findByStatusAndHeartbeatAtBefore}.
     * {@code park_count} is incremented in the database so it stays correct even
     * if two paths ever touch it, and it is what bounds how many times one job
     * may park.
     */
    default int parkForApproval(UUID id, Instant now, Instant approvalDeadlineAt, String reason) {
        return parkForApprovalInternal(
            id, now, approvalDeadlineAt, truncate(reason, MAX_RESUME_REASON_CHARS),
            AgentJobStatus.RUNNING, AgentJobStatus.AWAITING_APPROVAL);
    }

    /**
     * {@code resume_reason} is written HERE, at the park, and not at the resume.
     * The column has existed since V207 and had no writer at all until this
     * statement: the reason a job stopped is computed by the park detector and
     * was previously only logged, so an operator reading the API saw a job
     * sitting in AWAITING_APPROVAL (or FAILED/APPROVAL_TIMEOUT hours later) with
     * no recorded explanation anywhere except a log line they would have to go
     * and find. Writing it at the park is also the only moment the reason is
     * known - by the time a human decides, the gate that caused it may be one of
     * several.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.status = :to, j.parkedAt = :now, j.approvalDeadlineAt = :approvalDeadlineAt, "
        + "j.parkCount = j.parkCount + 1, j.resumeReason = :reason, j.ownerInstance = null, j.heartbeatAt = null "
        + "where j.id = :id and j.status = :from")
    int parkForApprovalInternal(
        @Param("id") UUID id,
        @Param("now") Instant now,
        @Param("approvalDeadlineAt") Instant approvalDeadlineAt,
        @Param("reason") String reason,
        @Param("from") AgentJobStatus from,
        @Param("to") AgentJobStatus to
    );

    /**
     * AWAITING_APPROVAL -&gt; RUNNING: the claim a resuming worker makes, the
     * mirror of {@link #claim} for a parked job.
     *
     * <p><b>Both timestamps are mandatory and each has its own failure mode.</b>
     * A resume that forgets {@code deadline_at} is killed by the deadline sweep
     * within 15 seconds (the row still carries the deadline from before the
     * park, long since passed). A resume that forgets {@code heartbeat_at} leaks
     * forever instead: the park NULLed it, and {@code heartbeat_at < :cutoff}
     * never matches NULL in SQL, so the orphan sweep would never see the row
     * again either. {@code deadlineAt} is therefore computed FRESH by the caller,
     * never reused.
     *
     * <p>{@code started_at} is deliberately left alone - it records when the job
     * first began, which is what an operator reading "how long has this taken?"
     * means. {@code attempt_count} increments because a resume IS another claim.
     */
    default int resumeClaim(UUID id, String ownerInstance, Instant now, Instant deadlineAt) {
        return resumeClaimInternal(
            id, ownerInstance, now, deadlineAt, AgentJobStatus.AWAITING_APPROVAL, AgentJobStatus.RUNNING);
    }

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.status = :to, j.ownerInstance = :ownerInstance, j.heartbeatAt = :now, "
        + "j.deadlineAt = :deadlineAt, j.attemptCount = j.attemptCount + 1, "
        + "j.parkedAt = null, j.approvalDeadlineAt = null where j.id = :id and j.status = :from")
    int resumeClaimInternal(
        @Param("id") UUID id,
        @Param("ownerInstance") String ownerInstance,
        @Param("now") Instant now,
        @Param("deadlineAt") Instant deadlineAt,
        @Param("from") AgentJobStatus from,
        @Param("to") AgentJobStatus to
    );

    /**
     * AWAITING_APPROVAL -&gt; FAILED: an approval that timed out, was rejected, or
     * hit the per-job park ceiling. Guarded on AWAITING_APPROVAL so it can never
     * race a resume that landed a microsecond earlier.
     */
    default int failIfAwaitingApproval(UUID id, String errorCode, String errorMessage, Instant now) {
        return failIfAwaitingApprovalInternal(
            id, errorCode, errorMessage, now, AgentJobStatus.AWAITING_APPROVAL, AgentJobStatus.FAILED);
    }

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.status = :to, j.errorCode = :errorCode, j.errorMessage = :errorMessage, "
        + "j.finishedAt = :now, j.ownerInstance = null where j.id = :id and j.status = :from")
    int failIfAwaitingApprovalInternal(
        @Param("id") UUID id,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("now") Instant now,
        @Param("from") AgentJobStatus from,
        @Param("to") AgentJobStatus to
    );

    /**
     * AWAITING_APPROVAL -&gt; CANCELLED, the third branch of cancel.
     *
     * <p>Without it, cancelling a parked job is a no-op on status: {@link
     * #cancelIfStillQueued} is QUEUED-guarded and misses, {@link #requestCancel}
     * writes only the flag, and {@code stopLocally} finds no worker to interrupt
     * because a parked job holds none. The row would sit AWAITING_APPROVAL with
     * {@code cancel_requested = true} forever, keeping its schedule's overlap
     * guard shut - a cancel that reports success and stops nothing.
     */
    default int cancelIfAwaitingApproval(UUID id, String by, Instant now) {
        return cancelIfAwaitingApprovalInternal(
            id, by, now, AgentJobStatus.AWAITING_APPROVAL, AgentJobStatus.CANCELLED);
    }

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.status = :to, j.cancelRequested = true, j.cancelRequestedBy = :by, "
        + "j.cancelRequestedAt = :now, j.finishedAt = :now, j.ownerInstance = null "
        + "where j.id = :id and j.status = :from")
    int cancelIfAwaitingApprovalInternal(
        @Param("id") UUID id,
        @Param("by") String by,
        @Param("now") Instant now,
        @Param("from") AgentJobStatus from,
        @Param("to") AgentJobStatus to
    );

    /**
     * RUNNING -&gt; QUEUED: the durability policy's bounded recovery of an orphan
     * that provably did zero chargeable work. The only backwards edge in the
     * state machine - see {@link AgentJobStatus#canTransitionTo} for why it is
     * not a general restart.
     *
     * <p>Clears owner, heartbeat, start and deadline together. Leaving {@code
     * heartbeat_at} behind would leave the row matching the orphan sweep's query
     * while QUEUED; leaving {@code deadline_at} behind would hand the re-queued
     * job an already-expired budget. {@code attempt_count} is NOT reset - it is
     * the bound on how many times this may happen.
     *
     * <p>Guarded on RUNNING so it loses cleanly to a worker finishing normally in
     * the same instant, exactly as {@link #failIfStillRunning} does.
     *
     * <p><b>Lease.</b> The UPDATE also requires {@code heartbeat_at} to be NULL or older than {@code now} (the
     * moment the sweep started). A live worker, on any replica, refreshes {@code heartbeat_at} to a time later
     * than that, so a worker that heartbeated between the sweep's read and this UPDATE is NOT re-queued: the
     * check-then-act window that could hand one run to two workers is closed in the database, atomically, on
     * top of the QUEUED-&gt;RUNNING claim (which is itself one guarded UPDATE).
     *
     * @param now the sweep's start time; bound as the lease check, no timestamp is written.
     */
    default int requeueIfStillRunning(UUID id, Instant now) {
        return requeueIfStillRunningInternal(id, now, AgentJobStatus.RUNNING, AgentJobStatus.QUEUED);
    }

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.status = :to, j.ownerInstance = null, j.heartbeatAt = null, "
        + "j.startedAt = null, j.deadlineAt = null where j.id = :id and j.status = :from "
        + "and (j.heartbeatAt is null or j.heartbeatAt < :now)")
    int requeueIfStillRunningInternal(
        @Param("id") UUID id,
        @Param("now") Instant now,
        @Param("from") AgentJobStatus from,
        @Param("to") AgentJobStatus to
    );

    /** Queue-TTL expiry: QUEUED -&gt; FAILED, guarded so it cannot steal a job a worker just claimed. */
    default int failIfStillQueued(UUID id, String errorCode, String errorMessage, Instant now) {
        return failIfStillQueuedInternal(id, errorCode, errorMessage, now, AgentJobStatus.QUEUED, AgentJobStatus.FAILED);
    }

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.status = :to, j.errorCode = :errorCode, j.errorMessage = :errorMessage, "
        + "j.finishedAt = :now where j.id = :id and j.status = :from")
    int failIfStillQueuedInternal(
        @Param("id") UUID id,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("now") Instant now,
        @Param("from") AgentJobStatus from,
        @Param("to") AgentJobStatus to
    );

    // ------------------------------------------------- ownership lease (app.agent-jobs.ownership.*)
    //
    // The lease is heartbeat_at + lease-seconds, owned by owner_instance: NO new column. Every statement
    // below is one guarded UPDATE (compare-and-set), so two replicas racing on one row see exactly one
    // rowcount of 1. Nothing here is called unless app.agent-jobs.ownership.enabled=true.

    /**
     * Lease renewal: only the replica that still owns the RUNNING row advances its heartbeat. Rowcount 0 means
     * this replica no longer holds the job (adopted elsewhere) or has not claimed it yet; the caller tells the
     * two apart with {@link #findStatusById} / {@link #findOwnerInstanceById}.
     */
    default int renewLease(UUID id, String owner, Instant now) {
        return renewLeaseInternal(id, owner, now, AgentJobStatus.RUNNING);
    }

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.heartbeatAt = :now where j.id = :id and j.status = :running "
        + "and j.ownerInstance = :owner")
    int renewLeaseInternal(
        @Param("id") UUID id,
        @Param("owner") String owner,
        @Param("now") Instant now,
        @Param("running") AgentJobStatus running
    );

    /**
     * Graceful release on shutdown: the lease is declared over by writing {@code heartbeat_at = releasedAt}
     * (the epoch). Guarded on status and owner so a replica can only release what it still owns.
     */
    default int releaseLease(UUID id, String owner, Instant releasedAt) {
        return releaseLeaseInternal(id, owner, releasedAt, AgentJobStatus.RUNNING);
    }

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.heartbeatAt = :releasedAt where j.id = :id and j.status = :running "
        + "and j.ownerInstance = :owner")
    int releaseLeaseInternal(
        @Param("id") UUID id,
        @Param("owner") String owner,
        @Param("releasedAt") Instant releasedAt,
        @Param("running") AgentJobStatus running
    );

    /** Owner column alone (cheap projection); empty when the row is gone or has no owner. */
    @Query("select j.ownerInstance from AgentJob j where j.id = :id")
    Optional<String> findOwnerInstanceById(@Param("id") UUID id);

    /** The lease sweep's bounded read: RUNNING rows whose heartbeat is older than {@code cutoff}, oldest first. */
    @Query("select j from AgentJob j where j.status = :status and j.heartbeatAt < :cutoff order by j.heartbeatAt asc")
    List<AgentJob> findExpiredLeases(
        @Param("status") AgentJobStatus status,
        @Param("cutoff") Instant cutoff,
        Pageable pageable
    );

    /**
     * Adoption: RUNNING -&gt; QUEUED for a row whose lease is STILL expired and whose {@code attempt_count} is
     * still the one the sweeper observed (so a job that was adopted, re-claimed and is live again is never
     * touched: the ABA guard). Exactly one of N racing sweepers gets rowcount 1. Clears owner, heartbeat,
     * start and deadline like {@link #requeueIfStillRunning}; {@code attempt_count} is NOT reset (it is the
     * resume-attempt bound). {@code heartbeat_at < cutoff} never matches NULL, which is the fail-safe direction.
     */
    default int requeueIfLeaseExpired(UUID id, int observedAttempt, Instant cutoff, String reason) {
        return requeueIfLeaseExpiredInternal(
            id, observedAttempt, cutoff, truncate(reason, MAX_RESUME_REASON_CHARS),
            AgentJobStatus.RUNNING, AgentJobStatus.QUEUED);
    }

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.status = :to, j.ownerInstance = null, j.heartbeatAt = null, "
        + "j.startedAt = null, j.deadlineAt = null, j.resumeReason = :reason "
        + "where j.id = :id and j.status = :from and j.attemptCount = :attempt and j.heartbeatAt < :cutoff")
    int requeueIfLeaseExpiredInternal(
        @Param("id") UUID id,
        @Param("attempt") int observedAttempt,
        @Param("cutoff") Instant cutoff,
        @Param("reason") String reason,
        @Param("from") AgentJobStatus from,
        @Param("to") AgentJobStatus to
    );

    /** RUNNING -&gt; FAILED for an expired lease, with the same ABA + lease guard as {@link #requeueIfLeaseExpired}. */
    default int failIfLeaseExpired(
        UUID id, int observedAttempt, Instant cutoff, String errorCode, String errorMessage, Instant now) {
        return failIfLeaseExpiredInternal(
            id, observedAttempt, cutoff, errorCode, errorMessage, now,
            AgentJobStatus.RUNNING, AgentJobStatus.FAILED);
    }

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.status = :to, j.errorCode = :errorCode, j.errorMessage = :errorMessage, "
        + "j.finishedAt = :now, j.ownerInstance = null "
        + "where j.id = :id and j.status = :from and j.attemptCount = :attempt and j.heartbeatAt < :cutoff")
    int failIfLeaseExpiredInternal(
        @Param("id") UUID id,
        @Param("attempt") int observedAttempt,
        @Param("cutoff") Instant cutoff,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("now") Instant now,
        @Param("from") AgentJobStatus from,
        @Param("to") AgentJobStatus to
    );

    /**
     * RUNNING -&gt; CANCELLED for an expired lease whose cancel was requested: the owner is gone, so nobody
     * would ever observe the cancel flag; the operator's intent is honoured instead of the job being failed.
     */
    default int cancelIfLeaseExpired(UUID id, int observedAttempt, Instant cutoff, Instant now) {
        return cancelIfLeaseExpiredInternal(
            id, observedAttempt, cutoff, now, AgentJobStatus.RUNNING, AgentJobStatus.CANCELLED);
    }

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentJob j set j.status = :to, j.finishedAt = :now, j.ownerInstance = null "
        + "where j.id = :id and j.status = :from and j.attemptCount = :attempt and j.heartbeatAt < :cutoff "
        + "and j.cancelRequested = true")
    int cancelIfLeaseExpiredInternal(
        @Param("id") UUID id,
        @Param("attempt") int observedAttempt,
        @Param("cutoff") Instant cutoff,
        @Param("now") Instant now,
        @Param("from") AgentJobStatus from,
        @Param("to") AgentJobStatus to
    );
}
