package com.agenticai.core.platform.retention;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention purge for tenant tables that grow per message / event and were never trimmed:
 * published {@code workflow_event_outbox} rows, finished {@code webhook_delivery} rows and old
 * {@code notification} rows (plus {@code tool_call_log}, opt-in). Workflow run data already has
 * {@code RunDataCleanupJob} and the step journal has {@code StepJournalRetentionPurge}.
 *
 * <p>Deliberately NOT purged: {@code llm_call_log} (read as raw rows by agent reliability, experiments,
 * quality judging, per-session usage, spend caps and the tenant data export, with windows this job cannot
 * see; {@code LlmDailyUsageRollup} only keeps daily aggregates) and {@code audit_log_entry} (append-only
 * and covered by the tamper-evident audit seal chain). {@code tool_call_log} is opt-in because its all-time
 * per-agent counts and the MCP-grant forensic view read it.
 *
 * <p>Shape: the same as the other tenant sweeps. Postgres advisory lock so one replica works per tick, the
 * tenant iterator (a {@code @Scheduled} thread has no tenant context; tables are schema-qualified here so the
 * plain {@link JdbcTemplate} hits the right schema), batched {@code DELETE ... LIMIT} so no statement holds
 * locks for long, a bounded number of batches per table per tenant per tick plus a tick time budget, and a
 * small pause between batches. Deletes are idempotent, so the lock is a convenience, not correctness.
 *
 * <p>Rollout: {@code app.retention.purge.enabled} defaults to FALSE (nothing runs). Turn it on with
 * {@code dry-run=true} (the default) first: each tick then only COUNTS what would be deleted (bounded by the
 * same per-tick batch limits) and logs it at INFO. Set {@code dry-run=false} to actually delete.
 *
 * <p>Intentionally kept (follow-up if they grow): dead-lettered outbox rows ({@code published_at IS NULL},
 * retries exhausted) and {@code webhook_delivery} rows stuck in {@code PENDING}. Both are operational
 * evidence of a failure and are never aged out here. No new indexes are added: the predicates seq-scan per
 * batch, which is acceptable for a 6-hourly bounded job; an index migration on these hot insert tables was
 * judged riskier (Flyway cannot CREATE INDEX CONCURRENTLY inside its transaction).
 */
@Component
public class DataRetentionPurge {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionPurge.class);
    static final String LOCK_NAME = "data-retention-purge";
    private static final Pattern SAFE_SCHEMA = Pattern.compile("[A-Za-z0-9_]+");

    /** One purgeable table: the predicate that makes a row deletable at the cutoff (bound as one Timestamp). */
    record Target(String table, String predicate, Duration window) {
        String deleteSql(String schema, int batchSize) {
            String qualified = "\"" + schema + "\".\"" + table + "\"";
            return "DELETE FROM " + qualified + " WHERE id IN (SELECT id FROM " + qualified
                + " WHERE " + predicate + " LIMIT " + batchSize + ")";
        }

        /** Dry-run: bounded count of rows a purge tick could delete from this table. */
        String countSql(String schema, int limit) {
            return "SELECT count(*) FROM (SELECT 1 FROM \"" + schema + "\".\"" + table + "\" WHERE " + predicate
                + " LIMIT " + limit + ") c";
        }
    }

    private final JdbcTemplate jdbc;
    private final TenantSchemaIterator schemaIterator;
    private final PgAdvisoryJobLock jobLock;
    private final Clock clock;
    private final LongConsumer pause;
    private final boolean enabled;
    private final boolean dryRun;
    private final AtomicInteger cursor = new AtomicInteger();
    private final int batchSize;
    private final int maxBatchesPerTable;
    private final long pauseMs;
    private final long maxTickSeconds;
    private final List<Target> targets;

    @Autowired
    public DataRetentionPurge(
        JdbcTemplate jdbc,
        TenantSchemaIterator schemaIterator,
        PgAdvisoryJobLock jobLock,
        @Value("${app.retention.purge.enabled:false}") boolean enabled,
        @Value("${app.retention.purge.dry-run:true}") boolean dryRun,
        @Value("${app.retention.purge.batch-size:5000}") int batchSize,
        @Value("${app.retention.purge.max-batches-per-table:20}") int maxBatchesPerTable,
        @Value("${app.retention.purge.pause-ms:50}") long pauseMs,
        @Value("${app.retention.purge.max-tick-seconds:120}") long maxTickSeconds,
        @Value("${app.retention.purge.outbox-published-days:7}") int outboxDays,
        @Value("${app.retention.purge.webhook-delivery-days:30}") int webhookDays,
        @Value("${app.retention.purge.notification-days:90}") int notificationDays,
        @Value("${app.retention.purge.tool-call-log.enabled:false}") boolean toolCallLogEnabled,
        @Value("${app.retention.purge.tool-call-log-days:90}") int toolCallLogDays
    ) {
        this(jdbc, schemaIterator, jobLock, Clock.systemUTC(), DataRetentionPurge::sleep, enabled, dryRun, batchSize,
            maxBatchesPerTable, pauseMs, maxTickSeconds,
            targets(outboxDays, webhookDays, notificationDays, toolCallLogEnabled, toolCallLogDays));
    }

    DataRetentionPurge(
        JdbcTemplate jdbc,
        TenantSchemaIterator schemaIterator,
        PgAdvisoryJobLock jobLock,
        Clock clock,
        LongConsumer pause,
        boolean enabled,
        boolean dryRun,
        int batchSize,
        int maxBatchesPerTable,
        long pauseMs,
        long maxTickSeconds,
        List<Target> targets
    ) {
        this.jdbc = jdbc;
        this.schemaIterator = schemaIterator;
        this.jobLock = jobLock;
        this.clock = clock;
        this.pause = pause;
        this.enabled = enabled;
        this.dryRun = dryRun;
        this.batchSize = Math.max(100, batchSize);
        this.maxBatchesPerTable = Math.max(1, maxBatchesPerTable);
        this.pauseMs = Math.max(0, pauseMs);
        this.maxTickSeconds = Math.max(1, maxTickSeconds);
        this.targets = targets;
    }

    /** Windows are floored at 1 day so a typo can never purge live rows. */
    static List<Target> targets(
        int outboxDays, int webhookDays, int notificationDays, boolean toolCallLogEnabled, int toolCallLogDays
    ) {
        List<Target> t = new ArrayList<>();
        t.add(new Target("workflow_event_outbox", "published_at IS NOT NULL AND published_at < ?",
            days(outboxDays)));
        t.add(new Target("webhook_delivery", "status <> 'PENDING' AND created_at < ?", days(webhookDays)));
        t.add(new Target("notification", "created_at < ?", days(notificationDays)));
        if (toolCallLogEnabled) {
            t.add(new Target("tool_call_log", "started_at < ?", days(toolCallLogDays)));
        }
        return List.copyOf(t);
    }

    private static Duration days(int d) {
        return Duration.ofDays(Math.max(1, d));
    }

    @Scheduled(
        fixedDelayString = "${app.retention.purge.interval:6h}",
        initialDelayString = "${app.retention.purge.initial-delay:30m}"
    )
    public void purge() {
        if (!enabled) {
            return;
        }
        jobLock.tryRunExclusive(LOCK_NAME, this::purgeExclusively);
    }

    void purgeExclusively() {
        Instant now = clock.instant();
        Instant deadline = now.plusSeconds(maxTickSeconds);
        List<String> schemas = new ArrayList<>();
        schemaIterator.forEachActiveTenantSchema(schemas::add);
        int n = schemas.size();
        if (n == 0) {
            return;
        }
        // Rotate the start so a tenant with a big backlog early in the order cannot starve later ones.
        int start = Math.floorMod(cursor.get(), n);
        int done = 0;
        for (; done < n; done++) {
            if (Thread.currentThread().isInterrupted() || clock.instant().isAfter(deadline)) {
                break;
            }
            String schema = schemas.get((start + done) % n);
            if (!SAFE_SCHEMA.matcher(schema).matches()) {
                log.warn("Retention purge skipping tenant schema with unexpected name '{}'", schema);
                continue;
            }
            for (Target target : targets) {
                if (Thread.currentThread().isInterrupted() || clock.instant().isAfter(deadline)) {
                    break;
                }
                try {
                    int rows = dryRun ? countTable(schema, target, now) : purgeTable(schema, target, now, deadline);
                    if (rows > 0) {
                        log.info("Retention purge {} {} row(s) {} {}.{} older than {} day(s)",
                            dryRun ? "DRY-RUN would remove" : "removed", rows, "from",
                            schema, target.table(), target.window().toDays());
                    }
                } catch (RuntimeException e) {
                    log.warn("Retention purge failed for {}.{}", schema, target.table(), e);
                }
            }
        }
        cursor.set((start + done) % n);
        if (done < n) {
            log.warn("Retention purge tick ended early (budget {}s or interrupt): deferred {} of {} tenant(s) to the next tick",
                maxTickSeconds, n - done, n);
        }
    }

    private int countTable(String schema, Target target, Instant now) {
        Timestamp cutoff = Timestamp.from(now.minus(target.window()));
        Integer c = jdbc.queryForObject(target.countSql(schema, batchSize * maxBatchesPerTable), Integer.class, cutoff);
        return c == null ? 0 : c;
    }

    private int purgeTable(String schema, Target target, Instant now, Instant deadline) {
        Timestamp cutoff = Timestamp.from(now.minus(target.window()));
        String sql = target.deleteSql(schema, batchSize);
        int total = 0;
        for (int i = 0; i < maxBatchesPerTable; i++) {
            int n = jdbc.update(sql, cutoff);
            total += n;
            if (n < batchSize || clock.instant().isAfter(deadline) || Thread.currentThread().isInterrupted()) {
                break;
            }
            if (pauseMs > 0) {
                pause.accept(pauseMs);
            }
        }
        return total;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
