package com.agenticai.core.chat;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;

import io.micrometer.core.instrument.MeterRegistry;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-heals the {@code llm_spend_month} counter (V481) against its source of truth, {@code
 * llm_call_log}. The counter is bumped by {@link LlmSpendCounterWriter} in the same transaction as
 * the call-log insert, so it is exact for every writer that runs this code. It drifts below the
 * true SUM when a pod on an older image (one that inserts call-log rows but does not know the
 * counter) logs calls during a rolling deploy; the budget cap reads the counter, so an undercount
 * lets a tenant overshoot its cap. Measured on the stage: 4.050 against a true 4.269 after
 * switching images back and forth. This job recomputes the current and the previous UTC month for
 * each ACTIVE tenant and corrects the counter.
 *
 * <p><b>Why the correction cannot lose a concurrent increment.</b> A naive single {@code UPDATE ...
 * SET cost_usd = (SELECT SUM(...))} can: the SUM subquery runs on the statement's snapshot, a
 * writer that commits its log row and counter increment after that snapshot but before the UPDATE
 * takes the row lock has its increment overwritten by a SUM that never saw its log row. So each
 * (tenant, month) is corrected in ONE transaction, in this order:
 *
 * <ol>
 *   <li>{@code SELECT ... FOR UPDATE} the counter row (creating it with {@code INSERT ... ON
 *       CONFLICT DO NOTHING} first when it is missing and the month has cost). From here every
 *       writer's {@code UPDATE llm_spend_month} on that row blocks.
 *   <li>THEN compute the {@code SUM} in a separate statement. This is a new READ COMMITTED
 *       snapshot, taken after the lock was granted, so it sees every row of every writer that had
 *       already updated the counter and committed (the lock is only granted after such a writer
 *       committed).
 *   <li>{@code UPDATE} the counter to that SUM and commit, releasing the lock.
 * </ol>
 *
 * A writer that has inserted its log row but not yet incremented the counter is invisible to our
 * SUM (uncommitted) and blocks on the row lock; when we commit, its {@code cost_usd = cost_usd + c}
 * re-reads the corrected value and adds its cost, which is the right answer. A writer that cannot
 * find the row creates it from the SUM of its own snapshot ({@link LlmSpendCounterWriter}); that
 * path and our insert-if-missing both use {@code ON CONFLICT}, so they serialise on the primary
 * key. The counter is therefore never lower than the committed SUM except for a transient window of
 * at most one in-flight writer, which is the same window the counter always had.
 *
 * <p>A tenant with no rows in the month and no counter row is not touched (no row is created for
 * it). Work per tick is capped ({@code reconcile-max-tenants-per-tick} and a wall-clock budget),
 * continuing from where the previous tick stopped, so a large tenant count cannot stall the sweep.
 *
 * <p>Scheduling: a fixed one-minute tick decides whether a pass is due, so the interval can be a
 * plain duration property that may also be {@code 0}/{@code off} (Spring cannot express "disabled"
 * for {@code fixedDelayString}). The due time is per JVM; the advisory lock makes one replica run a
 * pass at a time, and an extra pass on another replica is only redundant work. The job runs in the
 * {@code billing} pool.
 */
@Component
public class LlmSpendCounterReconciler {

    private static final Logger log = LoggerFactory.getLogger(LlmSpendCounterReconciler.class);

    static final String JOB_LOCK_NAME = "llm-spend-counter-reconcile";
    static final String DRIFT_METRIC = "llm.spend.counter.drift.usd";

    private static final String LOCK_ROW_SQL =
            "SELECT cost_usd FROM llm_spend_month WHERE month_start = ? FOR UPDATE";
    private static final String INSERT_ROW_SQL =
            "INSERT INTO llm_spend_month (month_start, cost_usd, updated_at) VALUES (?, 0, now())"
                    + " ON CONFLICT (month_start) DO NOTHING";
    private static final String SUM_SQL =
            "SELECT COALESCE(SUM(estimated_cost_usd), 0) FROM llm_call_log WHERE started_at >= ?"
                    + " AND started_at < ?";
    private static final String UPDATE_SQL =
            "UPDATE llm_spend_month SET cost_usd = ?, updated_at = now() WHERE month_start = ?";

    private final TenantSchemaIterator tenantSchemaIterator;
    private final PgAdvisoryJobLock jobLock;
    private final TransactionTemplate transactionTemplate;
    private final Duration interval;
    private final int maxTenantsPerTick;
    private final long maxTickMillis;
    private final Clock clock;
    private volatile MeterRegistry meterRegistry;

    @PersistenceContext private EntityManager entityManager;

    /** Index (in iteration order) of the first tenant the next tick processes; 0 = new pass. */
    private final AtomicInteger cursor = new AtomicInteger();

    private volatile long nextPassDueMillis;

    @Autowired
    public LlmSpendCounterReconciler(
            TenantSchemaIterator tenantSchemaIterator,
            PgAdvisoryJobLock jobLock,
            PlatformTransactionManager transactionManager,
            @Value("${app.llm.spend-counter.reconcile-interval:1h}") String interval,
            @Value("${app.llm.spend-counter.reconcile-max-tenants-per-tick:200}")
                    int maxTenantsPerTick,
            @Value("${app.llm.spend-counter.reconcile-max-tick-seconds:30}") long maxTickSeconds) {
        this(
                tenantSchemaIterator,
                jobLock,
                new TransactionTemplate(transactionManager),
                parseInterval(interval),
                maxTenantsPerTick,
                Math.max(1, maxTickSeconds) * 1000L,
                Clock.systemUTC());
    }

    LlmSpendCounterReconciler(
            TenantSchemaIterator tenantSchemaIterator,
            PgAdvisoryJobLock jobLock,
            TransactionTemplate transactionTemplate,
            Duration interval,
            int maxTenantsPerTick,
            long maxTickMillis,
            Clock clock) {
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.jobLock = jobLock;
        this.transactionTemplate = transactionTemplate;
        this.interval = interval;
        this.maxTenantsPerTick = Math.max(1, maxTenantsPerTick);
        this.maxTickMillis = maxTickMillis;
        this.clock = clock;
    }

    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** Test seam: the entity manager the production bean gets by injection. */
    void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Parses the interval property. {@code 0}, {@code off}, {@code false}, {@code disabled}, {@code
     * none}, blank and anything unparsable or non-positive disable the job ({@link Duration#ZERO});
     * otherwise a duration such as {@code 1h}, {@code 30m}, {@code 90s} or ISO {@code PT1H}. A bare
     * number is seconds.
     */
    static Duration parseInterval(String raw) {
        if (raw == null) {
            return Duration.ZERO;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()
                || s.equals("off")
                || s.equals("false")
                || s.equals("disabled")
                || s.equals("none")) {
            return Duration.ZERO;
        }
        try {
            Duration d;
            if (s.startsWith("p")) {
                d = Duration.parse(s);
            } else if (s.matches("\\d+")) {
                d = Duration.ofSeconds(Long.parseLong(s));
            } else if (s.matches("\\d+(ms|s|m|h|d)")) {
                String unit = s.replaceAll("\\d+", "");
                long n = Long.parseLong(s.substring(0, s.length() - unit.length()));
                d =
                        switch (unit) {
                            case "ms" -> Duration.ofMillis(n);
                            case "s" -> Duration.ofSeconds(n);
                            case "m" -> Duration.ofMinutes(n);
                            case "h" -> Duration.ofHours(n);
                            default -> Duration.ofDays(n);
                        };
            } else {
                log.warn(
                        "Unparsable app.llm.spend-counter.reconcile-interval '{}' - disabled", raw);
                return Duration.ZERO;
            }
            return d.isNegative() ? Duration.ZERO : d;
        } catch (RuntimeException e) {
            log.warn("Unparsable app.llm.spend-counter.reconcile-interval '{}' - disabled", raw);
            return Duration.ZERO;
        }
    }

    boolean enabled() {
        return !interval.isZero();
    }

    /** True when a pass should start now: enabled and (mid-pass, or the interval has elapsed). */
    boolean due() {
        if (!enabled()) {
            return false;
        }
        return cursor.get() > 0 || clock.millis() >= nextPassDueMillis;
    }

    @Scheduled(
            fixedDelayString = "${app.llm.spend-counter.reconcile-tick-ms:60000}",
            initialDelayString = "${app.llm.spend-counter.reconcile-initial-delay-ms:120000}")
    public void tick() {
        if (!due()) {
            return;
        }
        try {
            jobLock.tryRunExclusive(JOB_LOCK_NAME, this::runPass);
        } catch (RuntimeException e) {
            log.error("Spend counter reconcile tick failed", e);
        }
    }

    /** One capped slice of a pass over the tenants. Never throws. */
    public void runPass() {
        try {
            long deadline = clock.millis() + maxTickMillis;
            int start = cursor.get();
            AtomicInteger index = new AtomicInteger();
            AtomicInteger processed = new AtomicInteger();
            AtomicInteger corrected = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            AtomicInteger firstUnprocessed = new AtomicInteger(-1);
            BigDecimal[] largest = {BigDecimal.ZERO};
            BigDecimal[] total = {BigDecimal.ZERO};
            tenantSchemaIterator.forEachActiveTenantSchema(
                    schema -> {
                        int i = index.getAndIncrement();
                        if (i < start) {
                            return;
                        }
                        if (processed.get() >= maxTenantsPerTick || clock.millis() > deadline) {
                            firstUnprocessed.compareAndSet(-1, i);
                            return;
                        }
                        processed.incrementAndGet();
                        try {
                            BigDecimal drift = reconcileCurrentTenant();
                            if (drift.signum() != 0) {
                                corrected.incrementAndGet();
                                total[0] = total[0].add(drift);
                                if (drift.compareTo(largest[0]) > 0) {
                                    largest[0] = drift;
                                }
                            }
                        } catch (RuntimeException e) {
                            failed.incrementAndGet();
                            log.debug("Spend counter reconcile failed for '{}'", schema, e);
                        }
                    });
            int next = firstUnprocessed.get();
            if (next < 0) {
                cursor.set(0);
                nextPassDueMillis = clock.millis() + interval.toMillis();
            } else {
                cursor.set(next);
            }
            if (corrected.get() > 0 || failed.get() > 0) {
                log.warn(
                        "Spend counter reconcile: corrected {} of {} tenant(s) ({} failed),"
                                + " largest absolute drift ${}, total absolute drift ${}{}",
                        corrected.get(),
                        processed.get(),
                        failed.get(),
                        largest[0].toPlainString(),
                        total[0].toPlainString(),
                        next < 0 ? "" : " (pass continues next tick)");
            } else {
                log.debug(
                        "Spend counter reconcile: {} tenant(s) in sync{}",
                        processed.get(),
                        next < 0 ? "" : " (pass continues next tick)");
            }
        } catch (RuntimeException e) {
            // e.g. the tenant list could not be read; retry next tick, never throw out of the job
            log.error("Spend counter reconcile pass failed", e);
        }
    }

    /**
     * Reconciles the current and previous UTC month of the tenant in the current {@code
     * TenantContext}. One transaction per month. Returns the sum of absolute drifts corrected (zero
     * when in sync). Throws if a database error occurs (the caller isolates tenants).
     */
    public BigDecimal reconcileCurrentTenant() {
        LocalDate current = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
        BigDecimal abs = BigDecimal.ZERO;
        for (LocalDate month : new LocalDate[] {current.minusMonths(1), current}) {
            BigDecimal drift =
                    transactionTemplate.execute(status -> reconcileMonthInTransaction(month));
            BigDecimal d = drift == null ? BigDecimal.ZERO : drift.abs();
            record(d);
            abs = abs.add(d);
        }
        return abs;
    }

    private void record(BigDecimal absDrift) {
        MeterRegistry registry = meterRegistry;
        if (registry == null) {
            return;
        }
        registry.counter(DRIFT_METRIC, "outcome", absDrift.signum() == 0 ? "ok" : "corrected")
                .increment(absDrift.doubleValue());
    }

    private BigDecimal reconcileMonthInTransaction(LocalDate month) {
        BigDecimal[] drift = {BigDecimal.ZERO};
        entityManager
                .unwrap(Session.class)
                .doWork(connection -> drift[0] = reconcileMonth(connection, month));
        return drift[0];
    }

    /**
     * The locking protocol from the class javadoc, on a connection that is inside a transaction.
     * Returns {@code corrected - previous} (signed), zero if nothing changed.
     */
    static BigDecimal reconcileMonth(Connection connection, LocalDate month) throws SQLException {
        Timestamp from = Timestamp.from(month.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp to = Timestamp.from(month.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        BigDecimal counter = lockRow(connection, month);
        if (counter == null) {
            // No counter row. With no cost in the month either, leave the tenant untouched
            // (a writer creating the row concurrently seeds it from its own SUM).
            if (sum(connection, from, to).signum() == 0) {
                return BigDecimal.ZERO;
            }
            try (PreparedStatement ps = connection.prepareStatement(INSERT_ROW_SQL)) {
                ps.setDate(1, java.sql.Date.valueOf(month));
                ps.executeUpdate();
            }
            counter = lockRow(connection, month);
            if (counter == null) {
                return BigDecimal.ZERO; // cannot happen: just inserted or created by a writer
            }
        }
        // Separate statement, after the lock is held: a fresh READ COMMITTED snapshot.
        BigDecimal truth = sum(connection, from, to);
        BigDecimal drift = truth.subtract(counter);
        if (drift.signum() == 0) {
            return BigDecimal.ZERO;
        }
        try (PreparedStatement ps = connection.prepareStatement(UPDATE_SQL)) {
            ps.setBigDecimal(1, truth);
            ps.setDate(2, java.sql.Date.valueOf(month));
            ps.executeUpdate();
        }
        return drift;
    }

    /** Locks the counter row; null when absent. */
    private static BigDecimal lockRow(Connection connection, LocalDate month) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(LOCK_ROW_SQL)) {
            ps.setDate(1, java.sql.Date.valueOf(month));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : null;
            }
        }
    }

    private static BigDecimal sum(Connection connection, Timestamp from, Timestamp to)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SUM_SQL)) {
            ps.setTimestamp(1, from);
            ps.setTimestamp(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }
}
