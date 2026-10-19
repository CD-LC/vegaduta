package com.agenticai.core.knowledge;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.List;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background half of {@link KnowledgeReembedService} - same shape as {@link
 * KnowledgeIngestionJobRunner}: a {@code @Scheduled} poll that walks every tenant schema with
 * {@link TenantSchemaIterator} (a scheduled thread has no {@code TenantContext}; without the
 * iterator the poll would only ever see the empty {@code public} schema) under a {@link
 * PgAdvisoryJobLock}.
 *
 * <p>Its OWN lock name, not the ingestion runner's: a large re-embed can run for a long time, and
 * sharing a lock would stall every tenant's audio/video ingestion queue behind it.
 *
 * <p>Picks up EMBEDDING/SWEEPING jobs as well as QUEUED ones - that is the resume path. It's safe
 * because the lock guarantees no other replica is mid-job, and each poll runs jobs to completion
 * synchronously, so a non-terminal job seen at poll time was abandoned (crash, redeploy), not in
 * progress. Every phase is idempotent (see the service javadoc).
 */
@Component
@Profile("knowledge")
public class KnowledgeReembedJobRunner {

    private static final long FIXED_DELAY_MS = 30_000L;
    private static final long INITIAL_DELAY_MS = 45_000L;
    private static final String JOB_LOCK_NAME = "knowledge-reembed-poll";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReembedJobRunner.class);

    private final TenantSchemaIterator schemaIterator;
    private final KnowledgeReembedJobRepository repository;
    private final KnowledgeReembedService reembedService;
    private final PgAdvisoryJobLock jobLock;
    private final int maxJobsPerTenantPerTick;
    private final long tickBudgetNanos;
    private final LongSupplier nanoClock;
    private final RotatingTenantWalk walk = new RotatingTenantWalk();

    @Autowired
    public KnowledgeReembedJobRunner(
            TenantSchemaIterator schemaIterator,
            KnowledgeReembedJobRepository repository,
            KnowledgeReembedService reembedService,
            PgAdvisoryJobLock jobLock,
            @Value("${app.knowledge.reembed.max-jobs-per-tenant-per-tick:1}")
                    int maxJobsPerTenantPerTick,
            @Value("${app.knowledge.reembed.tick-budget-seconds:300}") long tickBudgetSeconds) {
        this(
                schemaIterator,
                repository,
                reembedService,
                jobLock,
                maxJobsPerTenantPerTick,
                tickBudgetSeconds * 1_000_000_000L,
                System::nanoTime);
    }

    KnowledgeReembedJobRunner(
            TenantSchemaIterator schemaIterator,
            KnowledgeReembedJobRepository repository,
            KnowledgeReembedService reembedService,
            PgAdvisoryJobLock jobLock,
            int maxJobsPerTenantPerTick,
            long tickBudgetNanos,
            LongSupplier nanoClock) {
        this.schemaIterator = schemaIterator;
        this.repository = repository;
        this.reembedService = reembedService;
        this.jobLock = jobLock;
        this.maxJobsPerTenantPerTick = maxJobsPerTenantPerTick;
        this.tickBudgetNanos = tickBudgetNanos;
        this.nanoClock = nanoClock;
    }

    @Scheduled(fixedDelay = FIXED_DELAY_MS, initialDelay = INITIAL_DELAY_MS)
    public void processActiveJobs() {
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::processActiveJobsExclusively);
    }

    private void processActiveJobsExclusively() {
        // Fairness: per-tenant cap and a wall-clock budget for the whole tick; leftover jobs stay
        // non-terminal and resume next tick (every phase is idempotent). The tenant walk resumes
        // where the previous tick stopped. A re-embed job runs to completion inside one
        // processJob call and is never interrupted, so one huge job still holds this poll lock
        // (and delays other tenants' re-embeds) until it finishes; making jobs resumable per
        // tick is not done here.
        long start = nanoClock.getAsLong();
        walk.run(
                schemaIterator,
                () -> budgetSpent(start),
                schema -> {
                    List<KnowledgeReembedJob> active =
                            repository.findByStatusInOrderByCreatedAtAsc(
                                    KnowledgeReembedJobStatus.ACTIVE);
                    int done = 0;
                    for (KnowledgeReembedJob job : active) {
                        if (maxJobsPerTenantPerTick > 0 && done >= maxJobsPerTenantPerTick) {
                            break;
                        }
                        if (budgetSpent(start)) {
                            break;
                        }
                        done++;
                        try {
                            reembedService.processJob(job.getId());
                        } catch (RuntimeException e) {
                            // processJob records its own failures; this is the net for
                            // anything it couldn't (e.g. the DB failing mid-save).
                            log.error(
                                    "Unexpected error processing knowledge re-embed job {} in"
                                            + " schema '{}'",
                                    job.getId(),
                                    schema,
                                    e);
                        }
                    }
                });
    }

    private boolean budgetSpent(long startNanos) {
        return tickBudgetNanos > 0 && nanoClock.getAsLong() - startNanos >= tickBudgetNanos;
    }
}
