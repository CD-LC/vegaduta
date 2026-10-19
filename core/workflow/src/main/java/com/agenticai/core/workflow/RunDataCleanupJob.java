package com.agenticai.core.workflow;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TTL sweep for {@link RunDataStore} rows ({@code app.workflow.run-data.ttl-days}, default 14): they hold
 * un-redacted business data, so they must not outlive their usefulness. Same tenant iteration and
 * advisory-lock idiom as {@link WorkflowRunRecoveryJob}; the delete is idempotent, so running without
 * the lock is only wasteful, never wrong.
 */
@Component
public class RunDataCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RunDataCleanupJob.class);
    private static final String JOB_LOCK_KEY = "workflow-run-data-cleanup";

    private final TenantSchemaIterator schemaIterator;
    private final RunDataStore store;
    private final ObjectProvider<PgAdvisoryJobLock> jobLock;
    private final Duration ttl;

    public RunDataCleanupJob(
        TenantSchemaIterator schemaIterator,
        RunDataStore store,
        ObjectProvider<PgAdvisoryJobLock> jobLock,
        @Value("${app.workflow.run-data.ttl-days:14}") long ttlDays
    ) {
        this.schemaIterator = schemaIterator;
        this.store = store;
        this.jobLock = jobLock;
        this.ttl = Duration.ofDays(Math.max(ttlDays, 1));
    }

    @Scheduled(
        fixedDelayString = "${app.workflow.run-data.cleanup-interval-ms:3600000}",
        initialDelayString = "${app.workflow.run-data.cleanup-initial-delay-ms:300000}"
    )
    public void purgeExpired() {
        PgAdvisoryJobLock lock = jobLock.getIfAvailable();
        if (lock != null) {
            lock.tryRunExclusive(JOB_LOCK_KEY, this::sweepAllTenants);
        } else {
            sweepAllTenants();
        }
    }

    /** Package-private seam so tests exercise the sweep without the lock wrapper. */
    void sweepAllTenants() {
        schemaIterator.forEachActiveTenantSchema(this::sweepTenant);
    }

    private void sweepTenant(String schema) {
        try {
            store.deleteOlderThan(Instant.now().minus(ttl));
        } catch (RuntimeException e) {
            // One tenant's failure must not stop the rest of the sweep.
            log.warn("Run data cleanup failed in schema '{}'", schema, e);
        }
    }
}
