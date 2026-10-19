package com.agenticai.core.knowledge;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-job claiming for {@code knowledge_ingestion_job}, so several runners (replicas or threads)
 * work different jobs at the same time instead of one advisory lock serialising the whole queue.
 *
 * <p>No schema change: the lease lives in the existing columns. A claimed job is {@code
 * PROCESSING}, and {@code updated_at} is its heartbeat. Every write to it on this path (claim,
 * heartbeat, completion, failure) is a SQL statement that sets {@code updated_at = now()}, and the
 * lease test also uses {@code now()}, so the lease only ever compares database time with database
 * time and never a JVM clock. (The runner's tick budget uses the local monotonic clock; that is
 * unrelated to the lease.) A {@code PROCESSING} job whose {@code updated_at} is older than the
 * lease is a dead claim and is handed to the next claimer, in the same statement that claims {@code
 * QUEUED} work.
 *
 * <p>Attempts: {@code result_chunk_count} is unused until a job completes, so while a job is not
 * {@code COMPLETE} it holds the number of times the job was claimed (the claim sets it to 1 on the
 * first run and adds 1 on each reclaim). A reclaim of a job that was already claimed {@code
 * maxAttempts} times does not run it again: the same statement marks it {@code FAILED} (a poison
 * job, for example audio that kills the pod every time). The attempt number is also the fencing
 * token: heartbeat, completion and failure only apply while {@code status = 'PROCESSING'} and the
 * stored attempt equals the caller's, so a slow first runner cannot overwrite what a later runner
 * wrote. No separate column is added; {@code KnowledgeIngestionJobResponse} hides the counter
 * unless the job is {@code COMPLETE}.
 *
 * <p>Claim is one statement: {@code FOR UPDATE SKIP LOCKED} picks a row nobody else is claiming and
 * the {@code UPDATE} flips it before the row lock is released, so a job is handed to exactly one
 * claimer. The table is tenant-scoped, so every call names the schema explicitly (resolved from the
 * current tenant context) the same way the chunk writes do.
 *
 * <p>Limit, stated plainly: a lease is a time-based guess. A runner that stops heartbeating but is
 * still alive (a long GC pause, a lost database connection) can have its job reclaimed and run a
 * second time. The job row is fenced (the loser cannot regress it), but the loser's chunk writes
 * through {@code KnowledgeIngestionService} are not fenced.
 */
@Component
@Profile("knowledge")
public class KnowledgeIngestionJobClaimer {

    /**
     * A claimed job: its id, the schema-qualified table it lives in, whether it was a dead claim,
     * the attempt number (the fencing token), whether the attempt cap was hit (the job is already
     * {@code FAILED} and must not be run), and its document id.
     */
    public record Claim(
            UUID jobId,
            String table,
            boolean reclaimed,
            int attempt,
            boolean poisoned,
            UUID documentId) {}

    /** Default for {@link #heartbeat}'s statement timeout. */
    static final int DEFAULT_HEARTBEAT_TIMEOUT_SECONDS = 5;

    private final JdbcTemplate jdbc;
    private final int heartbeatTimeoutSeconds;

    public KnowledgeIngestionJobClaimer(JdbcTemplate jdbc) {
        this(jdbc, DEFAULT_HEARTBEAT_TIMEOUT_SECONDS);
    }

    @Autowired
    public KnowledgeIngestionJobClaimer(
            JdbcTemplate jdbc,
            @Value("${app.knowledge.ingestion.claiming.heartbeat-timeout-seconds:5}")
                    int heartbeatTimeoutSeconds) {
        this.jdbc = jdbc;
        this.heartbeatTimeoutSeconds = Math.max(1, heartbeatTimeoutSeconds);
    }

    /** Schema-qualified job table for the current tenant context. */
    static String table() {
        return KnowledgeSchemaSupport.qualifiedTableName("knowledge_ingestion_job");
    }

    /**
     * Claims the oldest claimable job in the current tenant schema, or empty when none. Claimable
     * is {@code QUEUED}, or {@code PROCESSING} with no heartbeat for {@code leaseSeconds}. A dead
     * claim already claimed {@code maxAttempts} times is failed instead and returned with {@link
     * Claim#poisoned()} set.
     */
    public Optional<Claim> claimNext(long leaseSeconds, int maxAttempts) {
        String table = table();
        String exhausted = "c.status = 'PROCESSING' AND c.attempts >= ?";
        List<Claim> rows =
                jdbc.query(
                        "WITH c AS (SELECT id, status, coalesce(result_chunk_count, 0) AS attempts"
                                + " FROM "
                                + table
                                + " WHERE status = 'QUEUED' OR (status = 'PROCESSING' AND"
                                + " updated_at < now() - make_interval(secs => ?))"
                                + " ORDER BY created_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED)"
                                + " UPDATE "
                                + table
                                + " j SET status = CASE WHEN "
                                + exhausted
                                + " THEN 'FAILED' ELSE 'PROCESSING' END,"
                                + " result_chunk_count = CASE WHEN "
                                + exhausted
                                + " THEN c.attempts ELSE c.attempts + 1 END,"
                                + " error_message = CASE WHEN "
                                + exhausted
                                + " THEN 'Gave up after ' || c.attempts || ' attempts: the runner"
                                + " stopped heartbeating each time' ELSE j.error_message END,"
                                + " audio_content = CASE WHEN "
                                + exhausted
                                + " THEN NULL ELSE j.audio_content END,"
                                + " updated_at = now()"
                                + " FROM c WHERE j.id = c.id"
                                + " RETURNING j.id, c.status, j.status, j.result_chunk_count,"
                                + " j.document_id",
                        (rs, i) ->
                                new Claim(
                                        rs.getObject(1, UUID.class),
                                        table,
                                        "PROCESSING".equals(rs.getString(2)),
                                        rs.getInt(4),
                                        "FAILED".equals(rs.getString(3)),
                                        rs.getObject(5, UUID.class)),
                        (double) leaseSeconds,
                        maxAttempts,
                        maxAttempts,
                        maxAttempts,
                        maxAttempts);
        return rows.stream().findFirst();
    }

    /**
     * Extends the lease of a job this runner is still working on. False if it is no longer ours
     * (finished, or reclaimed by another attempt).
     *
     * <p>The statement has a short timeout ({@code heartbeat-timeout-seconds}, default 5): all
     * in-flight jobs of a pod share ONE heartbeat thread, so a single hung call (a row lock held by
     * a stuck transaction, a stalled connection) would otherwise stop every heartbeat and let the
     * leases of all of them expire together, followed by a mass reclaim. A timed-out heartbeat
     * throws, the runner logs it, and the next tick retries.
     */
    public boolean heartbeat(Claim claim) {
        return jdbc.update(
                        con -> {
                            java.sql.PreparedStatement ps =
                                    con.prepareStatement(
                                            "UPDATE "
                                                    + claim.table()
                                                    + " SET updated_at = now() WHERE id = ? AND"
                                                    + " status = 'PROCESSING' AND"
                                                    + " coalesce(result_chunk_count, 0) = ?");
                            ps.setQueryTimeout(heartbeatTimeoutSeconds);
                            ps.setObject(1, claim.jobId());
                            ps.setInt(2, claim.attempt());
                            return ps;
                        })
                > 0;
    }

    /**
     * Legacy (advisory-lock) path: takes a {@code QUEUED} job to {@code PROCESSING}. False when it
     * is not {@code QUEUED} any more (another replica, in either mode, already took it). Attempt 0
     * is its fencing token.
     */
    public boolean startIfQueued(UUID jobId) {
        return jdbc.update(
                        "UPDATE "
                                + table()
                                + " SET status = 'PROCESSING', updated_at = now() WHERE id = ? AND"
                                + " status = 'QUEUED'",
                        jobId)
                > 0;
    }

    /**
     * Marks the job {@code COMPLETE} if attempt {@code attempt} still owns it; false if it does not
     * (a later attempt owns it or finished it).
     */
    public boolean complete(UUID jobId, int attempt, int chunkCount) {
        return jdbc.update(
                        "UPDATE "
                                + table()
                                + " SET status = 'COMPLETE', result_chunk_count = ?, audio_content"
                                + " = NULL, updated_at = now() WHERE id = ? AND status ="
                                + " 'PROCESSING' AND coalesce(result_chunk_count, 0) = ?",
                        chunkCount,
                        jobId,
                        attempt)
                > 0;
    }

    /** Marks the job {@code FAILED} if attempt {@code attempt} still owns it. */
    public boolean fail(UUID jobId, int attempt, String message) {
        return jdbc.update(
                        "UPDATE "
                                + table()
                                + " SET status = 'FAILED', error_message = ?, audio_content = NULL,"
                                + " updated_at = now() WHERE id = ? AND status = 'PROCESSING' AND"
                                + " coalesce(result_chunk_count, 0) = ?",
                        message,
                        jobId,
                        attempt)
                > 0;
    }
}
