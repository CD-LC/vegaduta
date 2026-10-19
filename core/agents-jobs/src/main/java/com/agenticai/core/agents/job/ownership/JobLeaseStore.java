package com.agenticai.core.agents.job.ownership;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The seam between the ownership logic and the {@code agent_job} table. Every mutating method is a single
 * compare-and-set: it returns {@code true} only for the one caller whose guard still held. Implementations
 * run inside the caller's {@code TenantContext} (the sweep iterates tenant schemas; the heartbeat and release
 * paths set the job's tenant themselves). Exists as an interface so the two-replica kill simulation can run
 * the real decision code against an in-memory table with the same CAS semantics.
 */
public interface JobLeaseStore {

    /** What the sweep needs to know about one expired RUNNING row (an immutable snapshot, never the entity). */
    record LeasedJob(
        UUID id,
        String tenantId,
        UUID agentId,
        String sessionId,
        String ownerInstance,
        Instant heartbeatAt,
        int attemptCount,
        int turnsUsed,
        boolean cancelRequested,
        Instant createdAt
    ) {
        public static LeasedJob from(AgentJob job) {
            return new LeasedJob(
                job.getId(), job.getTenantId(), job.getAgentId(), job.getSessionId(), job.getOwnerInstance(),
                job.getHeartbeatAt(), job.getAttemptCount(), job.getTurnsUsed(), job.isCancelRequested(),
                job.getCreatedAt());
        }
    }

    /** Status and owner of a row, read after a failed renewal. */
    record LeaseView(AgentJobStatus status, String owner) {
    }

    /** Advances the heartbeat iff {@code owner} still owns the RUNNING row. */
    boolean renew(UUID jobId, String owner, Instant now);

    /** Current status/owner of the row; empty when the row is gone. */
    Optional<LeaseView> view(UUID jobId);

    /** One job by id (RUNNING or not); empty when gone. */
    Optional<LeasedJob> find(UUID jobId);

    /** Bounded read: RUNNING rows whose heartbeat is strictly before {@code cutoff}, oldest first. */
    List<LeasedJob> findExpired(Instant cutoff, int limit);

    /** Graceful release: {@code heartbeat_at = releasedAt} iff {@code owner} still owns the RUNNING row. */
    boolean release(UUID jobId, String owner, Instant releasedAt);

    /** RUNNING -&gt; QUEUED iff status, observed attempt and expired lease all still hold. */
    boolean requeueExpired(UUID jobId, int observedAttempt, Instant cutoff, String reason);

    /** RUNNING -&gt; FAILED iff status, observed attempt and expired lease all still hold. */
    boolean failExpired(
        UUID jobId, int observedAttempt, Instant cutoff, String errorCode, String errorMessage, Instant now);

    /** RUNNING -&gt; CANCELLED iff the same guards hold and a cancel was requested. */
    boolean cancelExpired(UUID jobId, int observedAttempt, Instant cutoff, Instant now);
}
