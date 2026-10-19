package com.agenticai.core.platform;

import com.agenticai.core.chat.LlmUsageRollupService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase P4 (Spend &amp; Reports, §8): pre-aggregates each tenant's prior days of {@code
 * llm_call_log} into the {@code llm_daily_usage_rollup} table (V119) so {@code
 * SpendReportController} serves reports from the rollup instead of scanning the hot log table at
 * volume.
 *
 * <p>Mirrors {@code WorkflowScheduler} exactly: a {@code @Scheduled} method that fans out over
 * every ACTIVE, non-demo tenant via {@link TenantSchemaIterator} (the ONLY correct way to touch a
 * tenant-scoped table from a background job with no HTTP request/JWT - see that class's javadoc; a
 * job that queried the repository directly would silently only ever see the empty {@code public}
 * schema). {@link TenantSchemaIterator} sets/clears {@code TenantContext} per tenant and swallows
 * one tenant's failure so it can't block the rest of the batch.
 *
 * <p>Runs once a day (00:20 UTC - after midnight so "yesterday" is complete, with a small offset to
 * avoid colliding with any midnight-boundary traffic spike) and rolls up a short trailing window
 * rather than only the single prior day: {@link #rollupDay} is idempotent (recompute-and-overwrite
 * per grain), so re-rolling the last few days is cheap and self-heals any day a previous run missed
 * (deploy, restart, transient DB blip) without an unbounded backfill scan.
 *
 * <p>Multi-replica safe since the {@link PgAdvisoryJobLock} wrap: exactly one replica runs each
 * daily rollup. The rollup upsert is idempotent (recompute-and-overwrite per grain), so an earlier
 * version of this javadoc called a double-run "harmless" - true for the final row values, but two
 * replicas recomputing-and-overwriting the same grains concurrently would still race (interleaved
 * delete/insert or overwrite within a grain is not guaranteed atomic across two sessions) and at
 * minimum doubles the aggregate-scan load on the hot log table at exactly the daily boundary. The
 * lock makes "idempotent" not need to also mean "concurrently safe".
 */
@Component
public class LlmUsageRollupScheduler {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageRollupScheduler.class);

    /**
     * How many trailing days (excluding today) to re-roll each run - bounds the self-heal window.
     */
    private static final int TRAILING_DAYS = 3;

    private static final String JOB_LOCK_NAME = "llm-usage-rollup";

    private final TenantSchemaIterator tenantSchemaIterator;
    private final LlmUsageRollupService rollupService;
    private final PgAdvisoryJobLock jobLock;

    public LlmUsageRollupScheduler(
            TenantSchemaIterator tenantSchemaIterator,
            LlmUsageRollupService rollupService,
            PgAdvisoryJobLock jobLock) {
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.rollupService = rollupService;
        this.jobLock = jobLock;
    }

    @Scheduled(cron = "0 20 0 * * *", zone = "UTC")
    public void rollupYesterday() {
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::rollupYesterdayExclusively);
    }

    private void rollupYesterdayExclusively() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // Demo sandboxes excluded (2026-10-09): every consumer of this rollup in
        // the platform console (PlatformSpendService) already excludes them, and
        // the legacy per-visitor sandboxes made this nightly fan-out scale with
        // landing-page traffic rather than with customers.
        tenantSchemaIterator.forEachActiveNonDemoTenantSchema(
                schema -> {
                    for (int i = 1; i <= TRAILING_DAYS; i++) {
                        LocalDate day = today.minusDays(i);
                        try {
                            int grains = rollupService.rollupDay(day);
                            log.debug(
                                    "Rolled up {} llm-usage grain(s) for {} in tenant schema '{}'",
                                    grains,
                                    day,
                                    schema);
                        } catch (RuntimeException e) {
                            // Per-day catch inside the per-tenant catch: one bad day
                            // in one tenant must not stop that tenant's other days or
                            // any other tenant - same layering WorkflowScheduler uses.
                            log.error(
                                    "LLM usage rollup failed for {} in tenant schema '{}'",
                                    day,
                                    schema,
                                    e);
                        }
                    }
                });
    }
}
