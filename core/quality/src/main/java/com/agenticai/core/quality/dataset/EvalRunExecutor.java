package com.agenticai.core.quality.dataset;

import com.agenticai.core.quality.BootOrphanSweepPolicy;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bounded in-process execution for eval runs, plus the boot orphan sweep.
 *
 * <h2>Why an in-process executor and not the AgentJob queue</h2>
 * The scout's own recommendation was to reuse {@code agent_job} (durable,
 * multi-replica, deadline-swept, cancellable) with a new {@code EVAL}
 * origin - and that remains the RIGHT eventual home. It is not reachable
 * from this workstream: {@code AgentJobLauncher}/{@code AgentJobService}
 * live in {@code core/agents}, which this module cannot depend on (pom
 * boundary) and whose files this workstream does not own; the enum addition
 * ({@code AgentJobOrigin.EVAL}) is likewise in files under a concurrent
 * workflow's active edits. So this executor makes the honest, smaller
 * promise instead: bounded concurrency, per-run isolation, and an explicit
 * boot sweep that turns "the JVM died mid-run" into a visible FAILED row
 * rather than a forever-RUNNING lie. Migrating execution onto AgentJob
 * (origin=EVAL, origin_ref=run id, one job per case for per-case
 * cancellation) is the recorded cross-workstream follow-up.
 *
 * <h2>Sizing</h2>
 * Two platform threads, queue of eight. A run is minutes of serial LLM
 * calls (N cases x (1 replay + up to 6 judge calls)); two concurrent runs
 * already mean up to ~14 in-flight LLM calls worst case. The bound is the
 * feature, exactly as {@code AgentJobExecutor}'s javadoc argues at length
 * against the "just use virtual threads" cleanup: an unbounded executor
 * here would let one tenant's burst of run triggers exhaust the Hikari pool
 * for everyone. Rejection is surfaced to the caller (run marked FAILED with
 * a clear message) rather than silently queued forever, because unlike
 * agent_job there is no dispatcher tick to ever pick a parked row back up.
 */
@Component
public class EvalRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(EvalRunExecutor.class);

    private static final String ORPHAN_REASON =
        "Orphaned by an application restart: eval runs execute in-process, so a run that was "
            + "QUEUED or RUNNING when the JVM stopped can never resume. Trigger the run again.";

    private final ThreadPoolExecutor executor;
    private final EvalRunRepository runRepository;
    private final TenantSchemaIterator tenantSchemaIterator;

    /**
     * {@code app.quality.boot-orphan-sweep}: all (default, fail every live row at boot, today) | stale | off.
     * Field-injected with the default repeated so the test-built constructor keeps today's behaviour.
     */
    @Value("${app.quality.boot-orphan-sweep:all}")
    private String bootOrphanSweep = "all";

    @Value("${app.quality.boot-orphan-min-age:PT2H}")
    private String bootOrphanMinAge = "PT2H";

    public EvalRunExecutor(
        EvalRunRepository runRepository,
        TenantSchemaIterator tenantSchemaIterator
    ) {
        this.runRepository = runRepository;
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.executor = TenantAwareExecutors.newThreadPool(
            2, 2, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(8),
            // Daemon: an eval run must never hold the JVM open on
            // shutdown - the boot sweep exists precisely because
            // interrupted runs are an accepted, visible outcome.
            TenantAwareExecutors.numberedThreadFactory("eval-run-", true));
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * @return true when accepted; false when the pool + queue are full -
     *     the CALLER owns marking the run FAILED (it holds the entity and
     *     the tenant context; doing it here would need both re-derived)
     */
    public boolean trySubmit(Runnable runBody) {
        try {
            executor.execute(runBody);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("Eval-run executor saturated ({} active, {} queued) - rejecting submission",
                executor.getActiveCount(), executor.getQueue().size());
            return false;
        }
    }

    /**
     * Boot orphan sweep. Runs once, after the context is fully up, across
     * every active tenant schema ({@link TenantSchemaIterator} - the
     * mandatory idiom for tenant-scoped work with no request context; see
     * its javadoc for the two cross-tenant-leak incidents that make direct
     * repository calls from here a bug by construction).
     *
     * <p>HONEST MULTI-REPLICA CAVEAT: with more than one core replica this
     * sweep would fail runs a sibling replica is genuinely still executing
     * - it has no owner_instance column to check, unlike agent_job's sweep.
     * Accepted deliberately: production runs a single core replica today
     * (the 2-replica move is blocked - see
     * docs/full-architecture-audit-2026-08-07), and the fix is not to grow
     * owner-tracking here but to move execution onto agent_job, per the
     * class javadoc.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOrphanedRuns() {
        BootOrphanSweepPolicy.Mode mode = BootOrphanSweepPolicy.mode(bootOrphanSweep);
        if (mode != BootOrphanSweepPolicy.Mode.ALL) {
            // Multi-replica safe: never fail a run another replica or the previous revision may still be
            // executing. "stale" fails only old rows now (QualityRunOrphanSweep repeats that periodically);
            // "off" leaves everything to that periodic sweep.
            if (mode == BootOrphanSweepPolicy.Mode.STALE) {
                java.time.Instant now = java.time.Instant.now();
                java.time.Instant cutoff = now.minus(BootOrphanSweepPolicy.minAge(bootOrphanMinAge));
                tenantSchemaIterator.forEachActiveTenantSchema(schema -> {
                    int swept = runRepository.failStaleLiveRuns(BootOrphanSweepPolicy.STALE_REASON, now, cutoff);
                    if (swept > 0) {
                        log.warn("Failed {} stale eval run(s) in tenant schema '{}' at boot", swept, schema);
                    }
                });
            }
            return;
        }
        tenantSchemaIterator.forEachActiveTenantSchema(schema -> {
            // The transaction lives on the repository method itself (see its
            // javadoc) - the iterator supplies only TenantContext.
            int swept = runRepository.failAllLiveRuns(ORPHAN_REASON, Instant.now());
            if (swept > 0) {
                log.warn("Failed {} orphaned eval run(s) in tenant schema '{}' after restart", swept, schema);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
