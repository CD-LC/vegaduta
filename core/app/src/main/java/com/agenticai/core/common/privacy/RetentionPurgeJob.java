package com.agenticai.core.common.privacy;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly retention enforcement. {@link TenantSchemaIterator} sets the tenant context for each
 * active tenant schema in turn, so {@link RetentionPurger} only ever sees one tenant's data at a
 * time; a failure in one tenant is logged and does not stop the others. {@link PgAdvisoryJobLock}
 * makes exactly one core replica run it. Off-switch: {@code privacy.retention.enabled=false}.
 * Tenants who have not enabled a retention rule lose nothing: the job only reads their (empty)
 * policy table.
 */
@Component
public class RetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);
    private static final String JOB_LOCK_NAME = "privacy-retention-purge";

    private final TenantSchemaIterator tenantSchemaIterator;
    private final PgAdvisoryJobLock jobLock;
    private final RetentionPurger purger;
    private final boolean enabled;

    public RetentionPurgeJob(
            TenantSchemaIterator tenantSchemaIterator,
            PgAdvisoryJobLock jobLock,
            RetentionPurger purger,
            @Value("${privacy.retention.enabled:true}") boolean enabled) {
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.jobLock = jobLock;
        this.purger = purger;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${privacy.retention.cron:0 17 2 * * *}", zone = "UTC")
    public void purge() {
        if (!enabled) {
            return;
        }
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::purgeAllTenants);
    }

    private void purgeAllTenants() {
        tenantSchemaIterator.forEachActiveTenantSchema(
                schema -> {
                    try {
                        purger.purgeCurrentTenant();
                    } catch (RuntimeException e) {
                        log.error("Retention purge failed in tenant schema '{}'", schema, e);
                    }
                });
    }
}
