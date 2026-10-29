package com.agenticai.core.tenancy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically retries tenant schemas whose startup migration failed (see {@link
 * TenantMigrationFailures}). Only on the shape that migrates on boot; a no-op when nothing failed.
 * Interval: {@code app.tenancy.migrate-retry-interval} (default 60s).
 */
@Component
@ConditionalOnProperty(
        name = "app.tenancy.migrate-on-boot",
        havingValue = "true",
        matchIfMissing = true)
public class TenantMigrationRetryJob {

    private final TenantMigrationService migrationService;
    private final TenantMigrationFailures failures;
    private final PgAdvisoryJobLock jobLock;

    public TenantMigrationRetryJob(
            TenantMigrationService migrationService,
            TenantMigrationFailures failures,
            PgAdvisoryJobLock jobLock) {
        this.migrationService = migrationService;
        this.failures = failures;
        this.jobLock = jobLock;
    }

    @Scheduled(
            fixedDelayString = "${app.tenancy.migrate-retry-interval:60s}",
            initialDelayString = "${app.tenancy.migrate-retry-interval:60s}")
    public void retry() {
        if (failures.snapshot().isEmpty()) {
            return;
        }
        jobLock.tryRunExclusive("tenant-migration-retry", migrationService::retryFailed);
    }
}
