package com.agenticai.core.agents.learning.consolidation;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly tick of the memory consolidation (phase 1 design 03, section 4; ADR-0028 WP5b). It wakes
 * hourly rather than once a night, like {@code MemoryReviewSweep}, so a laptop asleep at 3am still
 * consolidates when it wakes; {@link MemoryConsolidationService} claims (person, day) first, so
 * each person is consolidated once a day. Same shape as the other sweeps: {@link
 * TenantSchemaIterator} supplies the tenant context, {@link PgAdvisoryJobLock} keeps it to one
 * replica, and a failure in one tenant never stops the others.
 *
 * <p>Off unless {@code app.memory.consolidation.enabled=true} (default false). Starts as a dry run:
 * it plans and reports only; tombstone-marking additionally needs {@code
 * app.memory.consolidation.auto-apply} and the end of the planner's dry-run window.
 */
@Component
@ConditionalOnProperty(name = "app.memory.consolidation.enabled", havingValue = "true")
public class MemoryConsolidationSweep {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationSweep.class);
    private static final String JOB_LOCK_NAME = "learning-memory-consolidation";

    private final TenantSchemaIterator tenants;
    private final PgAdvisoryJobLock jobLock;
    private final MemoryConsolidationService consolidation;

    public MemoryConsolidationSweep(
            TenantSchemaIterator tenants,
            PgAdvisoryJobLock jobLock,
            MemoryConsolidationService consolidation) {
        this.tenants = tenants;
        this.jobLock = jobLock;
        this.consolidation = consolidation;
    }

    @Scheduled(cron = "0 53 * * * *", zone = "UTC")
    public void sweep() {
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::sweepExclusively);
    }

    private void sweepExclusively() {
        tenants.forEachActiveTenantSchema(
                schema -> {
                    try {
                        MemoryConsolidationService.Report r = consolidation.consolidateTenant();
                        if (r.subjects() > 0) {
                            log.info(
                                    "Memory consolidation in tenant schema '{}': {} person(s), {} row(s),"
                                            + " {} forget(s), {} merge(s), {} summary(ies), {} tombstoned, {} proposal(s) filed",
                                    schema,
                                    r.subjects(),
                                    r.rows(),
                                    r.forgets(),
                                    r.merges(),
                                    r.summaries(),
                                    r.tombstoned(),
                                    r.proposals());
                        }
                    } catch (RuntimeException e) {
                        log.error("Memory consolidation failed in tenant schema '{}'", schema, e);
                    }
                });
    }
}
