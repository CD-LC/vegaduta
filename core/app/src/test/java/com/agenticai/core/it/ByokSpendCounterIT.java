package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.chat.LlmCallLogRepository;
import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.chat.gateway.LlmGatewayBudgetService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

/**
 * The BYOK budget counter (V481 {@code llm_spend_month}) on a real Postgres: it must equal the SUM
 * it replaced after concurrent writers, after the backfill, and after the seed path; and the budget
 * check must cost one statement.
 */
@Tag("integration")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ByokSpendCounterIT extends AbstractContextIT {

    private static final String SCHEMA = "tenant_it_byok_budget";

    @Autowired private TenantRepository tenants;
    @Autowired private TenantMigrationService migrations;
    @Autowired private LlmCallLogService callLog;
    @Autowired private LlmCallLogRepository repository;
    @Autowired private LlmGatewayBudgetService budget;
    @Autowired private com.agenticai.core.chat.LlmSpendCounterSchemaCheck schemaCheck;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private final LocalDate month =
            Instant.now().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);

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

    private void recordCall(Instant startedAt) {
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
                startedAt,
                null,
                null,
                null,
                null,
                null);
    }

    private BigDecimal sum() {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(estimated_cost_usd), 0) FROM "
                        + SCHEMA
                        + ".llm_call_log WHERE started_at >= ? AND started_at < ?",
                BigDecimal.class,
                java.sql.Timestamp.from(month.atStartOfDay(ZoneOffset.UTC).toInstant()),
                java.sql.Timestamp.from(
                        month.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    private BigDecimal counter() {
        List<BigDecimal> rows =
                jdbc.queryForList(
                        "SELECT cost_usd FROM " + SCHEMA + ".llm_spend_month WHERE month_start = ?",
                        BigDecimal.class,
                        java.sql.Date.valueOf(month));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** A failed call with no usage data: priced null, so it must not touch the counter. */
    private void recordFailedCallWithNoUsage(Instant startedAt) {
        callLog.record(
                null,
                "s-" + UUID.randomUUID(),
                ProviderName.OPENAI,
                "gpt-4o-mini",
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                10L,
                startedAt,
                null,
                null,
                null,
                null,
                null);
    }

    private Long callLogRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + SCHEMA + ".llm_call_log", Long.class);
    }

    @Test
    void counterWriteFailureKeepsTheLogRowAndDoesNotThrow() {
        // A tenant whose V481 has not been applied: the counter table does not exist.
        jdbc.execute("ALTER TABLE " + SCHEMA + ".llm_spend_month RENAME TO llm_spend_month_gone");
        try {
            // Plain call, and a call inside the caller's own transaction (the harder case: a
            // Postgres error there used to abort the whole transaction).
            assertThatCode(() -> recordCall(Instant.now())).doesNotThrowAnyException();
            assertThatCode(() -> tx.executeWithoutResult(status -> recordCall(Instant.now())))
                    .doesNotThrowAnyException();
            assertThat(callLogRows()).as("log rows written").isEqualTo(2);
            // Read side fails open with the table missing.
            assertThatCode(() -> budget.assertWithinBudget(SCHEMA)).doesNotThrowAnyException();
        } finally {
            jdbc.execute(
                    "ALTER TABLE " + SCHEMA + ".llm_spend_month_gone RENAME TO llm_spend_month");
        }
    }

    /**
     * A tenant whose V481 is not applied: a budget check inside a transaction must neither throw
     * nor poison it (the old code marked the shared transaction rollback-only, so the commit threw
     * UnexpectedRollbackException out of resolveClient and every BYOK turn failed with 500).
     */
    @Test
    void missingCounterTableDoesNotPoisonTheSurroundingTransaction() {
        jdbc.execute("ALTER TABLE " + SCHEMA + ".llm_spend_month RENAME TO llm_spend_month_gone");
        try {
            assertThatCode(
                            () ->
                                    tx.executeWithoutResult(
                                            status -> {
                                                budget.assertWithinBudget(
                                                        SCHEMA, TenantPlan.PREMIUM);
                                                // the transaction is still usable afterwards
                                                assertThat(callLogRows()).isZero();
                                            }))
                    .doesNotThrowAnyException();
            // and standalone (the service's own transaction)
            assertThatCode(() -> budget.assertWithinBudget(SCHEMA, TenantPlan.PREMIUM))
                    .doesNotThrowAnyException();
        } finally {
            jdbc.execute(
                    "ALTER TABLE " + SCHEMA + ".llm_spend_month_gone RENAME TO llm_spend_month");
        }
    }

    @Test
    void missingCounterTableStillEnforcesTheCapFromTheSum() {
        jdbc.update(
                "INSERT INTO "
                        + SCHEMA
                        + ".llm_call_log (id, provider_name, model, estimated_cost_usd, success,"
                        + " latency_ms, started_at) VALUES (gen_random_uuid(), 'OPENAI', 'm', 250,"
                        + " true, 1, now())");
        jdbc.execute("ALTER TABLE " + SCHEMA + ".llm_spend_month RENAME TO llm_spend_month_gone");
        try {
            assertThatThrownBy(() -> budget.assertWithinBudget(SCHEMA, TenantPlan.PREMIUM))
                    .isInstanceOf(
                            com.agenticai.core.chat.gateway.LlmGatewayLimitExceededException.class);
        } finally {
            jdbc.execute(
                    "ALTER TABLE " + SCHEMA + ".llm_spend_month_gone RENAME TO llm_spend_month");
        }
    }

    @Test
    void schemaCheckReportsActiveTenantsWithoutTheCounterTable() {
        assertThat(schemaCheck.check()).doesNotContain(SCHEMA);
        jdbc.execute("ALTER TABLE " + SCHEMA + ".llm_spend_month RENAME TO llm_spend_month_gone");
        try {
            assertThat(schemaCheck.check()).contains(SCHEMA);
        } finally {
            jdbc.execute(
                    "ALTER TABLE " + SCHEMA + ".llm_spend_month_gone RENAME TO llm_spend_month");
        }
    }

    @Test
    void rolledBackCallerTransactionLeavesNeitherLogRowNorCounter() {
        recordCall(Instant.now());
        BigDecimal before = counter();
        assertThat(before).isPositive();
        Long rowsBefore = callLogRows();

        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            recordCall(Instant.now());
                                            throw new IllegalStateException("caller fails");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(callLogRows()).isEqualTo(rowsBefore);
        assertThat(counter()).isEqualByComparingTo(before);
        assertThat(counter()).isEqualByComparingTo(sum());
    }

    @Test
    void callInsideCallerTransactionKeepsCounterEqualToSum() {
        tx.executeWithoutResult(
                status -> {
                    recordCall(Instant.now());
                });
        assertThat(counter()).isEqualByComparingTo(sum());
        assertThat(counter()).isPositive();
    }

    @Test
    void zeroCostCallDoesNotTouchTheCounterRow() {
        recordCall(Instant.now());
        String xminBefore =
                jdbc.queryForObject(
                        "SELECT xmin::text FROM " + SCHEMA + ".llm_spend_month", String.class);
        BigDecimal valueBefore = counter();

        recordFailedCallWithNoUsage(Instant.now());

        assertThat(callLogRows()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT xmin::text FROM " + SCHEMA + ".llm_spend_month",
                                String.class))
                .as("no new tuple version")
                .isEqualTo(xminBefore);
        assertThat(counter()).isEqualByComparingTo(valueBefore);
        assertThat(counter()).isEqualByComparingTo(sum());
    }

    @Test
    void counterEqualsSumAfterConcurrentCallsAcrossFirstOfMonthRace() throws Exception {
        int threads = 8;
        int callsPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(
                    pool.submit(
                            () -> {
                                TenantContext.set(TenantContext.of(SCHEMA, "it-user", Set.of()));
                                try {
                                    go.await();
                                    for (int i = 0; i < callsPerThread; i++) {
                                        recordCall(Instant.now());
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    TenantContext.clear();
                                }
                                return null;
                            }));
        }
        // Table is empty here: every thread's first call races to create the month row.
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        Long rows =
                jdbc.queryForObject("SELECT COUNT(*) FROM " + SCHEMA + ".llm_call_log", Long.class);
        assertThat(rows).isEqualTo(threads * callsPerThread);
        assertThat(sum()).as("priced calls").isPositive();
        assertThat(counter()).isEqualByComparingTo(sum());
    }

    @Test
    void seedPathPicksUpRowsWrittenBeforeTheCounterExisted() {
        // Rows from an older replica that does not know the counter yet.
        jdbc.update(
                "INSERT INTO "
                        + SCHEMA
                        + ".llm_call_log (id, provider_name, model, estimated_cost_usd, success,"
                        + " latency_ms, started_at) SELECT gen_random_uuid(), 'OPENAI', 'm', 0.5,"
                        + " true, 1, now() FROM generate_series(1, 10)");
        assertThat(counter()).isNull();

        recordCall(Instant.now());

        assertThat(counter()).isEqualByComparingTo(sum());
        assertThat(sum()).isGreaterThan(new BigDecimal("5"));
    }

    @Test
    void backfillInTheMigrationEqualsTheSum() throws Exception {
        jdbc.update(
                "INSERT INTO "
                        + SCHEMA
                        + ".llm_call_log (id, provider_name, model, estimated_cost_usd, success,"
                        + " latency_ms, started_at) SELECT gen_random_uuid(), 'OPENAI', 'm', 0.25,"
                        + " true, 1, now() FROM generate_series(1, 40)");
        // Last month's rows must not leak into this month's counter.
        jdbc.update(
                "INSERT INTO "
                        + SCHEMA
                        + ".llm_call_log (id, provider_name, model, estimated_cost_usd, success,"
                        + " latency_ms, started_at) VALUES (gen_random_uuid(), 'OPENAI', 'm', 99,"
                        + " true, 1, ?)",
                java.sql.Timestamp.from(
                        month.atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(60)));
        String ddl =
                new String(
                        new ClassPathResource(
                                        "db/migration/tenant/V481__llm_spend_month_counter.sql")
                                .getInputStream()
                                .readAllBytes(),
                        StandardCharsets.UTF_8);
        tx.executeWithoutResult(
                status -> {
                    jdbc.execute("SET LOCAL search_path TO " + SCHEMA);
                    jdbc.execute(ddl);
                });
        assertThat(counter()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(counter()).isEqualByComparingTo(sum());
    }

    @Test
    void budgetCheckIsOneStatementAndReadsTheCounter() {
        recordCall(Instant.now());
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        stats.clear();
        budget.assertWithinBudget(SCHEMA);
        long connections = stats.getConnectCount();
        long statements = stats.getPrepareStatementCount();
        System.out.println(
                "BYOK-BUDGET assertWithinBudget (counter): connections="
                        + connections
                        + " statements="
                        + statements);

        stats.clear();
        tx.executeWithoutResult(
                status -> repository.costSince(month.atStartOfDay(ZoneOffset.UTC).toInstant()));
        System.out.println(
                "BYOK-BUDGET old SUM path: connections="
                        + stats.getConnectCount()
                        + " statements="
                        + stats.getPrepareStatementCount());

        assertThat(statements).as("one SELECT on the counter row").isEqualTo(1);
        assertThat(connections).isEqualTo(1);
    }

    @Test
    @EnabledIfSystemProperty(named = "byok.counter.benchmark", matches = "true")
    void readCostAtVolumeCounterVersusSum() {
        jdbc.update(
                "INSERT INTO "
                        + SCHEMA
                        + ".llm_call_log (id, provider_name, model, estimated_cost_usd, success,"
                        + " latency_ms, started_at) SELECT gen_random_uuid(), 'OPENAI', 'm', 0.001,"
                        + " true, 1, now() FROM generate_series(1, 300000)");
        jdbc.execute("ANALYZE " + SCHEMA + ".llm_call_log");
        recordCall(Instant.now());
        assertThat(counter()).isEqualByComparingTo(sum());

        Instant since = month.atStartOfDay(ZoneOffset.UTC).toInstant();
        for (int i = 0; i < 5; i++) {
            tx.executeWithoutResult(status -> repository.costSince(since));
            budget.assertWithinBudget(SCHEMA);
        }
        int n = 30;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            tx.executeWithoutResult(status -> repository.costSince(since));
        }
        long sumNs = (System.nanoTime() - t0) / n;
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            budget.assertWithinBudget(SCHEMA);
        }
        long counterNs = (System.nanoTime() - t0) / n;
        System.out.println(
                "BYOK-BUDGET 300k rows this month: SUM path "
                        + sumNs / 1000
                        + " us/turn, counter path "
                        + counterNs / 1000
                        + " us/turn");
    }
}
