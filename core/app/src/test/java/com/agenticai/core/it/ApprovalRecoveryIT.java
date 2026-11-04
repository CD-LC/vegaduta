package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The approval-recovery bookkeeping (tenant migration V675, {@code ApprovalRequestRecorder},
 * {@code ApprovalRequestRepository}) against a REAL Postgres, which the mocked-repository unit test
 * ({@code ApprovalRecoveryTest}) cannot prove: the backfill, the two JPQL queries, and the row lock
 * the claim / interrupt / finish transitions rely on.
 *
 * <p>No Spring context and no Testcontainers: needs a Postgres with pgvector and AGE reachable via
 * {@code -Dvegaduta.it.pg.url=...} (see {@link ExternalPostgres}); otherwise the class is skipped.
 * The recorder is package-private, so it is driven by reflection inside real
 * {@link TransactionTemplate} transactions (its {@code @Transactional} proxy is not needed for the
 * lock semantics under test, only an open transaction is).
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApprovalRecoveryIT {

    private static final String DATABASE = "approval_recovery_it";
    private static final String SCHEMA = "tenant_it_approval_rec";

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static EntityManagerFactory emf;
    private static ApprovalRequestRepository repo;
    private static Object recorder;

    private static final List<UUID> BACKFILL_IDS = new ArrayList<>();
    private static UUID backfillPending;
    private static UUID backfillApproved;
    private static UUID backfillRejected;
    private static UUID backfillFailed;
    private static UUID backfillApprovedNoDecidedAt;
    private static Instant backfillDecidedAt;
    private static Instant backfillCreatedAt;

    @BeforeAll
    static void createDatabase() {
        ExternalPostgres.requireOrSkip();
        dataSource = ExternalPostgres.freshDatabase(DATABASE, SCHEMA);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void close() {
        if (emf != null) {
            emf.close();
        }
    }

    // @BeforeAll only creates the database; test 1 migrates to V673, inserts legacy rows, then applies V675.
    @Test
    @Order(1)
    void backfillMarksPreExistingDecidedRowsCompletedAndLeavesPendingAlone() {
        // Start over from an empty schema: migrate up to V673, the last tenant migration before V675.
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        var before = org.flywaydb.core.Flyway.configure()
            .dataSource(dataSource)
            .locations(PlatformMigrations.TENANT)
            .schemas(SCHEMA)
            .createSchemas(true)
            .outOfOrder(true)
            .baselineOnMigrate(false)
            .target(MigrationVersion.fromVersion("673"))
            .load();
        before.migrate();
        assertThat(columnExists("execution_started_at")).as("column before V675").isFalse();

        backfillCreatedAt = Instant.now().minus(Duration.ofDays(30)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        backfillDecidedAt = backfillCreatedAt.plus(Duration.ofDays(1));
        backfillPending = insertLegacy("PENDING", null);
        backfillApproved = insertLegacy("APPROVED", backfillDecidedAt);
        backfillRejected = insertLegacy("REJECTED", backfillDecidedAt);
        backfillFailed = insertLegacy("EXECUTION_FAILED", backfillDecidedAt);
        backfillApprovedNoDecidedAt = insertLegacy("APPROVED", null);

        // Apply V675 (and anything after it) on the populated schema.
        PlatformMigrations.strictTenantFlyway(dataSource, SCHEMA).migrate();
        assertThat(columnExists("execution_started_at")).isTrue();
        assertThat(columnExists("execution_completed_at")).isTrue();

        assertThat(completedAt(backfillApproved)).as("approved legacy row").isEqualTo(backfillDecidedAt);
        assertThat(completedAt(backfillRejected)).as("rejected legacy row").isEqualTo(backfillDecidedAt);
        assertThat(completedAt(backfillFailed)).as("failed legacy row").isEqualTo(backfillDecidedAt);
        assertThat(completedAt(backfillApprovedNoDecidedAt)).as("no decided_at: falls back to created_at")
            .isEqualTo(backfillCreatedAt);
        assertThat(completedAt(backfillPending)).as("PENDING stays untouched").isNull();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM approval_request WHERE execution_started_at IS NOT NULL", Integer.class))
            .as("backfill never claims a row was started").isZero();
        BACKFILL_IDS.addAll(List.of(backfillPending, backfillApproved, backfillRejected, backfillFailed,
            backfillApprovedNoDecidedAt));
        wire();

        // The first recovery sweep after the upgrade must not see a year of old approvals.
        assertThat(repo.findApprovedNeverStarted(Instant.now().plusSeconds(60), PageRequest.of(0, 50)))
            .as("legacy rows are not 'never started'").isEmpty();
        assertThat(repo.findApprovedStartedNeverFinished(Instant.now().plusSeconds(60), PageRequest.of(0, 50)))
            .as("legacy rows are not 'started, never finished'").isEmpty();
    }

    @Test
    @Order(2)
    void partialIndexExistsAndServesTheUnfinishedApprovedQuery() {
        String def = jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND indexname = 'idx_approval_request_unfinished'",
            String.class, SCHEMA);
        assertThat(def).contains("decided_at").containsIgnoringCase("WHERE")
            .contains("execution_completed_at IS NULL").contains("'APPROVED'");
        assertThat(jdbc.queryForObject(
            "SELECT i.indpred IS NOT NULL FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ?"
                + " AND c.relname = 'idx_approval_request_unfinished'", Boolean.class, SCHEMA))
            .as("it is a partial index").isTrue();

        // With seq scans discouraged the planner must choose it for the sweep's predicate.
        String plan = jdbc.execute((java.sql.Connection c) -> {
            try (var st = c.createStatement()) {
                st.execute("SET enable_seqscan = off");
                var rs = st.executeQuery("EXPLAIN SELECT id FROM approval_request WHERE status = 'APPROVED'"
                    + " AND execution_completed_at IS NULL ORDER BY decided_at");
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    sb.append(rs.getString(1)).append('\n');
                }
                return sb.toString();
            }
        });
        assertThat(plan).contains("idx_approval_request_unfinished");
    }

    @Test
    @Order(3)
    void queriesReturnExactlyTheIntendedRows() {
        Instant old = Instant.now().minus(Duration.ofMinutes(30));
        Instant mid = Instant.now().minus(Duration.ofMinutes(20));
        Instant recent = Instant.now().minus(Duration.ofSeconds(5));
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));

        UUID neverStartedOld = approved(old, null, null);
        UUID neverStartedOlder = approved(old.minus(Duration.ofMinutes(10)), null, null);
        UUID neverStartedRecent = approved(recent, null, null);
        UUID startedOld = approved(old, mid, null);
        UUID startedOlder = approved(old.minus(Duration.ofMinutes(10)), old.minus(Duration.ofMinutes(9)), null);
        UUID startedRecent = approved(old, recent, null);
        UUID completed = approved(old, mid, mid.plusSeconds(1));
        UUID completedNeverStarted = approved(old, null, mid); // backfill shape
        UUID pending = status(ApprovalStatus.PENDING, null, null, null);
        UUID failedStartedUnfinished = status(ApprovalStatus.EXECUTION_FAILED, old, mid, null);
        UUID rejected = status(ApprovalStatus.REJECTED, old, null, null);
        UUID deadLettered = status(ApprovalStatus.DEAD_LETTERED, old, mid, null);

        List<ApprovalRequest> never = repo.findApprovedNeverStarted(cutoff, PageRequest.of(0, 100));
        assertThat(never.stream().map(ApprovalRequest::getId).toList())
            .as("never started, oldest first, recent and completed excluded")
            .containsExactly(neverStartedOlder, neverStartedOld);
        assertThat(never).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
            assertThat(r.getExecutionStartedAt()).isNull();
            assertThat(r.getExecutionCompletedAt()).isNull();
        });
        assertThat(repo.findApprovedNeverStarted(cutoff, PageRequest.of(0, 1)).stream().map(ApprovalRequest::getId))
            .as("page size is honoured, oldest first").containsExactly(neverStartedOlder);

        List<ApprovalRequest> unfinished = repo.findApprovedStartedNeverFinished(cutoff, PageRequest.of(0, 100));
        assertThat(unfinished.stream().map(ApprovalRequest::getId).toList())
            .as("started long ago, never finished, oldest first")
            .containsExactly(startedOlder, startedOld);

        List<UUID> everyoneElse = List.of(neverStartedRecent, startedRecent, completed, completedNeverStarted,
            pending, failedStartedUnfinished, rejected, deadLettered);
        assertThat(never.stream().map(ApprovalRequest::getId)).doesNotContainAnyElementsOf(everyoneElse);
        assertThat(unfinished.stream().map(ApprovalRequest::getId)).doesNotContainAnyElementsOf(everyoneElse);
    }

    @Test
    @Order(4)
    void twoConcurrentClaimsExactlyOneWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 25; round++) {
                UUID id = approved(Instant.now().minus(Duration.ofMinutes(10)), null, null);
                int callers = 8;
                CyclicBarrier barrier = new CyclicBarrier(callers);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < callers; i++) {
                    results.add(pool.submit(() -> {
                        ApprovalRequest mine = load(id); // each caller holds its own copy, as in production
                        barrier.await(10, TimeUnit.SECONDS);
                        return inTx(() -> claim(mine));
                    }));
                }
                int winners = 0;
                for (Future<Boolean> f : results) {
                    if (f.get(30, TimeUnit.SECONDS)) {
                        winners++;
                    }
                }
                assertThat(winners).as("round " + round + ": exactly one claimant").isEqualTo(1);
                assertThat(load(id).getExecutionStartedAt()).isNotNull();
                assertThat(load(id).getExecutionCompletedAt()).isNull();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Order(5)
    void aSecondClaimBlocksOnTheRowLockUntilTheFirstTransactionEnds() throws Exception {
        UUID id = approved(Instant.now().minus(Duration.ofMinutes(10)), null, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            // Callers hold a detached copy read BEFORE their transaction (as the gate and the sweep do): a copy
            // loaded inside the transaction would sit in its persistence context and mask the post-lock state.
            ApprovalRequest firstCopy = load(id);
            ApprovalRequest secondCopy = load(id);
            Future<Boolean> first = pool.submit(() -> inTx(() -> {
                boolean won = claim(firstCopy);
                firstHoldsLock.countDown();
                await(releaseFirst);
                return won;
            }));
            assertThat(firstHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> second = pool.submit(() -> inTx(() -> claim(secondCopy)));
            assertThatThrownBy(() -> second.get(1500, TimeUnit.MILLISECONDS))
                .as("the second claim must wait on SELECT ... FOR UPDATE, not run past it")
                .isInstanceOf(TimeoutException.class);
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = ?",
                Integer.class, DATABASE)).as("a backend is waiting on a lock").isGreaterThanOrEqualTo(1);

            releaseFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).as("loser sees the committed start and refuses").isFalse();
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @Order(6)
    void interruptedThenALateFinishedLeavesTheInterruptedOutcome() {
        UUID id = approved(Instant.now().minus(Duration.ofMinutes(30)), Instant.now().minus(Duration.ofMinutes(20)), null);
        assertThat(inTx(() -> interrupted(load(id), "process died"))).isTrue();
        ApprovalRequest afterInterrupt = load(id);
        assertThat(afterInterrupt.getStatus()).isEqualTo(ApprovalStatus.DEAD_LETTERED);
        Instant completed = afterInterrupt.getExecutionCompletedAt();
        assertThat(completed).isNotNull();

        // The straggler thread finally reports success.
        inTx(() -> {
            finished(load(id));
            return null;
        });
        ApprovalRequest after = load(id);
        assertThat(after.getStatus()).as("late finish must not resurrect the row").isEqualTo(ApprovalStatus.DEAD_LETTERED);
        assertThat(after.getExecutionCompletedAt()).isEqualTo(completed);
        assertThat(after.getExecutionError()).isEqualTo("process died");
    }

    @Test
    @Order(7)
    void finishedThenInterruptedRefusesToDeadLetter() {
        UUID id = approved(Instant.now().minus(Duration.ofMinutes(30)), Instant.now().minus(Duration.ofMinutes(20)), null);
        inTx(() -> {
            finished(load(id));
            return null;
        });
        assertThat(inTx(() -> interrupted(load(id), "late sweep"))).isFalse();
        ApprovalRequest r = load(id);
        assertThat(r.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(r.getExecutionCompletedAt()).isNotNull();
        assertThat(r.getExecutionError()).isNull();
    }

    @Test
    @Order(8)
    void interruptedAndFinishedRacingAlwaysEndInOneConsistentState() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int interruptedWins = 0;
            int finishedWins = 0;
            for (int round = 0; round < 40; round++) {
                UUID id = approved(Instant.now().minus(Duration.ofMinutes(30)),
                    Instant.now().minus(Duration.ofMinutes(20)), null);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Boolean> sweep = pool.submit(() -> {
                    ApprovalRequest mine = load(id);
                    barrier.await(10, TimeUnit.SECONDS);
                    return inTx(() -> interrupted(mine, "interrupted"));
                });
                Future<?> straggler = pool.submit(() -> {
                    ApprovalRequest mine = load(id);
                    barrier.await(10, TimeUnit.SECONDS);
                    inTx(() -> {
                        finished(mine);
                        return null;
                    });
                    return null;
                });
                boolean interruptedWon = sweep.get(30, TimeUnit.SECONDS);
                straggler.get(30, TimeUnit.SECONDS);
                ApprovalRequest r = load(id);
                assertThat(r.getExecutionCompletedAt()).as("round " + round + ": always completed").isNotNull();
                if (interruptedWon) {
                    interruptedWins++;
                    assertThat(r.getStatus()).isEqualTo(ApprovalStatus.DEAD_LETTERED);
                    assertThat(r.getExecutionError()).isEqualTo("interrupted");
                } else {
                    finishedWins++;
                    assertThat(r.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
                    assertThat(r.getExecutionError()).isNull();
                }
            }
            assertThat(interruptedWins + finishedWins).isEqualTo(40);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Order(9)
    void aLateFinishedWaitsForAnOpenInterruptTransactionAndThenChangesNothing() throws Exception {
        UUID id = approved(Instant.now().minus(Duration.ofMinutes(30)), Instant.now().minus(Duration.ofMinutes(20)), null);
        ApprovalRequest sweepCopy = load(id);
        ApprovalRequest lateCopy = load(id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch holds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Boolean> sweep = pool.submit(() -> inTx(() -> {
                boolean won = interrupted(sweepCopy, "sweep");
                holds.countDown();
                await(release);
                return won;
            }));
            assertThat(holds.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> late = pool.submit(() -> inTx(() -> {
                finished(lateCopy);
                return null;
            }));
            assertThatThrownBy(() -> late.get(1500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(sweep.get(10, TimeUnit.SECONDS)).isTrue();
            late.get(10, TimeUnit.SECONDS);
            assertThat(load(id).getStatus()).isEqualTo(ApprovalStatus.DEAD_LETTERED);
            assertThat(load(id).getExecutionError()).isEqualTo("sweep");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @Order(10)
    void neverRanDeadLettersOnlyUntouchedApprovals() {
        UUID untouched = approved(Instant.now().minus(Duration.ofHours(2)), null, null);
        UUID started = approved(Instant.now().minus(Duration.ofHours(2)), Instant.now().minus(Duration.ofHours(1)), null);
        assertThat(inTx(() -> neverRan(load(untouched), "too old"))).isTrue();
        assertThat(load(untouched).getStatus()).isEqualTo(ApprovalStatus.DEAD_LETTERED);
        assertThat(load(untouched).getExecutionCompletedAt()).isNotNull();
        assertThat(inTx(() -> neverRan(load(started), "too old"))).as("a started action is never 'never ran'").isFalse();
        assertThat(load(started).getStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }

    // ---------------------------------------------------------------- wiring and helpers

    private static void wire() {
        LocalContainerEntityManagerFactoryBean f = new LocalContainerEntityManagerFactoryBean();
        f.setDataSource(dataSource);
        f.setPackagesToScan("com.agenticai.core.approval");
        f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        f.setJpaPropertyMap(Map.of(
            "hibernate.hbm2ddl.auto", "none",
            "hibernate.jdbc.time_zone", "UTC"));
        f.afterPropertiesSet();
        emf = f.getObject();
        JpaTransactionManager txm = new JpaTransactionManager(emf);
        txm.setDataSource(dataSource);
        tx = new TransactionTemplate(txm);
        repo = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf))
            .getRepository(ApprovalRequestRepository.class);
        try {
            Class<?> c = Class.forName("com.agenticai.core.approval.ApprovalRequestRecorder");
            Constructor<?> ctor = c.getDeclaredConstructor(ApprovalRequestRepository.class, AuditLogService.class,
                ApplicationEventPublisher.class, WorkflowEventPublisher.class);
            ctor.setAccessible(true);
            recorder = ctor.newInstance(repo, mock(AuditLogService.class), mock(ApplicationEventPublisher.class),
                mock(WorkflowEventPublisher.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object invoke(String name, Class<?>[] types, Object... args) {
        try {
            Method m = recorder.getClass().getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(recorder, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean claim(ApprovalRequest r) {
        return (Boolean) invoke("claimExecution", new Class<?>[] {ApprovalRequest.class}, r);
    }

    private static void finished(ApprovalRequest r) {
        invoke("recordExecutionFinished", new Class<?>[] {ApprovalRequest.class}, r);
    }

    private static boolean interrupted(ApprovalRequest r, String reason) {
        return (Boolean) invoke("recordInterrupted", new Class<?>[] {ApprovalRequest.class, String.class}, r, reason);
    }

    private static boolean neverRan(ApprovalRequest r, String reason) {
        return (Boolean) invoke("recordNeverRan", new Class<?>[] {ApprovalRequest.class, String.class}, r, reason);
    }

    private static <T> T inTx(java.util.function.Supplier<T> body) {
        return tx.execute(status -> body.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A fresh, detached copy as the database holds it now. */
    private static ApprovalRequest load(UUID id) {
        return repo.findById(id).orElseThrow();
    }

    private static UUID approved(Instant decidedAt, Instant startedAt, Instant completedAt) {
        return status(ApprovalStatus.APPROVED, decidedAt, startedAt, completedAt);
    }

    private static UUID status(ApprovalStatus status, Instant decidedAt, Instant startedAt, Instant completedAt) {
        UUID id = inTx(() -> repo.save(new ApprovalRequest("it.recovery", "recover me", "{}", "it-user")).getId());
        jdbc.update(
            "UPDATE approval_request SET status = ?, decided_at = ?, execution_started_at = ?,"
                + " execution_completed_at = ? WHERE id = ?",
            status.name(), ts(decidedAt), ts(startedAt), ts(completedAt), id);
        return id;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static UUID insertLegacy(String status, Instant decidedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO approval_request (id, action_type, summary, payload_json, status, requested_by, created_at,"
                + " decided_at, retry_count) VALUES (?, 'it.legacy', 'legacy', '{}', ?, 'it-user', ?, ?, 0)",
            id, status, ts(backfillCreatedAt), ts(decidedAt));
        return id;
    }

    private static Instant completedAt(UUID id) {
        Timestamp t = jdbc.queryForObject("SELECT execution_completed_at FROM approval_request WHERE id = ?",
            Timestamp.class, id);
        return t == null ? null : t.toInstant();
    }

    private static boolean columnExists(String column) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'approval_request'"
                + " AND column_name = ?", Integer.class, SCHEMA, column);
        return n != null && n == 1;
    }
}
