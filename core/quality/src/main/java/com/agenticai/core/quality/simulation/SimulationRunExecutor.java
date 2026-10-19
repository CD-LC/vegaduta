package com.agenticai.core.quality.simulation;

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
 * Bounded in-process execution for simulation runs, plus the boot orphan
 * sweep - a SIBLING of {@code EvalRunExecutor} (see its javadoc for the full
 * "why an in-process executor, why bounded, why a boot sweep" reasoning,
 * which applies here verbatim), deliberately its own instance rather than a
 * shared one: eval runs and simulation runs are different features with
 * different failure/lifecycle stories, and coupling their executors would
 * mean one feature's saturation throttles the other's for no shared reason.
 *
 * <p>Sizing matches {@code EvalRunExecutor}'s: two platform threads, queue
 * of eight. A simulation run is minutes of serial LLM calls too (up to
 * {@code maxTurns} exchanges, each a simulator call PLUS a target-agent
 * replay, plus one final verdict-judge call) - the same bounded-concurrency
 * reasoning applies without change.
 */
@Component
public class SimulationRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(SimulationRunExecutor.class);

    private static final String ORPHAN_REASON =
        "Orphaned by an application restart: simulation runs execute in-process, so a run that was "
            + "QUEUED or RUNNING when the JVM stopped can never resume. Trigger the session again.";

    private final ThreadPoolExecutor executor;
    private final SimulationRunRepository runRepository;
    private final TenantSchemaIterator tenantSchemaIterator;

    /**
     * {@code app.quality.boot-orphan-sweep}: all (default, fail every live row at boot, today) | stale | off.
     * Field-injected with the default repeated so the test-built constructor keeps today's behaviour.
     */
    @Value("${app.quality.boot-orphan-sweep:all}")
    private String bootOrphanSweep = "all";

    @Value("${app.quality.boot-orphan-min-age:PT2H}")
    private String bootOrphanMinAge = "PT2H";

    public SimulationRunExecutor(
        SimulationRunRepository runRepository,
        TenantSchemaIterator tenantSchemaIterator
    ) {
        this.runRepository = runRepository;
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.executor = TenantAwareExecutors.newThreadPool(
            2, 2, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(8),
            // Daemon: a simulation run must never hold the JVM open on
            // shutdown - the boot sweep exists precisely because
            // interrupted runs are an accepted, visible outcome.
            TenantAwareExecutors.numberedThreadFactory("simulation-run-", true));
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * @return true when accepted; false when the pool + queue are full - the
     *     CALLER owns marking the run ERROR (it holds the entity and the
     *     tenant context; doing it here would need both re-derived)
     */
    public boolean trySubmit(Runnable runBody) {
        try {
            executor.execute(runBody);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("Simulation-run executor saturated ({} active, {} queued) - rejecting submission",
                executor.getActiveCount(), executor.getQueue().size());
            return false;
        }
    }

    /** Boot orphan sweep - see {@code EvalRunExecutor#sweepOrphanedRuns}'s javadoc for the full reasoning, unchanged here. */
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
                        log.warn("Failed {} stale simulation run(s) in tenant schema '{}' at boot", swept, schema);
                    }
                });
            }
            return;
        }
        tenantSchemaIterator.forEachActiveTenantSchema(schema -> {
            int swept = runRepository.failAllLiveRuns(ORPHAN_REASON, Instant.now());
            if (swept > 0) {
                log.warn("Failed {} orphaned simulation run(s) in tenant schema '{}' after restart", swept, schema);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
