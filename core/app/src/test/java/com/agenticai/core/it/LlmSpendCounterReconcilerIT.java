package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.chat.LlmSpendCounterReconciler;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link LlmSpendCounterReconciler} on a real Postgres: it corrects a drifted counter (current and
 * previous month), leaves a tenant without rows alone, and, the point of the class, loses no
 * concurrent increment while it runs against 8 writers.
 */
@Tag("integration")
class LlmSpendCounterReconcilerIT extends AbstractContextIT {

    private static final String SCHEMA = "tenant_it_spend_reconcile";

    @Autowired private TenantRepository tenants;
    @Autowired private TenantMigrationService migrations;
    @Autowired private LlmCallLogService callLog;
    @Autowired private LlmSpendCounterReconciler reconciler;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;

    private final LocalDate month =
            Instant.now().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    private final LocalDate previousMonth = month.minusMonths(1);

    @BeforeEach
    void tenantSchema() {
        if (tenants.findBySchemaName(SCHEMA).isEmpty()) {
            Tenant tenant =
                    new Tenant(
                            SCHEMA,
                            TenantPlan.PREMIUM,
                            SCHEMA,
                            "agentic-ai",
                            AccountType.B2B,
                            ExperienceMode.SIMPLE);
            tenant.markActive();
            tenants.save(tenant);
            assertThat(migrations.migrateAllActiveTenants()).as("schemas that failed").isEmpty();
        }
        jdbc.execute("TRUNCATE " + SCHEMA + ".llm_call_log");
        jdbc.execute("TRUNCATE " + SCHEMA + ".llm_spend_month");
        TenantContext.set(TenantContext.of(SCHEMA, "it-user", Set.of()));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** Rows as an older image writes them: a log row, no counter update. */
    private void insertUncountedRows(int n, String cost, Instant startedAt) {
        jdbc.update(
                "INSERT INTO "
                        + SCHEMA
                        + ".llm_call_log (id, provider_name, model, estimated_cost_usd, success,"
                        + " latency_ms, started_at) SELECT gen_random_uuid(), 'OPENAI', 'm',"
                        + " ?::numeric, true, 1, ? FROM generate_series(1, ?)",
                cost,
                Timestamp.from(startedAt),
                n);
    }

    private void setCounter(LocalDate m, String value) {
        jdbc.update(
                "INSERT INTO "
                        + SCHEMA
                        + ".llm_spend_month (month_start, cost_usd) VALUES (?, ?::numeric)"
                        + " ON CONFLICT (month_start) DO UPDATE SET cost_usd = EXCLUDED.cost_usd",
                java.sql.Date.valueOf(m),
                value);
    }

    private BigDecimal counter(LocalDate m) {
        List<BigDecimal> rows =
                jdbc.queryForList(
                        "SELECT cost_usd FROM " + SCHEMA + ".llm_spend_month WHERE month_start = ?",
                        BigDecimal.class,
                        java.sql.Date.valueOf(m));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BigDecimal sum(LocalDate m) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(estimated_cost_usd), 0) FROM "
                        + SCHEMA
                        + ".llm_call_log WHERE started_at >= ? AND started_at < ?",
                BigDecimal.class,
                Timestamp.from(m.atStartOfDay(ZoneOffset.UTC).toInstant()),
                Timestamp.from(m.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    private void recordCall() {
        callLog.record(
                null,
                "s-" + UUID.randomUUID(),
                ProviderName.OPENAI,
                "gpt-4o-mini",
                1000L,
                500L,
                1500L,
                true,
                null,
                null,
                null,
                null,
                10L,
                Instant.now(),
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void driftedCounterIsCorrectedAndASecondRunChangesNothing() {
        insertUncountedRows(10, "0.5", Instant.now());
        setCounter(month, "3.0"); // an older pod logged 2.0 the counter never saw
        double before =
                meterRegistry
                        .counter("llm.spend.counter.drift.usd", "outcome", "corrected")
                        .count();

        BigDecimal drift = reconciler.reconcileCurrentTenant();

        assertThat(drift).isEqualByComparingTo("2.0");
        assertThat(counter(month)).isEqualByComparingTo("5.0").isEqualByComparingTo(sum(month));
        assertThat(
                        meterRegistry
                                        .counter(
                                                "llm.spend.counter.drift.usd",
                                                "outcome",
                                                "corrected")
                                        .count()
                                - before)
                .isEqualTo(2.0);

        String xmin = xmin();
        assertThat(reconciler.reconcileCurrentTenant()).isEqualByComparingTo("0");
        assertThat(xmin()).as("an in-sync counter is not rewritten").isEqualTo(xmin);
    }

    @Test
    void anOvercountedCounterIsCorrectedDownAndAMissingRowIsCreated() {
        insertUncountedRows(4, "0.25", Instant.now());
        setCounter(month, "50");
        reconciler.reconcileCurrentTenant();
        assertThat(counter(month)).isEqualByComparingTo("1.0");

        jdbc.execute("TRUNCATE " + SCHEMA + ".llm_spend_month");
        reconciler.reconcileCurrentTenant();
        assertThat(counter(month)).isEqualByComparingTo("1.0");
    }

    @Test
    void previousMonthIsCorrectedAndOlderMonthsAreLeftAlone() {
        Instant inPreviousMonth =
                previousMonth.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
        Instant twoMonthsAgo =
                previousMonth
                        .minusMonths(1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .plusSeconds(3600);
        insertUncountedRows(20, "0.1", inPreviousMonth);
        insertUncountedRows(5, "7", twoMonthsAgo);
        setCounter(previousMonth, "0.5");
        setCounter(previousMonth.minusMonths(1), "1");

        reconciler.reconcileCurrentTenant();

        assertThat(counter(previousMonth)).isEqualByComparingTo("2.0");
        assertThat(counter(previousMonth.minusMonths(1)))
                .as("not in the window")
                .isEqualByComparingTo("1");
        assertThat(counter(month)).as("no rows this month, no row created").isNull();
    }

    @Test
    void aTenantWithNoRowsIsUntouched() {
        assertThat(reconciler.reconcileCurrentTenant()).isEqualByComparingTo("0");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM " + SCHEMA + ".llm_spend_month", Long.class))
                .isZero();
    }

    @Test
    void theScheduledPassFindsTheTenantThroughTheIteratorAndCorrectsIt() {
        insertUncountedRows(6, "1", Instant.now());
        setCounter(month, "1");
        TenantContext.clear();

        reconciler.runPass();

        assertThat(counter(month)).isEqualByComparingTo("6");
    }

    @Test
    void noIncrementIsLostWhileTheReconcilerRunsAgainstEightWriters() throws Exception {
        int threads = 8;
        int callsPerThread = 120;
        int rounds = 3;
        AtomicInteger reconcilePasses = new AtomicInteger();
        for (int round = 0; round < rounds; round++) {
            jdbc.execute("TRUNCATE " + SCHEMA + ".llm_call_log");
            jdbc.execute("TRUNCATE " + SCHEMA + ".llm_spend_month");
            // Start with real drift so the reconciler has something to write while writers run.
            insertUncountedRows(50, "0.01", Instant.now());
            setCounter(month, "0.1");

            ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
            CountDownLatch go = new CountDownLatch(1);
            AtomicBoolean writersDone = new AtomicBoolean();
            List<Future<?>> writers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                writers.add(
                        pool.submit(
                                () -> {
                                    TenantContext.set(
                                            TenantContext.of(SCHEMA, "it-user", Set.of()));
                                    try {
                                        go.await();
                                        for (int i = 0; i < callsPerThread; i++) {
                                            recordCall();
                                        }
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    } finally {
                                        TenantContext.clear();
                                    }
                                    return null;
                                }));
            }
            Future<?> reconciling =
                    pool.submit(
                            () -> {
                                TenantContext.set(TenantContext.of(SCHEMA, "it-user", Set.of()));
                                try {
                                    go.await();
                                    while (!writersDone.get()) {
                                        reconciler.reconcileCurrentTenant();
                                        reconcilePasses.incrementAndGet();
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    TenantContext.clear();
                                }
                                return null;
                            });
            go.countDown();
            for (Future<?> f : writers) {
                f.get();
            }
            writersDone.set(true);
            reconciling.get();
            pool.shutdown();

            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM " + SCHEMA + ".llm_call_log", Long.class))
                    .isEqualTo(50L + threads * callsPerThread);
            // No final reconcile: a lost increment must still be visible here.
            assertThat(counter(month))
                    .as("round %d: counter equals SUM with no reconcile after the writers", round)
                    .isEqualByComparingTo(sum(month));
        }
        System.out.println(
                "RECONCILE-IT reconcile passes during writers: " + reconcilePasses.get());
    }

    private String xmin() {
        return jdbc.queryForObject(
                "SELECT xmin::text FROM " + SCHEMA + ".llm_spend_month WHERE month_start = ?",
                String.class,
                java.sql.Date.valueOf(month));
    }
}
