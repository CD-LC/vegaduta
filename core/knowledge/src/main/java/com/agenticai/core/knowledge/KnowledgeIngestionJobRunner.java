package com.agenticai.core.knowledge;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Knowledge Stage F3c - the background half of the async ingestion job pattern. {@link
 * KnowledgeIngestionJobService#processJob} already knows how to run a single job; this class is
 * what finds {@code QUEUED} jobs and calls it, on a schedule, across every tenant schema.
 *
 * <p>Uses {@link TenantSchemaIterator} rather than querying {@link KnowledgeIngestionJobRepository}
 * directly - the same reason {@code RetryFailedActionsJob} does: a {@code @Scheduled} method has no
 * {@code TenantContext} of its own, and without this, the poll query would silently only ever see
 * the {@code public} schema (empty for every real tenant), appearing to run successfully while
 * transcribing nothing for anyone. See {@code TenantSchemaIterator}'s javadoc for the full history
 * of this bug class in this codebase.
 *
 * <p>15s poll interval, deliberately shorter than {@code RetryFailedActionsJob}'s 60s or {@code
 * AbandonedSignupCleanupJob}'s hourly cadence - a user waiting on a job-status poll notices latency
 * here much more directly than either of those background concerns.
 *
 * <p>Multi-replica (default): each poll runs under {@link PgAdvisoryJobLock}. Two replicas both
 * finding the same {@code QUEUED} job would both call {@code processJob} on it and double-run the
 * extraction/embedding pipeline (double sidecar cost, duplicate chunks), so the lock is
 * load-bearing here, not just insurance.
 *
 * <p>Opt-in {@code app.knowledge.ingestion.claiming.enabled=true}: no poll lock; each job is
 * claimed on its own ({@link KnowledgeIngestionJobClaimer}) with a heartbeat lease, so several
 * replicas ingest different jobs at the same time and a dead replica's job is reclaimed.
 */
@Component
@Profile("knowledge")
public class KnowledgeIngestionJobRunner {

    private static final long FIXED_DELAY_MS = 15_000L;
    private static final long INITIAL_DELAY_MS = 15_000L;

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionJobRunner.class);

    private static final String JOB_LOCK_NAME = "knowledge-ingestion-poll";

    private final TenantSchemaIterator schemaIterator;
    private final KnowledgeIngestionJobRepository repository;
    private final KnowledgeIngestionJobService jobService;
    private final PgAdvisoryJobLock jobLock;
    private final int maxJobsPerTenantPerTick;
    private final long tickBudgetNanos;
    private final LongSupplier nanoClock;
    private final RotatingTenantWalk walk = new RotatingTenantWalk();
    private final KnowledgeIngestionJobClaimer claimer;
    private final long leaseSeconds;
    private final int maxAttempts;
    private final ScheduledExecutorService heartbeats;

    @Autowired
    public KnowledgeIngestionJobRunner(
            TenantSchemaIterator schemaIterator,
            KnowledgeIngestionJobRepository repository,
            KnowledgeIngestionJobService jobService,
            PgAdvisoryJobLock jobLock,
            @Value("${app.knowledge.ingestion.max-jobs-per-tenant-per-tick:2}")
                    int maxJobsPerTenantPerTick,
            @Value("${app.knowledge.ingestion.tick-budget-seconds:120}") long tickBudgetSeconds,
            KnowledgeIngestionJobClaimer claimer,
            @Value("${app.knowledge.ingestion.claiming.enabled:false}") boolean claimingEnabled,
            @Value("${app.knowledge.ingestion.claiming.lease-seconds:300}") long leaseSeconds,
            @Value("${app.knowledge.ingestion.claiming.max-attempts:3}") int maxAttempts) {
        this(
                schemaIterator,
                repository,
                jobService,
                jobLock,
                maxJobsPerTenantPerTick,
                tickBudgetSeconds * 1_000_000_000L,
                System::nanoTime,
                claimingEnabled ? claimer : null,
                leaseSeconds,
                maxAttempts);
    }

    KnowledgeIngestionJobRunner(
            TenantSchemaIterator schemaIterator,
            KnowledgeIngestionJobRepository repository,
            KnowledgeIngestionJobService jobService,
            PgAdvisoryJobLock jobLock,
            int maxJobsPerTenantPerTick,
            long tickBudgetNanos,
            LongSupplier nanoClock) {
        this(
                schemaIterator,
                repository,
                jobService,
                jobLock,
                maxJobsPerTenantPerTick,
                tickBudgetNanos,
                nanoClock,
                null,
                300,
                3);
    }

    KnowledgeIngestionJobRunner(
            TenantSchemaIterator schemaIterator,
            KnowledgeIngestionJobRepository repository,
            KnowledgeIngestionJobService jobService,
            PgAdvisoryJobLock jobLock,
            int maxJobsPerTenantPerTick,
            long tickBudgetNanos,
            LongSupplier nanoClock,
            KnowledgeIngestionJobClaimer claimer,
            long leaseSeconds,
            int maxAttempts) {
        this.schemaIterator = schemaIterator;
        this.repository = repository;
        this.jobService = jobService;
        this.jobLock = jobLock;
        this.maxJobsPerTenantPerTick = maxJobsPerTenantPerTick;
        this.tickBudgetNanos = tickBudgetNanos;
        this.nanoClock = nanoClock;
        this.claimer = claimer;
        this.leaseSeconds = Math.max(4, leaseSeconds);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.heartbeats =
                claimer == null
                        ? null
                        : TenantAwareExecutors.newSingleThreadScheduledExecutor("knowledge-ingestion-heartbeat", true);
    }

    @PreDestroy
    void shutdown() {
        if (heartbeats != null) {
            heartbeats.shutdownNow();
        }
    }

    @Scheduled(fixedDelay = FIXED_DELAY_MS, initialDelay = INITIAL_DELAY_MS)
    public void processQueuedJobs() {
        if (claimer != null) {
            // Per-job claiming: each claim is exclusive, so no poll-wide lock is taken and
            // replicas may tick at the same time.
            walkTenants(this::processClaimedJobs);
            return;
        }
        // Lock-not-acquired is only DEBUG-logged (by the lock service itself) -
        // another replica processing the queue is success, not an incident.
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::processQueuedJobsExclusively);
    }

    private void processQueuedJobsExclusively() {
        walkTenants(this::processQueuedJobsInSchema);
    }

    /** Per-tenant work of one tick; {@code start} is the tick's start for the budget check. */
    private interface SchemaWork {
        void run(String schema, long start);
    }

    private void walkTenants(SchemaWork work) {
        // Fairness: at most maxJobsPerTenantPerTick jobs per tenant (oldest first, ids only so
        // queued audio blobs are not re-read every tick), and no new job starts once the tick's
        // wall-clock budget is spent. The tenant walk resumes where the previous tick stopped,
        // so a spent budget cannot starve tenants late in the order. A job already running is
        // never cut short: a single job longer than the budget delays the rest of this replica's
        // walk for that long (and, in advisory-lock mode, holds the poll lock, so it delays every
        // replica).
        long start = nanoClock.getAsLong();
        walk.run(schemaIterator, () -> budgetSpent(start), schema -> work.run(schema, start));
    }

    private void processQueuedJobsInSchema(String schema, long start) {
        Pageable page =
                maxJobsPerTenantPerTick > 0
                        ? PageRequest.ofSize(maxJobsPerTenantPerTick)
                        : Pageable.unpaged();
        List<UUID> queued =
                repository.findIdsByStatusOrderByCreatedAtAsc(
                        KnowledgeIngestionJobStatus.QUEUED, page);
        for (UUID jobId : queued) {
            if (budgetSpent(start)) {
                break;
            }
            try {
                jobService.processJob(jobId);
            } catch (RuntimeException e) {
                // processJob() already catches and records its own transcription/ingestion
                // failures - this is a last-resort net for anything unexpected (e.g. a DB error
                // mid-save) so one bad job can never stop the rest of this tenant's queue.
                log.error(
                        "Unexpected error processing knowledge ingestion job {} in schema '{}'",
                        jobId,
                        schema,
                        e);
            }
        }
    }

    /**
     * Claiming variant: claim one job at a time (so a replica never sits on a batch another could
     * run), up to the same per-tenant cap and tick budget, heartbeating while it runs.
     */
    private void processClaimedJobs(String schema, long start) {
        int done = 0;
        while ((maxJobsPerTenantPerTick <= 0 || done < maxJobsPerTenantPerTick)
                && !budgetSpent(start)) {
            Optional<KnowledgeIngestionJobClaimer.Claim> claim;
            try {
                claim = claimer.claimNext(leaseSeconds, maxAttempts);
            } catch (RuntimeException e) {
                log.error("Could not claim a knowledge ingestion job in schema '{}'", schema, e);
                return;
            }
            if (claim.isEmpty()) {
                return;
            }
            if (claim.get().poisoned()) {
                // Already FAILED by the claim; record it on the document and look at the next job.
                failPoisoned(claim.get(), schema);
                continue;
            }
            done++;
            runClaim(claim.get(), schema);
        }
    }

    private void failPoisoned(KnowledgeIngestionJobClaimer.Claim claim, String schema) {
        try {
            jobService.processClaimedJob(claim);
        } catch (RuntimeException e) {
            log.error(
                    "Could not record poisoned knowledge ingestion job {} in schema '{}'",
                    claim.jobId(),
                    schema,
                    e);
        }
    }

    private void runClaim(KnowledgeIngestionJobClaimer.Claim claim, String schema) {
        long periodSeconds = Math.max(1, leaseSeconds / 4);
        ScheduledFuture<?> beat =
                heartbeats.scheduleAtFixedRate(
                        () -> beat(claim), periodSeconds, periodSeconds, TimeUnit.SECONDS);
        try {
            jobService.processClaimedJob(claim);
        } catch (RuntimeException e) {
            log.error(
                    "Unexpected error processing knowledge ingestion job {} in schema '{}'",
                    claim.jobId(),
                    schema,
                    e);
        } finally {
            beat.cancel(false);
        }
    }

    private void beat(KnowledgeIngestionJobClaimer.Claim claim) {
        try {
            if (!claimer.heartbeat(claim)) {
                log.warn(
                        "Knowledge ingestion job {} is no longer ours (reclaimed or finished)",
                        claim.jobId());
            }
        } catch (RuntimeException e) {
            log.warn("Heartbeat failed for knowledge ingestion job {}", claim.jobId(), e);
        }
    }

    private boolean budgetSpent(long startNanos) {
        return tickBudgetNanos > 0 && nanoClock.getAsLong() - startNanos >= tickBudgetNanos;
    }
}
