package com.agenticai.core.agents.learning.review;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly tick of the daily memory review. It wakes up every hour, not once a night, so a laptop that was asleep
 * at 3am still gets its review when it wakes; {@link MemoryReviewService} claims (person, day) first, so the
 * review itself runs once per person per day. Pure database work, no model call. Same shape as the other sweeps:
 * {@link TenantSchemaIterator} for the tenant context, {@link PgAdvisoryJobLock} so one replica runs a tick, and
 * a failure in one tenant never stops the others. Off unless {@code app.learning.review.enabled} (on in the
 * personal profile) and the proposal store are both on.
 */
@Component
@ConditionalOnProperty(
        name = {"app.learning.proposals.enabled", "app.learning.review.enabled"},
        havingValue = "true")
public class MemoryReviewSweep {

    private static final Logger log = LoggerFactory.getLogger(MemoryReviewSweep.class);
    private static final String JOB_LOCK_NAME = "learning-memory-review";

    private final TenantSchemaIterator tenants;
    private final PgAdvisoryJobLock jobLock;
    private final MemoryReviewService review;

    public MemoryReviewSweep(TenantSchemaIterator tenants, PgAdvisoryJobLock jobLock, MemoryReviewService review) {
        this.tenants = tenants;
        this.jobLock = jobLock;
        this.review = review;
    }

    @Scheduled(cron = "0 27 * * * *", zone = "UTC")
    public void sweep() {
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::sweepExclusively);
    }

    private void sweepExclusively() {
        tenants.forEachActiveTenantSchema(schema -> {
            try {
                int filed = review.reviewTenant();
                if (filed > 0) {
                    log.info("Daily memory review filed {} suggestion(s) in tenant schema '{}'", filed, schema);
                }
            } catch (RuntimeException e) {
                log.error("Daily memory review failed in tenant schema '{}'", schema, e);
            }
        });
    }
}
