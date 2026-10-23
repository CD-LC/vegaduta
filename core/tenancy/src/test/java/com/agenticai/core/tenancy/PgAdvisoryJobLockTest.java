package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link PgAdvisoryJobLock}'s three contractual outcomes, DB-free: lock won -> the job runs exactly
 * once and the lock is released on the same connection; lock lost -> the tick is skipped; lock
 * machinery broken -> fail-open, the job still runs (the property a single-instance deployment's
 * jobs depend on). The mocked {@link JdbcTemplate} invokes the real {@link ConnectionCallback}
 * against a mocked {@link Connection}, so the acquire-then-release-on-the-SAME-connection
 * sequencing this class exists for is what's actually exercised - not a stubbed-out shortcut around
 * it.
 */
class PgAdvisoryJobLockTest {

    private static final String JOB = "test-job";

    private JdbcTemplate jdbcTemplate;
    private Connection connection;
    private PreparedStatement lockStatement;
    private PreparedStatement unlockStatement;
    private ResultSet lockResult;
    private PgAdvisoryJobLock lock;

    @BeforeEach
    void setUp() throws SQLException {
        jdbcTemplate = mock(JdbcTemplate.class);
        connection = mock(Connection.class);
        lockStatement = mock(PreparedStatement.class);
        unlockStatement = mock(PreparedStatement.class);
        lockResult = mock(ResultSet.class);

        when(connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))"))
                .thenReturn(lockStatement);
        when(connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))"))
                .thenReturn(unlockStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(unlockStatement.executeQuery()).thenReturn(mock(ResultSet.class));
        when(lockResult.next()).thenReturn(true);

        // JdbcTemplate.execute(ConnectionCallback) runs the real callback
        // against the mocked connection - the same shape the production
        // JdbcTemplate has, minus pooling/exception translation.
        doAnswer(
                        invocation -> {
                            ConnectionCallback<?> callback = invocation.getArgument(0);
                            return callback.doInConnection(connection);
                        })
                .when(jdbcTemplate)
                .execute(any(ConnectionCallback.class));

        lock = new PgAdvisoryJobLock(jdbcTemplate);
    }

    @Test
    void lockAcquiredRunsTheJobAndReleasesOnTheSameConnection() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(true);
        AtomicInteger runs = new AtomicInteger();

        boolean ran = lock.tryRunExclusive(JOB, runs::incrementAndGet);

        assertTrue(ran);
        assertEquals(1, runs.get());
        verify(unlockStatement).executeQuery();
        // Both lock and unlock keyed by the same job name.
        ArgumentCaptor<String> lockKey = ArgumentCaptor.forClass(String.class);
        verify(lockStatement).setString(org.mockito.ArgumentMatchers.eq(1), lockKey.capture());
        verify(unlockStatement).setString(1, lockKey.getValue());
        assertEquals(JOB, lockKey.getValue());
    }

    @Test
    void lockHeldElsewhereSkipsTheJobAndDoesNotUnlock() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(false);
        AtomicInteger runs = new AtomicInteger();

        boolean ran = lock.tryRunExclusive(JOB, runs::incrementAndGet);

        assertFalse(ran);
        assertEquals(0, runs.get());
        // Releasing a lock we never acquired would free the OTHER replica's
        // lock (Postgres advisory unlock is by key, not by holder) - the
        // exact bug that would reintroduce double-running.
        verify(unlockStatement, org.mockito.Mockito.never()).executeQuery();
    }

    @Test
    void lockMachineryFailureFailsOpenAndStillRunsTheJob() {
        // doThrow, not when().thenThrow() - when() would invoke setUp's
        // doAnswer stub with a null callback while stubbing.
        org.mockito.Mockito.doThrow(
                        new DataAccessResourceFailureException("connection pool exhausted"))
                .when(jdbcTemplate)
                .execute(any(ConnectionCallback.class));
        AtomicInteger runs = new AtomicInteger();

        boolean ran = lock.tryRunExclusive(JOB, runs::incrementAndGet);

        assertTrue(ran);
        assertEquals(1, runs.get());
    }

    @Test
    void aJobFailureIsNotMistakenForALockFailureAndNeverRerunsTheJob() throws SQLException {
        // The job's own DataAccessException must propagate unchanged, NOT
        // trip the fail-open path (which would run the job a second time).
        when(lockResult.getBoolean(1)).thenReturn(true);
        AtomicInteger runs = new AtomicInteger();
        DataAccessResourceFailureException jobFailure =
                new DataAccessResourceFailureException("job's own DB call failed");

        DataAccessResourceFailureException thrown =
                assertThrows(
                        DataAccessResourceFailureException.class,
                        () ->
                                lock.tryRunExclusive(
                                        JOB,
                                        () -> {
                                            runs.incrementAndGet();
                                            throw jobFailure;
                                        }));

        assertSame(jobFailure, thrown);
        assertEquals(1, runs.get());
        // Lock still released despite the job throwing.
        verify(unlockStatement).executeQuery();
    }

    @Test
    void tickIsSkippedWithoutTouchingThePoolWhenHeldLockCapIsReached() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(true);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        PgAdvisoryJobLock capped = new PgAdvisoryJobLock(jdbcTemplate, 1, registry);
        AtomicInteger outerRuns = new AtomicInteger();
        AtomicInteger innerRuns = new AtomicInteger();
        boolean[] innerRan = new boolean[1];

        boolean outerRan =
                capped.tryRunExclusive(
                        "outer",
                        () -> {
                            outerRuns.incrementAndGet();
                            // A second job ticking while the only permit is held: skipped, no
                            // connection borrowed.
                            innerRan[0] =
                                    capped.tryRunExclusive("inner", innerRuns::incrementAndGet);
                        });

        assertTrue(outerRan);
        assertEquals(1, outerRuns.get());
        assertFalse(innerRan[0]);
        assertEquals(0, innerRuns.get());
        // Only the outer call reached JdbcTemplate.
        verify(jdbcTemplate, org.mockito.Mockito.times(1)).execute(any(ConnectionCallback.class));
        assertEquals(
                1.0, registry.counter("app.scheduling.locked_job_skipped", "job", "inner").count());
    }

    @Test
    void permitIsReleasedAfterRunSkipAndFailureSoLaterTicksProceed() throws SQLException {
        PgAdvisoryJobLock capped =
                new PgAdvisoryJobLock(
                        jdbcTemplate, 1, (io.micrometer.core.instrument.MeterRegistry) null);
        AtomicInteger runs = new AtomicInteger();

        // Lock lost on this tick, then job failure, then a normal run: the single permit must come
        // back each time.
        when(lockResult.getBoolean(1)).thenReturn(false);
        assertFalse(capped.tryRunExclusive(JOB, runs::incrementAndGet));
        when(lockResult.getBoolean(1)).thenReturn(true);
        assertThrows(
                IllegalStateException.class,
                () ->
                        capped.tryRunExclusive(
                                JOB,
                                () -> {
                                    throw new IllegalStateException("boom");
                                }));
        assertTrue(capped.tryRunExclusive(JOB, runs::incrementAndGet));
        assertEquals(1, runs.get());
    }

    @Test
    void nonPositiveCapDisablesTheLimit() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(true);
        PgAdvisoryJobLock uncapped =
                new PgAdvisoryJobLock(
                        jdbcTemplate, 0, (io.micrometer.core.instrument.MeterRegistry) null);
        boolean[] innerRan = new boolean[1];

        uncapped.tryRunExclusive(
                "outer", () -> innerRan[0] = uncapped.tryRunExclusive("inner", () -> {}));

        assertTrue(innerRan[0]);
    }

    @Test
    void aReleaseFailureDoesNotMaskAJobThatRanSuccessfully() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(true);
        when(unlockStatement.executeQuery())
                .thenThrow(new SQLException("connection dropped mid-release"));
        AtomicInteger runs = new AtomicInteger();

        boolean ran = lock.tryRunExclusive(JOB, runs::incrementAndGet);

        assertTrue(ran);
        assertEquals(1, runs.get());
    }

    @Test
    void capExemptJobsAreNeverSkippedAndDoNotTakeAPermit() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(true);
        PgAdvisoryJobLock capped =
                new PgAdvisoryJobLock(
                        jdbcTemplate, 1, (io.micrometer.core.instrument.MeterRegistry) null);
        boolean[] cronRan = new boolean[1];
        boolean[] otherRan = new boolean[1];

        capped.tryRunExclusive(
                "outer",
                () -> {
                    // The only permit is held, yet the cron job still runs...
                    cronRan[0] = capped.tryRunExclusive("daily-quota-reset", () -> {});
                    otherRan[0] = capped.tryRunExclusive("not-exempt", () -> {});
                });

        assertTrue(cronRan[0]);
        assertFalse(otherRan[0]);
        // ...and an exempt run does not consume the permit: it is free again afterwards.
        capped.tryRunExclusive("daily-quota-reset", () -> {});
        assertTrue(capped.tryRunExclusive("not-exempt", () -> {}));
    }

    /**
     * The migrate-only Job reads a skipped tick as "another migrator holds the lock" and exits 75
     * without migrating, so its lock must never be subject to the held-lock cap: scheduled jobs
     * that tick while the context starts would otherwise fail the Job at random.
     */
    @Test
    void theMigrateOnlyLockIsExemptFromTheCapSoACapSkipCannotLookLikeAHeldLock()
            throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(true);
        PgAdvisoryJobLock capped =
                new PgAdvisoryJobLock(
                        jdbcTemplate, 1, (io.micrometer.core.instrument.MeterRegistry) null);
        boolean[] migrateRan = new boolean[1];

        capped.tryRunExclusive(
                "some-scheduled-job",
                () ->
                        migrateRan[0] =
                                capped.tryRunExclusive(
                                        TenantMigrateOnlyRunner.LOCK_NAME, () -> {}));

        assertTrue(migrateRan[0], "the only permit is held, the migration must still run");
    }

    @Test
    void concurrentHoldersBlockTheNextJobAcrossThreads() throws Exception {
        when(lockResult.getBoolean(1)).thenReturn(true);
        PgAdvisoryJobLock capped =
                new PgAdvisoryJobLock(
                        jdbcTemplate, 2, (io.micrometer.core.instrument.MeterRegistry) null);
        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                String name = "holder-" + i;
                pool.submit(
                        () ->
                                capped.tryRunExclusive(
                                        name,
                                        () -> {
                                            holding.countDown();
                                            try {
                                                release.await(
                                                        10, java.util.concurrent.TimeUnit.SECONDS);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                        }));
            }
            assertTrue(holding.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(capped.tryRunExclusive("third", () -> {}));
            release.countDown();
        } finally {
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertTrue(capped.tryRunExclusive("third", () -> {}));
    }

    @Test
    void permitIsReleasedWhenTheJobThrowsAnError() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(true);
        PgAdvisoryJobLock capped =
                new PgAdvisoryJobLock(
                        jdbcTemplate, 1, (io.micrometer.core.instrument.MeterRegistry) null);
        assertThrows(
                AssertionError.class,
                () ->
                        capped.tryRunExclusive(
                                JOB,
                                () -> {
                                    throw new AssertionError("boom");
                                }));
        assertTrue(capped.tryRunExclusive(JOB, () -> {}));
    }

    @Test
    void parseCapFallsBackToDefaultOnBlankOrInvalidInput() {
        assertEquals(
                PgAdvisoryJobLock.DEFAULT_MAX_CONCURRENT_LOCKED_JOBS,
                PgAdvisoryJobLock.parseCap(""));
        assertEquals(
                PgAdvisoryJobLock.DEFAULT_MAX_CONCURRENT_LOCKED_JOBS,
                PgAdvisoryJobLock.parseCap("  "));
        assertEquals(
                PgAdvisoryJobLock.DEFAULT_MAX_CONCURRENT_LOCKED_JOBS,
                PgAdvisoryJobLock.parseCap("four"));
        assertEquals(6, PgAdvisoryJobLock.parseCap(" 6 "));
        assertEquals(0, PgAdvisoryJobLock.parseCap("0"));
    }

    @Test
    void springPropertyBindingUsesDefaultOverrideAndSurvivesEmptyValue() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(true);
        assertEquals(
                1,
                permitsAfterBooting(
                        java.util.Map.of("app.scheduling.max-concurrent-locked-jobs", "1")));
        assertEquals(
                PgAdvisoryJobLock.DEFAULT_MAX_CONCURRENT_LOCKED_JOBS,
                permitsAfterBooting(java.util.Map.of()));
        // An empty env var (APP_SCHEDULING_MAX_CONCURRENT_LOCKED_JOBS=) must not fail boot.
        assertEquals(
                PgAdvisoryJobLock.DEFAULT_MAX_CONCURRENT_LOCKED_JOBS,
                permitsAfterBooting(
                        java.util.Map.of("app.scheduling.max-concurrent-locked-jobs", "")));
    }

    /**
     * Boots the real @Autowired constructor and counts how many nested distinct jobs get a permit.
     */
    private int permitsAfterBooting(java.util.Map<String, Object> props) {
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext ctx =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment()
                    .getPropertySources()
                    .addFirst(new org.springframework.core.env.MapPropertySource("t", props));
            ctx.registerBean(JdbcTemplate.class, () -> jdbcTemplate);
            ctx.registerBean(PgAdvisoryJobLock.class);
            ctx.refresh();
            PgAdvisoryJobLock bound = ctx.getBean(PgAdvisoryJobLock.class);
            return nestedRuns(bound, 0, 100);
        }
    }

    private static int nestedRuns(PgAdvisoryJobLock l, int depth, int max) {
        int[] result = {depth};
        if (depth < max) {
            l.tryRunExclusive("nest-" + depth, () -> result[0] = nestedRuns(l, depth + 1, max));
        }
        return result[0];
    }
}
