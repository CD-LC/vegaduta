package com.agenticai.core.tenancy;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Cross-replica mutual exclusion for {@code @Scheduled} jobs, built on Postgres session advisory
 * locks ({@code pg_try_advisory_lock(hashtext(...))}). Before this class, every scheduler in this
 * codebase carried some variant of {@code WorkflowScheduler}'s "single-instance deployment
 * assumption" javadoc: two {@code core} replicas would both tick and double-run workflows,
 * double-ingest email, double-count usage. Wrapping a tick in {@link #tryRunExclusive} makes
 * exactly one replica per tick win; the others skip cheaply and try again next tick.
 *
 * <p><b>Why advisory locks and not ShedLock:</b> zero new dependency and zero new table/migration -
 * the lock lives entirely inside the shared Postgres every deployment already has, and works today
 * with no schema change. The tradeoffs, stated honestly: a <em>session</em> advisory lock belongs
 * to one database connection, so acquire, job, and release must all happen on the SAME pinned
 * connection (the {@link ConnectionCallback} below - same reason {@code GraphMemoryService} uses
 * one for AGE's {@code LOAD 'age'} session quirk). That pins one pool connection for the job's
 * whole runtime - fine for these periodic jobs, but the reason this is not a general-purpose lock
 * for long-lived or high-frequency critical sections. Unlike ShedLock there is also no {@code
 * lockAtMostFor} lease: if a replica dies mid-job, Postgres releases the lock the moment that
 * session drops, which is strictly better than a time-based lease (no tuning, no stale-lease
 * window).
 *
 * <p><b>Not reentrant:</b> each call borrows its own pooled connection, so a nested {@code
 * tryRunExclusive} for the same job name would contend against the outer call's connection and
 * skip. Don't nest.
 *
 * <p><b>Fail-open:</b> if the lock machinery itself errors (can't get a connection, lock query
 * fails), the job still runs, with a WARN. A lock outage must never stop a single-instance
 * deployment's jobs; the cost is that a genuine multi-replica deployment could double-run during a
 * lock outage - the pre-existing behavior, now bounded to outages instead of always.
 *
 * <p><b>Key space:</b> {@code hashtext(jobName)} is a 32-bit hash, so two distinct job names could
 * theoretically collide. With a handful of static names the odds are negligible, and the
 * consequence of a collision is only over-serialization (one job skipping a tick while an unrelated
 * one runs), never a double run.
 *
 * <p><b>Held-lock cap:</b> every held lock pins one pooled connection and the job body typically
 * needs a second, so N concurrently locked jobs can hold up to 2N connections. {@code
 * app.scheduling.max-concurrent-locked-jobs} (default 4) bounds the locks held at once PER JVM with
 * a non-blocking semaphore; a tick that finds the cap reached is skipped (never queued) with a
 * DEBUG log and the {@code app.scheduling.locked_job_skipped} counter, and retried at the job's
 * next tick (a sustained skip also logs a WARN, at most once per job per minute). A value {@code <=
 * 0} disables it; a blank or unparsable value falls back to the default (see {@link #parseCap}).
 * The cap is per JVM by design (it protects that JVM's own Hikari pool), so it needs no
 * cross-replica coordination. It is a soft bound, not a hard guarantee: job bodies may borrow more
 * than one connection.
 *
 * <p><b>Exempt jobs:</b> a skipped tick is only harmless for a poller that ticks again soon. {@link
 * #CAP_EXEMPT_JOBS} lists, by lock name, the jobs that never take or wait for a permit and are
 * never skipped by the cap: cron jobs (a skipped midnight quota reset would not run again for a
 * day), jobs that are long by design (index builds, knowledge ingestion/re-embed, retention purges)
 * and the short latency-critical relays/schedulers, plus the migrate-only Job's lock (its caller
 * reads a skipped tick as "another migrator holds the lock"). They still pin their own connection,
 * so the bound stays soft. Exemption is by name (not a second method) so every caller and its test
 * double keeps the one {@link #tryRunExclusive} entry point; a NEW cron or long-running locked job
 * must be added to that set.
 */
@Service
public class PgAdvisoryJobLock {

    private static final Logger log = LoggerFactory.getLogger(PgAdvisoryJobLock.class);

    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(hashtext(?))";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(hashtext(?))";

    /**
     * Default cap: with Hikari max 15, 4 locks pin 4 + 4 job-body connections, leaving 7 for
     * requests.
     */
    static final int DEFAULT_MAX_CONCURRENT_LOCKED_JOBS = 4;

    private final JdbcTemplate jdbcTemplate;

    /** Null when the cap is disabled. */
    private final Semaphore heldLockPermits;

    private final MeterRegistry meterRegistry;

    /** Job name -> last time a cap-skip WARN was logged (names are static, so this stays tiny). */
    private final ConcurrentHashMap<String, Long> lastSkipWarnNanos = new ConcurrentHashMap<>();

    /**
     * Lock names exempt from the held-lock cap - see the class javadoc. Keep in sync with new
     * cron/long jobs.
     */
    static final Set<String> CAP_EXEMPT_JOBS =
            Set.of(
                    // cron-driven: a skipped tick is not retried until the next slot
                    "daily-quota-reset",
                    "llm-usage-rollup",
                    "provider-model-catalog-refresh",
                    "model-capability-summary",
                    "agent-supervisor-sweep",
                    "channel-session-resolution-sweep",
                    "tutor-review-reminder-sweep",
                    // long by design
                    "knowledge-vector-index-maintenance",
                    "knowledge-ingestion-poll",
                    "knowledge-reembed-poll",
                    "chat-branch-retention-purge",
                    "agent-step-journal-purge",
                    "turn-journal-retention-purge",
                    // short, latency-critical dispatch/relay loops that must not starve behind long
                    // jobs
                    "workflow-event-outbox-relay",
                    "webhook-delivery-relay",
                    "workflow-scheduler",
                    "agent-job-dispatch",
                    "agent-job-deadline-sweep",
                    "agent-job-orphan-sweep",
                    // the one-shot migrate-only Job: a cap skip would read as "another migrator
                    // holds the lock" and exit 75 without migrating
                    TenantMigrateOnlyRunner.LOCK_NAME);

    private static final long SKIP_WARN_INTERVAL_NANOS = 60_000_000_000L;

    public PgAdvisoryJobLock(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, DEFAULT_MAX_CONCURRENT_LOCKED_JOBS, (MeterRegistry) null);
    }

    @Autowired
    public PgAdvisoryJobLock(
            JdbcTemplate jdbcTemplate,
            @Value(
                            "${app.scheduling.max-concurrent-locked-jobs:"
                                    + DEFAULT_MAX_CONCURRENT_LOCKED_JOBS
                                    + "}")
                    String maxConcurrentLockedJobs,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this(
                jdbcTemplate,
                parseCap(maxConcurrentLockedJobs),
                meterRegistry == null ? null : meterRegistry.getIfAvailable());
    }

    PgAdvisoryJobLock(
            JdbcTemplate jdbcTemplate, int maxConcurrentLockedJobs, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.heldLockPermits =
                maxConcurrentLockedJobs > 0 ? new Semaphore(maxConcurrentLockedJobs) : null;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Blank (e.g. an empty env var) or non-numeric input falls back to the default instead of
     * failing boot.
     */
    static int parseCap(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_CONCURRENT_LOCKED_JOBS;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn(
                    "Invalid app.scheduling.max-concurrent-locked-jobs '{}' - using default {}",
                    raw,
                    DEFAULT_MAX_CONCURRENT_LOCKED_JOBS);
            return DEFAULT_MAX_CONCURRENT_LOCKED_JOBS;
        }
    }

    /**
     * Runs {@code job} only if this replica wins the advisory lock for {@code jobName}; returns
     * {@code true} if the job ran (including the fail-open path), {@code false} if another holder
     * had the lock and this tick was skipped (logged at DEBUG - callers that export a skip metric,
     * e.g. {@code WorkflowEventOutboxRelay}, use the return value).
     *
     * <p>Exceptions thrown by {@code job} itself propagate unchanged (after the lock is released) -
     * each scheduler keeps its own established error-handling semantics; this class only decides
     * whether a tick runs.
     */
    public boolean tryRunExclusive(String jobName, Runnable job) {
        // Non-blocking: skip rather than queue, so a scheduler thread is never parked waiting here.
        boolean capped = heldLockPermits != null && !CAP_EXEMPT_JOBS.contains(jobName);
        if (capped && !heldLockPermits.tryAcquire()) {
            recordCapSkip(jobName);
            return false;
        }
        try {
            return runWithLock(jobName, job);
        } finally {
            if (capped) {
                heldLockPermits.release();
            }
        }
    }

    private void recordCapSkip(String jobName) {
        log.debug(
                "Skipping '{}' tick - this JVM already holds the max concurrent locked jobs",
                jobName);
        if (meterRegistry != null) {
            meterRegistry.counter("app.scheduling.locked_job_skipped", "job", jobName).increment();
        }
        long now = System.nanoTime();
        Long last = lastSkipWarnNanos.get(jobName);
        if (last == null || now - last >= SKIP_WARN_INTERVAL_NANOS) {
            lastSkipWarnNanos.put(jobName, now);
            log.warn(
                    "Job '{}' tick skipped: this JVM already holds the max concurrent locked jobs"
                            + " (app.scheduling.max-concurrent-locked-jobs); sustained skips mean"
                            + " long-running locked jobs are hogging the permits (logged at most once a"
                            + " minute per job)",
                    jobName);
        }
    }

    private boolean runWithLock(String jobName, Runnable job) {
        Boolean ran;
        try {
            ran =
                    jdbcTemplate.execute(
                            (ConnectionCallback<Boolean>)
                                    conn -> {
                                        if (!tryAcquire(conn, jobName)) {
                                            return false;
                                        }
                                        // Outcome lines for scripts/ci/two-replica-drill.sh: logged
                                        // AFTER the acquire and BEFORE
                                        // the release, so the logged [won, released] window is
                                        // always inside the true lock-hold
                                        // window and two replicas' windows for one job can only
                                        // overlap if the lock is broken.
                                        log.debug("advisory-lock outcome=won job={}", jobName);
                                        try {
                                            job.run();
                                        } catch (RuntimeException jobFailure) {
                                            // Sentinel-wrapped so the fail-open catch below can
                                            // never
                                            // mistake the job's own failure (which may itself be a
                                            // DataAccessException if the job touches the DB) for a
                                            // lock-machinery failure and re-run the job.
                                            throw new JobFailedException(jobFailure);
                                        } finally {
                                            log.debug(
                                                    "advisory-lock outcome=released job={}",
                                                    jobName);
                                            releaseQuietly(conn, jobName);
                                        }
                                        return true;
                                    });
        } catch (JobFailedException jobFailure) {
            throw jobFailure.unwrap();
        } catch (DataAccessException lockMachineryFailure) {
            log.warn(
                    "Advisory-lock check for job '{}' failed - running the job anyway (fail-open; "
                            + "a lock outage must not stop a single-instance deployment)",
                    jobName,
                    lockMachineryFailure);
            job.run();
            return true;
        }
        if (Boolean.TRUE.equals(ran)) {
            return true;
        }
        log.debug("Skipping '{}' tick - another replica holds the advisory lock", jobName);
        log.debug("advisory-lock outcome=skipped job={}", jobName);
        return false;
    }

    /**
     * A lock-query {@link SQLException} deliberately propagates out of here (JdbcTemplate
     * translates it to a {@link DataAccessException}) so the caller's fail-open path handles it.
     */
    private boolean tryAcquire(Connection conn, String jobName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(TRY_LOCK_SQL)) {
            ps.setString(1, jobName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /**
     * Best-effort - a release failure is only WARNed, never propagated: the job already ran, and
     * surfacing a release error as a job failure would lie about what happened. A leaked session
     * lock persists only until the pool retires this physical connection (session locks die with
     * the session), so the worst case is other replicas skipping ticks until then, never a double
     * run.
     */
    private void releaseQuietly(Connection conn, String jobName) {
        try (PreparedStatement ps = conn.prepareStatement(UNLOCK_SQL)) {
            ps.setString(1, jobName);
            ps.executeQuery().close();
        } catch (Exception e) {
            log.warn(
                    "Failed to release advisory lock for job '{}' - it will be freed when this"
                            + " connection is retired",
                    jobName,
                    e);
        }
    }

    /** Sentinel wrapper distinguishing "the job threw" from "the lock machinery threw". */
    private static final class JobFailedException extends RuntimeException {
        JobFailedException(RuntimeException cause) {
            super(cause);
        }

        RuntimeException unwrap() {
            return (RuntimeException) getCause();
        }
    }
}
