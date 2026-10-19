package com.agenticai.core.quality;

import com.agenticai.core.quality.dataset.EvalRunRepository;
import com.agenticai.core.quality.simulation.SimulationRunRepository;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic companion of the eval / simulation boot sweeps for {@code app.quality.boot-orphan-sweep=stale|off}.
 *
 * <p>With two core replicas the boot sweep of a restarting replica must not fail runs the other one is
 * executing, so in those modes the boot sweep only fails rows older than
 * {@code app.quality.boot-orphan-min-age}, and this sweep keeps doing the same every
 * {@code app.quality.boot-orphan-sweep-interval-ms} under the Postgres advisory lock (one replica per tick). A
 * run orphaned by a dead JVM therefore ends FAILED within min-age + one interval instead of at the next boot.
 *
 * <p>Default mode {@code all} makes this a no-op, so today's behaviour is unchanged. Bounded: one UPDATE per
 * repository per tenant schema per tick, no long transaction.
 */
@Component
public class QualityRunOrphanSweep {

    private static final Logger log = LoggerFactory.getLogger(QualityRunOrphanSweep.class);

    static final String LOCK_NAME = "quality-run-orphan-sweep";

    private final EvalRunRepository evalRuns;
    private final SimulationRunRepository simulationRuns;
    private final TenantSchemaIterator tenantSchemaIterator;
    private final PgAdvisoryJobLock jobLock;
    private final String modeRaw;
    private final String minAgeRaw;

    public QualityRunOrphanSweep(
        EvalRunRepository evalRuns,
        SimulationRunRepository simulationRuns,
        TenantSchemaIterator tenantSchemaIterator,
        PgAdvisoryJobLock jobLock,
        @Value("${app.quality.boot-orphan-sweep:all}") String modeRaw,
        @Value("${app.quality.boot-orphan-min-age:PT2H}") String minAgeRaw
    ) {
        this.evalRuns = evalRuns;
        this.simulationRuns = simulationRuns;
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.jobLock = jobLock;
        this.modeRaw = modeRaw;
        this.minAgeRaw = minAgeRaw;
    }

    @Scheduled(
        fixedDelayString = "${app.quality.boot-orphan-sweep-interval-ms:600000}",
        initialDelayString = "${app.quality.boot-orphan-sweep-interval-ms:600000}")
    public void sweepStaleRuns() {
        if (BootOrphanSweepPolicy.mode(modeRaw) == BootOrphanSweepPolicy.Mode.ALL) {
            return; // today's behaviour: only the boot sweep, which fails everything
        }
        jobLock.tryRunExclusive(LOCK_NAME, this::sweepAllTenants);
    }

    /** Package-private so the unit test can run the sweep without the lock wrapper. */
    void sweepAllTenants() {
        Duration minAge = BootOrphanSweepPolicy.minAge(minAgeRaw);
        Instant now = Instant.now();
        Instant cutoff = now.minus(minAge);
        tenantSchemaIterator.forEachActiveTenantSchema(schema -> {
            int evalFailed = failQuietly(() -> evalRuns.failStaleLiveRuns(BootOrphanSweepPolicy.STALE_REASON, now, cutoff),
                "eval", schema);
            int simFailed = failQuietly(
                () -> simulationRuns.failStaleLiveRuns(BootOrphanSweepPolicy.STALE_REASON, now, cutoff), "simulation", schema);
            if (evalFailed > 0 || simFailed > 0) {
                log.warn("Failed {} stale eval run(s) and {} stale simulation run(s) older than {} in tenant schema '{}'",
                    evalFailed, simFailed, minAge, schema);
            }
        });
    }

    private static int failQuietly(java.util.function.IntSupplier update, String kind, String schema) {
        try {
            return update.getAsInt();
        } catch (RuntimeException e) {
            log.warn("Stale {} run sweep failed in tenant schema '{}': {}", kind, schema, e.getMessage());
            return 0;
        }
    }
}
