package com.agenticai.core.agents.job.ownership;

import com.agenticai.core.agents.job.AgentJobExecutor;
import com.agenticai.core.agents.job.AgentJobDurabilityProperties;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.ownership.JobLeaseAdopter.Outcome;
import com.agenticai.core.agents.job.ownership.JobLeaseStore.LeasedJob;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The per-replica adoption sweep: finds RUNNING jobs whose ownership lease expired (their replica died, was
 * replaced, or released them on shutdown) and re-queues, fails or cancels them per {@link JobResumePlanner}.
 *
 * <p>Same shape as every other sweep in this package: {@link PgAdvisoryJobLock#tryRunExclusive} so replicas do
 * not all sweep at once (a de-duplication convenience; correctness comes from the compare-and-set UPDATEs in
 * {@link JobLeaseStore}), then {@link TenantSchemaIterator#forEachActiveTenantSchema} because a
 * {@code @Scheduled} method has no tenant context, bounded batches per tenant, a per-row try/catch, and no
 * long transaction (each statement is its own short UPDATE).
 *
 * <p><b>OFF unless {@code app.agent-jobs.ownership.enabled=true}</b>: the tick returns immediately without
 * touching the DB. With it ON this sweep REPLACES the pre-existing heartbeat orphan sweep
 * ({@code AgentJobRunner#reapOrphans} returns immediately): two sweeps racing the same rows would give one job
 * two verdicts under two different attempt bounds, and the legacy FAIL could land on a row that was just
 * re-queued and adopted. This sweep covers every RUNNING row whose heartbeat is older than the lease.
 * Because it is a {@code @Scheduled} bean it is listed in the jobs-only {@code skip-bean-classes} of
 * {@code application-shape-api.yml}: an API pod must not run (or locally dispatch) agent jobs.
 *
 * <p><b>Latency:</b> a SIGKILLed owner is adopted after lease (60s) + skew grace (5s) + up to one sweep
 * interval (60s) + one dispatch poll (10s), unless this replica re-queues and dispatches locally in the same
 * tick. A gracefully released job skips the lease wait (see {@link AgentJobShutdownRelease}).
 */
@Component
public class AgentJobLeaseSweep {

    private static final Logger log = LoggerFactory.getLogger(AgentJobLeaseSweep.class);

    private static final String LOCK = "agent-job-lease-sweep";
    private static final String SHUTDOWN_ACTOR = "system:agent-job-lease-release";
    private static final String OUTCOME_METRIC = "agent.job.lease.outcome";

    private final JobLeaseStore store;
    private final AgentJobProperties properties;
    private final JobOwnership ownership;
    private final TenantSchemaIterator schemaIterator;
    private final PgAdvisoryJobLock jobLock;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final JobLeaseAdopter sweepAdopter;
    private final JobLeaseAdopter shutdownAdopter;

    public AgentJobLeaseSweep(
        JobLeaseStore store,
        JobResumeFactsReader factsReader,
        AgentJobProperties properties,
        JobOwnership ownership,
        AgentJobExecutor executor,
        TenantSchemaIterator schemaIterator,
        PgAdvisoryJobLock jobLock,
        MeterRegistry meterRegistry
    ) {
        this.store = store;
        this.properties = properties;
        this.ownership = ownership;
        this.schemaIterator = schemaIterator;
        this.jobLock = jobLock;
        this.meterRegistry = meterRegistry;
        this.clock = Clock.systemUTC();
        this.sweepAdopter = new JobLeaseAdopter(
            store, factsReader::read, this::policy, () -> ownership.timing().leaseSeconds(),
            executor::isActiveHere,
            // Best-effort local fast path; any replica's dispatch tick would also pick the row up.
            job -> executor.tryDispatch(job.id(), job.tenantId()));
        // At shutdown the workers were just interrupted and released, so "active here" is no longer true in
        // spirit even if their threads have not yet unwound, and nothing may be dispatched locally.
        this.shutdownAdopter = new JobLeaseAdopter(
            store, factsReader::read, this::policy, () -> ownership.timing().leaseSeconds(),
            id -> false, job -> { });
    }

    private JobResumePlanner.Policy policy() {
        AgentJobDurabilityProperties durability = properties.getDurability();
        return new JobResumePlanner.Policy(
            durability.isResumeFromJournal(),
            durability.isResumeRerunIncompleteTurn(),
            properties.getOwnership().effectiveResumeMaxAttempts(),
            properties.getQueueTtl(),
            durability.isRequeueZeroWorkOrphans());
    }

    @Scheduled(
        fixedDelayString = "${app.agent-jobs.ownership.sweep-interval:60s}",
        initialDelayString = "${app.agent-jobs.ownership.sweep-interval:60s}"
    )
    public void sweep() {
        if (!ownership.isEnabled() || ownership.isShuttingDown()) {
            return;
        }
        jobLock.tryRunExclusive(LOCK, this::sweepExclusively);
    }

    private void sweepExclusively() {
        Instant now = clock.instant();
        int lease = ownership.timing().leaseSeconds();
        Instant cutoff = JobLeasePolicy.expiryCutoff(now, lease);
        int batch = properties.getOwnership().effectiveSweepBatch();
        schemaIterator.forEachActiveTenantSchema(schema -> {
            List<LeasedJob> expired;
            try {
                expired = store.findExpired(cutoff, batch);
            } catch (RuntimeException e) {
                log.error("Lease sweep could not read expired leases in schema '{}'", schema, e);
                return;
            }
            for (LeasedJob job : expired) {
                try {
                    count(sweepAdopter.resolve(job, now));
                } catch (RuntimeException e) {
                    count(null);
                    log.error("Lease sweep failed to resolve agent job {} in schema '{}'", job.id(), schema, e);
                }
            }
        });
    }

    /**
     * Shutdown fast path: immediately resolves the jobs this replica just interrupted and released, so
     * another replica's normal dispatch tick can claim them within seconds instead of waiting for the next
     * lease sweep. Best-effort and per-job isolated: the release itself already guarantees adoption by the
     * sweep, so a failure here only costs latency.
     */
    void resolveReleased(List<AgentJobExecutor.OwnedJob> released) {
        Instant now = clock.instant();
        for (AgentJobExecutor.OwnedJob owned : released) {
            Optional<TenantContext> previous = TenantContext.current();
            TenantContext.set(TenantContext.of(owned.tenantId(), SHUTDOWN_ACTOR, Set.of()));
            try {
                Optional<LeasedJob> row = store.find(owned.jobId());
                if (row.isPresent()) {
                    Outcome outcome = shutdownAdopter.resolve(row.get(), now);
                    count(outcome);
                    log.info("Agent job {} resolved at shutdown: {}", owned.jobId(), outcome);
                }
            } catch (RuntimeException e) {
                log.warn("Could not resolve released agent job {} at shutdown; the lease sweep will", owned.jobId(), e);
            } finally {
                previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
            }
        }
    }

    private void count(Outcome outcome) {
        try {
            meterRegistry.counter(OUTCOME_METRIC, "outcome", outcome == null ? "error" : outcome.name().toLowerCase())
                .increment();
        } catch (RuntimeException ignored) {
            // metrics must never affect recovery
        }
    }
}
