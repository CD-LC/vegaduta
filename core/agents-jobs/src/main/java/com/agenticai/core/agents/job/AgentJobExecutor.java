package com.agenticai.core.agents.job;

import com.agenticai.core.agents.job.ownership.JobLeasePolicy;
import com.agenticai.core.agents.job.ownership.JobOwnership;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import jakarta.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;

/**
 * Owns the bounded worker pool agent jobs actually run on, and the liveness
 * signal that lets other replicas tell a live job from an abandoned one.
 *
 * <h2>Why a dedicated bounded ThreadPoolExecutor on PLATFORM threads</h2>
 * Three plausible-looking alternatives are all wrong here, and this comment
 * exists because each of them will look like an obvious cleanup to someone
 * later:
 * <ul>
 *   <li><b>Not {@code @Async}.</b> {@code spring.threads.virtual.enabled=true}
 *       in {@code application.yml}, which makes Boot replace {@code
 *       applicationTaskExecutor} with an UNBOUNDED virtual {@code
 *       SimpleAsyncTaskExecutor}. Twenty submissions would become twenty
 *       concurrent JPA workers against a Hikari pool of 15.</li>
 *   <li><b>Not the {@code @Scheduled} pool.</b> It is size 8, already shared by
 *       ~21 scheduled methods, and its own configuration comment warns against
 *       putting long-running work on it. A job holds its thread for minutes.</li>
 *   <li><b>Not a {@code Semaphore} plus virtual threads.</b> The bound IS the
 *       feature - it is what protects Hikari (15), PgBouncer (25) and the shared
 *       hosted-LLM pool from a burst of jobs. At four concurrent jobs of
 *       minute-scale duration, four parked platform threads is a rounding error,
 *       and a real thread is what makes {@code future.cancel(true)} able to
 *       interrupt a blocking call.</li>
 * </ul>
 *
 * <h2>Rejection is normal, not an error</h2>
 * {@code POST} writes the durable QUEUED row FIRST and only then calls {@link
 * #tryDispatch}. A {@link RejectedExecutionException} is caught, logged at DEBUG
 * and the row simply stays QUEUED for {@link AgentJobRunner} to pick up. In-memory
 * dispatch is a LATENCY OPTIMISATION; <b>the {@code agent_job} table is the
 * queue.</b> That is also why a full pool must never become a 429 - nothing was
 * lost.
 *
 * <h2>The heartbeat runs here, not in AgentJobRunner</h2>
 * Only this class knows which jobs this replica is actually executing, and the
 * heartbeat must not consume one of the shared 8 scheduler threads every 15
 * seconds. It is a single daemon thread doing a handful of one-column UPDATEs.
 * It also doubles as the CANCEL OBSERVER: an operator on another replica can
 * only set {@code cancel_requested}, and this tick is where the owning replica
 * notices and interrupts the worker. With {@code
 * app.agent-jobs.interrupt-on-external-terminal=true} it observes a row
 * terminalised by another replica's deadline/orphan sweep the same way, which
 * is what makes the wall-clock deadline hold when the sweeping pod and the
 * executing pod are different (API/jobs split).
 */
@Component
public class AgentJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentJobExecutor.class);

    /**
     * host+pid, computed once. Written to {@code agent_job.owner_instance} so a
     * boot sweep on replica A can never reap a job replica B is genuinely still
     * running - the difference between crash recovery and cross-replica
     * sabotage.
     */
    private static final String INSTANCE_ID = computeInstanceId();

    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService heartbeatScheduler;
    private final AgentJobRepository repository;
    private final AgentJobExecution execution;
    private final AgentJobProperties properties;

    /**
     * Jobs this replica is executing right now, by id. Read by {@link
     * #stopLocally} (to interrupt) and by the heartbeat tick (to prove
     * liveness). Holds the tenant id alongside the task because the heartbeat
     * thread has no {@link TenantContext} of its own and must rebuild one per
     * job before it can touch a tenant-scoped row.
     */
    private final Map<UUID, ActiveJob> active = new ConcurrentHashMap<>();

    /**
     * Cross-replica ownership (app.agent-jobs.ownership.*). Optional setter so no constructor arity changes:
     * null (unit tests, slim contexts) or disabled => every code path below is exactly the pre-ownership one.
     */
    private volatile JobOwnership ownership;

    @Autowired(required = false)
    public void setOwnership(JobOwnership ownership) {
        this.ownership = ownership;
    }

    public AgentJobExecutor(
        AgentJobRepository repository,
        AgentJobExecution execution,
        AgentJobProperties properties
    ) {
        this.repository = repository;
        this.execution = execution;
        this.properties = properties;

        int workers = Math.max(1, properties.getMaxConcurrent());
        // Core == max, so the pool reaches full width immediately rather than
        // growing only once the queue fills (ThreadPoolExecutor's default growth
        // rule would otherwise leave a single worker serving a full queue).
        // AbortPolicy on purpose: the caller MUST see the rejection so it can
        // leave the row QUEUED, which is the whole fallback mechanism. A
        // CallerRunsPolicy here would run a multi-minute LLM call on the servlet
        // request thread - exactly what this feature exists to avoid.
        this.pool = TenantAwareExecutors.newThreadPool(
            workers, workers,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity())),
            new CustomizableThreadFactory("agent-job-"),
            new ThreadPoolExecutor.AbortPolicy()
        );

        this.heartbeatScheduler = TenantAwareExecutors.newScheduledThreadPool(
            1, new CustomizableThreadFactory("agent-job-heartbeat-"));
        // effectiveHeartbeatInterval() == getHeartbeatInterval() unless app.agent-jobs.ownership.enabled=true.
        long intervalMs = Math.max(1_000L, properties.effectiveHeartbeatInterval().toMillis());
        this.heartbeatScheduler.scheduleWithFixedDelay(
            this::heartbeatTick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Best-effort hand-off of a QUEUED job to a local worker.
     *
     * @return {@code false} when the pool and its queue are full, or when this
     *     tenant already occupies its share of the workers. Either way the
     *     caller leaves the row QUEUED; nothing is lost and nothing is an error.
     */
    public boolean tryDispatch(UUID jobId, String tenantId) {
        JobOwnership own = ownership;
        if (own != null && own.isShuttingDown()) {
            // This process is going away: a job claimed now would only be interrupted. Leave the row QUEUED
            // for another replica.
            return false;
        }
        if (active.containsKey(jobId)) {
            // Already ours. Happens when the poller races the submit path for a
            // row the submit path had already handed to a worker.
            return true;
        }
        if (tenantActiveCount(tenantId) >= properties.getMaxConcurrentPerTenant()) {
            log.debug(
                "Not dispatching agent job {} locally - tenant '{}' already occupies its share of the worker pool",
                jobId, tenantId
            );
            return false;
        }

        FutureTask<Void> task = new FutureTask<>(() -> {
            execution.run(jobId, tenantId);
            return null;
        });
        // Registered BEFORE submission, and removed by the wrapper below, so
        // there is no window where a running job is invisible to stopLocally.
        active.put(jobId, new ActiveJob(tenantId, task));
        try {
            pool.execute(() -> {
                try {
                    task.run();
                } finally {
                    active.remove(jobId);
                }
            });
            return true;
        } catch (RejectedExecutionException poolFull) {
            active.remove(jobId);
            log.debug("Agent job {} stays QUEUED - local worker pool is saturated (this is normal)", jobId);
            return false;
        }
    }

    /**
     * Interrupts the worker running {@code jobId} on THIS replica.
     *
     * @return {@code true} only if this replica actually owned the job. A {@code
     *     false} here is not a failure to cancel: the durable {@code
     *     cancel_requested} flag has already been written by the caller and is
     *     the authoritative signal. There is no cross-replica interrupt without
     *     a message bus, and this workstream adds no bus - saying so plainly is
     *     better than a method that pretends to have stopped something.
     */
    public boolean stopLocally(UUID jobId) {
        ActiveJob job = active.get(jobId);
        if (job == null) {
            return false;
        }
        job.task().cancel(true);
        return true;
    }

    /** How many jobs this replica is executing right now. */
    public int activeCount() {
        return active.size();
    }

    /** Whether this replica is the one running {@code jobId}. */
    public boolean isActiveHere(UUID jobId) {
        return active.containsKey(jobId);
    }

    /** {@code true} when no further job can be handed off without being rejected. */
    public boolean isSaturated() {
        return pool.getQueue().remainingCapacity() == 0;
    }

    /** A job this replica is executing, as seen by the shutdown release. */
    public record OwnedJob(UUID jobId, String tenantId) {
    }

    /**
     * Shutdown step for ownership mode (see {@code AgentJobShutdownRelease}): stops the heartbeat tick for
     * good, interrupts every local worker, and returns what was running so the caller can release the rows.
     * Does NOT shut the pool down (the {@code @PreDestroy} hook still does).
     */
    public List<OwnedJob> quiesceForShutdown() {
        heartbeatScheduler.shutdownNow();
        try {
            heartbeatScheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<OwnedJob> owned = new ArrayList<>();
        for (Map.Entry<UUID, ActiveJob> entry : active.entrySet()) {
            owned.add(new OwnedJob(entry.getKey(), entry.getValue().tenantId()));
            entry.getValue().task().cancel(true);
        }
        // Bounded wait for the interrupted workers to actually leave `active`, so the release that follows is
        // not racing a worker that is still unwinding. A worker stuck in non-interruptible I/O simply times
        // out here: the release still happens (the adopter's claim is then a duplicate read-only call at worst;
        // durable calls are protected by the planner and the effect journal).
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(QUIESCE_WORKER_WAIT_MS);
        while (!active.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return owned;
    }

    /**
     * Whether the heartbeat scheduler has fully terminated. When {@code false} after {@link
     * #quiesceForShutdown} (a tick was still inside a renewal), the caller re-asserts the lease release because
     * that tick may have written {@code heartbeat_at = now} after the first release.
     */
    public boolean heartbeatQuiesced() {
        return heartbeatScheduler.isTerminated();
    }

    /** host+pid - the value written to {@code agent_job.owner_instance}. */
    public static String instanceId() {
        return INSTANCE_ID;
    }

    /**
     * Proves liveness for every job this replica owns, and observes cancels
     * requested elsewhere.
     *
     * <p>Every failure is swallowed per job: a heartbeat write that fails must
     * never take down the scheduler thread, because losing the scheduler would
     * make every other running job look abandoned to the orphan sweep - turning
     * one transient DB blip into a mass reap.
     */
    void heartbeatTick() {
        JobOwnership own = ownership;
        if (own != null && own.isShuttingDown()) {
            // The shutdown release owns the rows now; renewing a released lease would undo it.
            return;
        }
        Instant now = Instant.now();
        for (Map.Entry<UUID, ActiveJob> entry : active.entrySet()) {
            UUID jobId = entry.getKey();
            String tenantId = entry.getValue().tenantId();
            // This thread has no context of its own and the row is tenant-scoped,
            // so without this the UPDATE would silently target the empty `public`
            // schema and every job would look abandoned. Save-and-restore rather
            // than set-and-clear, because this thread is long-lived and reused.
            Optional<TenantContext> previous = TenantContext.current();
            TenantContext.set(TenantContext.of(tenantId, HEARTBEAT_ACTOR, Set.of()));
            try {
                if (own != null && own.isEnabled()) {
                    // Ownership mode: renew only while this replica still owns the RUNNING row. A miss that
                    // proves another replica owns it means this worker is a zombie (it was adopted after a
                    // pause longer than the lease): stop it rather than run the job twice.
                    if (own.renew(jobId, now) == JobLeasePolicy.RenewOutcome.LOST) {
                        log.warn("Agent job {} was adopted by another replica (lease lost) - interrupting its "
                            + "worker on {}", jobId, own.ownerId());
                        stopLocally(jobId);
                        continue;
                    }
                } else {
                    repository.touchHeartbeat(jobId, now);
                }
                if (Boolean.TRUE.equals(repository.isCancelRequested(jobId).orElse(false))) {
                    log.info("Agent job {} has a pending cancel request - interrupting its worker on {}", jobId, INSTANCE_ID);
                    stopLocally(jobId);
                } else if (properties.isInterruptOnExternalTerminal()
                    && repository.findStatusById(jobId).filter(AgentJobStatus::isTerminal).isPresent()) {
                    // A sweep on ANOTHER replica (deadline, orphan) failed the row
                    // while this worker still runs it; the in-process stopLocally
                    // that sweep attempted could not reach us. The row is the
                    // signal, so stop burning a worker slot and an LLM call on it.
                    log.info("Agent job {} was terminalised elsewhere - interrupting its worker on {}", jobId, INSTANCE_ID);
                    stopLocally(jobId);
                }
            } catch (RuntimeException e) {
                log.warn("Heartbeat tick failed for agent job {} (job continues; may look stale to the orphan sweep)", jobId, e);
            } finally {
                previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
            }
        }
    }

    private int tenantActiveCount(String tenantId) {
        int count = 0;
        for (ActiveJob job : active.values()) {
            if (job.tenantId().equals(tenantId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Interrupts in-flight jobs on shutdown rather than waiting for them. A job
     * killed mid-flight leaves a RUNNING row whose heartbeat stops, which the
     * orphan sweep on this or any replica reaps into a FAILED row with a real
     * error code - a strictly better outcome than blocking a container's
     * shutdown grace period on a ten-minute LLM call.
     */
    @PreDestroy
    void shutdown() {
        heartbeatScheduler.shutdownNow();
        pool.shutdownNow();
    }

    private static final String HEARTBEAT_ACTOR = "system:agent-job-heartbeat";

    /** Max time {@link #quiesceForShutdown} waits for interrupted workers to unwind (well inside the 30 s SIGTERM grace). */
    private static final long QUIESCE_WORKER_WAIT_MS = 3_000L;

    private static String computeInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception unresolvable) {
            // A container with no resolvable hostname must still get a STABLE,
            // distinct id - falling back to a constant would make two replicas
            // indistinguishable and let each reap the other's jobs.
            host = "unknown-" + UUID.randomUUID().toString().substring(0, 8);
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        int at = pid.indexOf('@');
        if (at > 0) {
            pid = pid.substring(0, at);
        }
        String id = host + ":" + pid;
        return id.length() <= 128 ? id : id.substring(0, 128);
    }

    /** One locally-executing job: enough to interrupt it and to heartbeat for it. */
    private record ActiveJob(String tenantId, FutureTask<Void> task) {
    }
}
