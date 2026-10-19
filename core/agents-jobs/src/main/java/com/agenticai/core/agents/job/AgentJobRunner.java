package com.agenticai.core.agents.job;

import com.agenticai.core.agents.job.ownership.JobOwnership;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The durable half of the job system: what makes the {@code agent_job} table a real queue rather
 * than a log of what one JVM happened to do in memory.
 *
 * <p>Three responsibilities, each a separate tick because they have genuinely different failure
 * modes:
 *
 * <ul>
 *   <li>{@link #dispatchQueued} - hands QUEUED rows to workers. This is what picks up everything a
 *       saturated pool rejected, everything submitted while this replica was down, and everything a
 *       schedule enqueued.
 *   <li>{@link #enforceDeadlines} - the wall clock that actually works.
 *   <li>{@link #reapOrphans} - crash recovery.
 * </ul>
 *
 * <p>Every tick follows the shape every other scheduled job in this codebase uses: {@link
 * PgAdvisoryJobLock#tryRunExclusive} so two replicas do not both sweep, then {@link
 * TenantSchemaIterator#forEachActiveTenantSchema} because a {@code @Scheduled} method has NO {@link
 * com.agenticai.core.security.TenantContext} and an un-iterated repository call would silently only
 * ever see the empty {@code public} schema, then a per-item try/catch so one bad row cannot stop
 * the batch.
 *
 * <p>The advisory lock is a de-duplication convenience here, never a correctness guarantee - it is
 * documented fail-open. Correctness comes from every write below being a guarded, conditional
 * UPDATE that a loser observes as rowcount 0.
 */
@Component
public class AgentJobRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentJobRunner.class);

    private static final String DISPATCH_LOCK = "agent-job-dispatch";
    private static final String DEADLINE_LOCK = "agent-job-deadline-sweep";
    private static final String ORPHAN_LOCK = "agent-job-orphan-sweep";

    private final AgentJobRepository repository;
    private final AgentJobExecutor executor;
    private final AgentJobProperties properties;
    private final TenantSchemaIterator schemaIterator;
    private final PgAdvisoryJobLock jobLock;

    /**
     * Consulted by {@link #reapOrphans} only. Defaults to {@link
     * AgentJobOrphanRecoveryPolicy#FAIL_LOUDLY}, which is the behaviour shipped today - so with no
     * implementation registered this sweep is byte-for-byte what it always was.
     */
    private final AgentJobOrphanRecoveryPolicy recoveryPolicy;

    /**
     * Every sweep reads its "now" from here rather than {@link Instant#now()} directly. In
     * production this is always {@link Clock#systemUTC()}; the seam exists because the queue-TTL
     * comparison is strict ({@code createdAt < cutoff}) and a coarse system clock can stamp a row
     * and sweep it on the same tick - only a pinnable clock lets a test assert on that boundary
     * deterministically.
     */
    private final Clock clock;

    /**
     * Cross-replica ownership (app.agent-jobs.ownership.*). Optional setter so no constructor arity
     * changes: null (unit tests, slim contexts) or disabled => {@link #reapOrphans} is exactly the
     * pre-ownership sweep.
     */
    private volatile JobOwnership ownership;

    private final AtomicBoolean orphanSweepSupersededLogged = new AtomicBoolean(false);

    private volatile AgentJobOutcomes outcomes;

    @Autowired(required = false)
    public void setOutcomes(AgentJobOutcomes outcomes) {
        this.outcomes = outcomes;
    }

    private void notifyTerminal(java.util.UUID jobId) {
        AgentJobOutcomes notifier = outcomes;
        if (notifier != null) {
            notifier.terminal(jobId);
        }
    }

    @Autowired(required = false)
    public void setOwnership(JobOwnership ownership) {
        this.ownership = ownership;
    }

    @Autowired
    public AgentJobRunner(
            AgentJobRepository repository,
            AgentJobExecutor executor,
            AgentJobProperties properties,
            TenantSchemaIterator schemaIterator,
            PgAdvisoryJobLock jobLock,
            ObjectProvider<AgentJobOrphanRecoveryPolicy> recoveryPolicyProvider) {
        this(
                repository,
                executor,
                properties,
                schemaIterator,
                jobLock,
                recoveryPolicyProvider.getIfAvailable(
                        () -> AgentJobOrphanRecoveryPolicy.FAIL_LOUDLY),
                Clock.systemUTC());
    }

    /**
     * Policy-free constructor, for the unit suite (which builds this class directly with plain
     * Mockito mocks and no Spring context). Behaves exactly as the container-wired one does when no
     * policy bean is registered.
     */
    public AgentJobRunner(
            AgentJobRepository repository,
            AgentJobExecutor executor,
            AgentJobProperties properties,
            TenantSchemaIterator schemaIterator,
            PgAdvisoryJobLock jobLock) {
        this(
                repository,
                executor,
                properties,
                schemaIterator,
                jobLock,
                AgentJobOrphanRecoveryPolicy.FAIL_LOUDLY,
                Clock.systemUTC());
    }

    /** Test seam - a pinned clock is the only race-free way to test the TTL boundary. */
    AgentJobRunner(
            AgentJobRepository repository,
            AgentJobExecutor executor,
            AgentJobProperties properties,
            TenantSchemaIterator schemaIterator,
            PgAdvisoryJobLock jobLock,
            Clock clock) {
        this(
                repository,
                executor,
                properties,
                schemaIterator,
                jobLock,
                AgentJobOrphanRecoveryPolicy.FAIL_LOUDLY,
                clock);
    }

    private AgentJobRunner(
            AgentJobRepository repository,
            AgentJobExecutor executor,
            AgentJobProperties properties,
            TenantSchemaIterator schemaIterator,
            PgAdvisoryJobLock jobLock,
            AgentJobOrphanRecoveryPolicy recoveryPolicy,
            Clock clock) {
        this.repository = repository;
        this.executor = executor;
        this.properties = properties;
        this.schemaIterator = schemaIterator;
        this.jobLock = jobLock;
        this.recoveryPolicy = recoveryPolicy;
        this.clock = clock;
    }

    /**
     * Poll, expire, dispatch. Also the BOOT SWEEP: on a fresh replica the first tick picks up every
     * QUEUED row left behind by whatever ran before it, which is the property that makes "the
     * caller disconnected" and "the process was restarted" the same, already-handled case.
     */
    @Scheduled(
            fixedDelayString = "${app.agent-jobs.poll-interval:10s}",
            initialDelayString = "${app.agent-jobs.poll-interval:10s}")
    public void dispatchQueued() {
        jobLock.tryRunExclusive(DISPATCH_LOCK, this::dispatchQueuedExclusively);
    }

    private void dispatchQueuedExclusively() {
        Instant queueCutoff = clock.instant().minus(properties.getQueueTtl());
        schemaIterator.forEachActiveTenantSchemaSkippingIdle(
                DISPATCH_LOCK,
                schema -> {
                    List<AgentJob> queued =
                            repository.findTop50ByStatusOrderByCreatedAtAsc(AgentJobStatus.QUEUED);
                    for (AgentJob job : queued) {
                        try {
                            if (job.getCreatedAt().isBefore(queueCutoff)) {
                                // The requester's roles were frozen at submit time. Running
                                // an hour-old snapshot of someone's authority means acting
                                // on permissions nobody has re-checked since - so this
                                // expires rather than executes.
                                expire(job);
                                continue;
                            }
                            if (executor.isSaturated()) {
                                // Nothing is lost: the rows stay QUEUED and the next tick
                                // (or a worker finishing) picks them up. Stopping here
                                // rather than churning through rejections keeps the tick
                                // short, which matters on a shared 8-thread pool.
                                return true;
                            }
                            executor.tryDispatch(job.getId(), job.getTenantId());
                        } catch (RuntimeException e) {
                            log.error(
                                    "Failed to dispatch agent job {} in schema '{}'",
                                    job.getId(),
                                    schema,
                                    e);
                        }
                    }
                    return !queued.isEmpty();
                });
    }

    /**
     * The wall clock that ACTUALLY enforces the timeout.
     *
     * <p>This sweep is not redundant with the {@code block(remaining)} inside {@link
     * AgentJobExecution}, and deleting it as duplication would silently remove the only working
     * timeout for a large class of jobs. {@code block(timeout)} cannot fire for a
     * PLANNED/CAREFUL/RIGOROUS turn: {@code AgentChatService#planThenRespond} does blocking work
     * inside a {@code Flux.defer} with no {@code publishOn}, so the defer body executes on the
     * SUBSCRIBING thread - the worker is already inside the LLM call before {@code block}'s timer
     * is ever consulted. The in-band block catches the cheap streaming case; this out-of-band sweep
     * is the backstop that holds for every case.
     *
     * <p>Timeout and operator cancel deliberately route through the SAME stop path (durable write
     * first, then a local interrupt) so there is one mechanism to reason about, not two.
     */
    @Scheduled(
            fixedDelayString = "${app.agent-jobs.deadline-sweep-interval:15s}",
            initialDelayString = "${app.agent-jobs.deadline-sweep-interval:15s}")
    public void enforceDeadlines() {
        jobLock.tryRunExclusive(DEADLINE_LOCK, this::enforceDeadlinesExclusively);
    }

    private void enforceDeadlinesExclusively() {
        Instant now = clock.instant();
        schemaIterator.forEachActiveTenantSchema(
                schema -> {
                    List<AgentJob> overdue =
                            repository.findByStatusAndDeadlineAtBefore(AgentJobStatus.RUNNING, now);
                    for (AgentJob job : overdue) {
                        try {
                            // Guarded UPDATE, so a worker finishing normally in this same
                            // instant wins cleanly instead of the two half-writing the row.
                            int updated =
                                    repository.failIfStillRunning(
                                            job.getId(),
                                            "TIMEOUT",
                                            "Agent job exceeded its "
                                                    + job.getTimeoutSeconds()
                                                    + "s wall-clock budget",
                                            now);
                            if (updated > 0) {
                                log.warn(
                                        "Agent job {} (agent {}) timed out after {}s and was"
                                                + " terminated by the deadline sweep",
                                        job.getId(),
                                        job.getAgentId(),
                                        job.getTimeoutSeconds());
                                // Best-effort; only succeeds if THIS replica owns it.
                                executor.stopLocally(job.getId());
                                notifyTerminal(job.getId());
                            }
                        } catch (RuntimeException e) {
                            log.error(
                                    "Failed to enforce the deadline for agent job {} in schema"
                                            + " '{}'",
                                    job.getId(),
                                    schema,
                                    e);
                        }
                    }
                });
    }

    /**
     * Crash recovery. A RUNNING row whose heartbeat stopped belongs to a worker that no longer
     * exists - a SIGKILLed container, an OOM, a pod eviction.
     *
     * <p>Distinct from {@link #enforceDeadlines} and worth its own tick: an abandoned job will not
     * reach its deadline for up to an hour, and until then it holds the schedule overlap guard
     * shut, so a killed replica would mute a tenant's schedules for the rest of the hour while
     * every layer reported itself healthy.
     *
     * <p><b>What actually makes this safe, stated honestly.</b> HEARTBEAT STALENESS is the
     * cross-replica liveness signal: the sweep only ever sees rows whose {@code heartbeat_at} is
     * older than {@link AgentJobProperties#staleHeartbeatAfter} (five heartbeat intervals, floored
     * at two minutes), and a live worker on ANY replica refreshes its rows every {@code
     * heartbeat-interval}. {@code isActiveHere} is a process-LOCAL check on top - it reads only
     * this JVM's in-memory active map, so it protects exactly one case the heartbeat cannot: a job
     * running on THIS replica whose heartbeat writer is behind (GC pause, slow DB). It says nothing
     * about other replicas - a job live on replica B is simply not in replica A's map.
     *
     * <p>The residual multi-replica window is therefore: a worker elsewhere whose heartbeat writer
     * stalls past the stale threshold while the worker itself is still executing. For the FAIL
     * verdict that costs one wrongly-failed job (the shipped behaviour since PR-1); for the REQUEUE
     * verdict it could double-run - which is why {@code ZeroWorkOrphanRecoveryPolicy} only
     * re-queues when {@code turns_used == 0}, and why {@code AgentJobExecution#runTurns} increments
     * that counter BEFORE the first model call: the requeue-eligible overlap is the few
     * milliseconds between {@code claim} and the first turn marker, not the span of an LLM call.
     * Deploy today runs a single core replica ({@code container_name} blocks {@code --scale}), so
     * the window is theoretical until that changes; anyone enabling a second replica should re-read
     * this paragraph rather than the comforting one it replaced.
     *
     * <p><b>With {@code app.agent-jobs.ownership.enabled=true} this sweep does nothing.</b> The
     * lease sweep ({@code AgentJobLeaseSweep}) then owns every orphan decision: running both would
     * let this FAIL land on a row the lease sweep had just re-queued and another worker had adopted
     * (RUNNING -&gt; QUEUED -&gt; RUNNING is a routine, fast cycle in that mode, and a released row
     * carries {@code heartbeat_at = epoch}, so it always looks stale here), and the two would apply
     * different attempt bounds.
     */
    @Scheduled(
            fixedDelayString = "${app.agent-jobs.deadline-sweep-interval:15s}",
            initialDelayString = "60s")
    public void reapOrphans() {
        JobOwnership own = ownership;
        if (own != null && own.isEnabled()) {
            if (orphanSweepSupersededLogged.compareAndSet(false, true)) {
                log.info(
                        "Legacy agent-job orphan sweep is superseded by the ownership lease sweep "
                                + "(app.agent-jobs.ownership.enabled=true); skipping it");
            }
            return;
        }
        jobLock.tryRunExclusive(ORPHAN_LOCK, this::reapOrphansExclusively);
    }

    private void reapOrphansExclusively() {
        Instant now = clock.instant();
        Instant staleBefore = now.minus(properties.staleHeartbeatAfter());
        schemaIterator.forEachActiveTenantSchema(
                schema -> {
                    List<AgentJob> stale =
                            repository.findByStatusAndHeartbeatAtBefore(
                                    AgentJobStatus.RUNNING, staleBefore);
                    for (AgentJob job : stale) {
                        try {
                            if (executor.isActiveHere(job.getId())) {
                                // Genuinely running on this replica; the heartbeat thread
                                // is merely behind (a GC pause, a slow DB). Not an orphan.
                                continue;
                            }
                            // ONE decision point, here, rather than a second sweep: two
                            // sweeps racing the same rows would give the same job a
                            // nondeterministic verdict. The default policy answers FAIL,
                            // which is exactly the behaviour below, so an unconfigured
                            // deployment reaps precisely as it always has.
                            if (recoveryPolicy.decide(job)
                                    == AgentJobOrphanRecoveryPolicy.Verdict.REQUEUE) {
                                if (repository.requeueIfStillRunning(job.getId(), now) > 0) {
                                    log.warn(
                                            "Agent job {} (agent {}) was re-queued by the recovery"
                                                    + " policy after its worker stopped reporting"
                                                    + " (attempt {}, last owner '{}')",
                                            job.getId(),
                                            job.getAgentId(),
                                            job.getAttemptCount(),
                                            job.getOwnerInstance());
                                } else {
                                    // The guarded requeue lost: the row is no longer RUNNING, or a
                                    // live worker
                                    // heartbeated since the sweep began. Either way it is not ours
                                    // to fail.
                                    log.info(
                                            "Agent job {} was not re-queued (finished or"
                                                + " heartbeated since the sweep began); leaving it"
                                                + " alone",
                                            job.getId());
                                }
                                continue;
                            }
                            String review = recoveryPolicy.takeNeedsReviewReason(job);
                            int updated =
                                    review != null
                                            ? repository.failIfStillRunning(
                                                    job.getId(),
                                                    "ORPHANED_NEEDS_REVIEW",
                                                    "The worker running this job stopped reporting"
                                                        + " and it was NOT re-run because it may"
                                                        + " already have performed a write/send"
                                                        + " action: "
                                                            + review
                                                            + ". Check the external system, then"
                                                            + " re-submit the job if the work is"
                                                            + " still wanted.",
                                                    now)
                                            : repository.failIfStillRunning(
                                                    job.getId(),
                                                    "ORPHANED",
                                                    "The worker running this job stopped reporting;"
                                                        + " it was recovered by the orphan sweep",
                                                    now);
                            if (updated > 0) {
                                log.warn(
                                        "Reaped orphaned agent job {} (agent {}, last owner '{}',"
                                                + " last heartbeat {})",
                                        job.getId(),
                                        job.getAgentId(),
                                        job.getOwnerInstance(),
                                        job.getHeartbeatAt());
                                notifyTerminal(job.getId());
                            }
                        } catch (RuntimeException e) {
                            log.error(
                                    "Failed to reap orphaned agent job {} in schema '{}'",
                                    job.getId(),
                                    schema,
                                    e);
                        }
                    }
                });
    }

    private void expire(AgentJob job) {
        int updated =
                repository.failIfStillQueued(
                        job.getId(),
                        "QUEUE_TTL_EXPIRED",
                        "This job waited in the queue longer than "
                                + properties.getQueueTtl()
                                + " and was not run; its requester authority had gone stale",
                        clock.instant());
        if (updated > 0) {
            log.warn(
                    "Agent job {} expired in the queue after {}",
                    job.getId(),
                    properties.getQueueTtl());
            notifyTerminal(job.getId());
        }
    }
}
