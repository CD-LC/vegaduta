package com.agenticai.core.agents.job.ownership;

import com.agenticai.core.agents.job.AgentJobStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An in-memory {@code agent_job} table with the SAME compare-and-set semantics as the guarded UPDATEs in
 * {@code AgentJobRepository} (every method is synchronized and checks the same guard the SQL does), so the
 * kill simulation exercises the real adoption code against faithful atomicity.
 */
final class InMemoryJobLeaseStore implements JobLeaseStore {

    static final class Row {
        final UUID id = UUID.randomUUID();
        final UUID agentId = UUID.randomUUID();
        final String tenantId = "t_sim";
        final Instant createdAt;
        AgentJobStatus status = AgentJobStatus.QUEUED;
        String owner;
        Instant heartbeat;
        int attempt;
        int turnsUsed;
        boolean cancelRequested;
        String errorCode;
        String errorMessage;
        String resumeReason;

        Row(Instant createdAt) {
            this.createdAt = createdAt;
        }

        LeasedJob snapshot() {
            return new LeasedJob(id, tenantId, agentId, "job:" + id, owner, heartbeat, attempt, turnsUsed,
                cancelRequested, createdAt);
        }
    }

    private final Map<UUID, Row> rows = new LinkedHashMap<>();

    // ---- test-side operations (the parts of the real system that are not under test) ----

    synchronized Row enqueue(Instant now) {
        Row row = new Row(now);
        rows.put(row.id, row);
        return row;
    }

    /** Mirror of {@code AgentJobRepository#claim}: QUEUED -> RUNNING, exactly one winner. */
    synchronized boolean claim(UUID id, String owner, Instant now) {
        Row row = rows.get(id);
        if (row == null || row.status != AgentJobStatus.QUEUED) {
            return false;
        }
        row.status = AgentJobStatus.RUNNING;
        row.owner = owner;
        row.heartbeat = now;
        row.attempt++;
        return true;
    }

    synchronized void incrementTurnsUsed(UUID id) {
        rows.get(id).turnsUsed++;
    }

    synchronized void requestCancel(UUID id) {
        rows.get(id).cancelRequested = true;
    }

    /** What a worker's finish() does, with the ownership guard of {@code JobOwnership#mayWriteTerminal}. */
    synchronized boolean succeed(UUID id, String owner) {
        Row row = rows.get(id);
        if (!JobLeasePolicy.holdsLease(row.status, row.owner, owner)) {
            return false;
        }
        row.status = AgentJobStatus.SUCCEEDED;
        row.owner = null;
        return true;
    }

    synchronized Row row(UUID id) {
        return rows.get(id);
    }

    // ---- JobLeaseStore ----

    @Override
    public synchronized boolean renew(UUID jobId, String owner, Instant now) {
        Row row = rows.get(jobId);
        if (row == null || row.status != AgentJobStatus.RUNNING || !owner.equals(row.owner)) {
            return false;
        }
        row.heartbeat = now;
        return true;
    }

    @Override
    public synchronized Optional<LeaseView> view(UUID jobId) {
        Row row = rows.get(jobId);
        return row == null ? Optional.empty() : Optional.of(new LeaseView(row.status, row.owner));
    }

    @Override
    public synchronized Optional<LeasedJob> find(UUID jobId) {
        Row row = rows.get(jobId);
        return row == null ? Optional.empty() : Optional.of(row.snapshot());
    }

    @Override
    public synchronized List<LeasedJob> findExpired(Instant cutoff, int limit) {
        List<Row> expired = new ArrayList<>();
        for (Row row : rows.values()) {
            if (row.status == AgentJobStatus.RUNNING && row.heartbeat != null && row.heartbeat.isBefore(cutoff)) {
                expired.add(row);
            }
        }
        expired.sort(Comparator.comparing(r -> r.heartbeat));
        List<LeasedJob> out = new ArrayList<>();
        for (Row row : expired) {
            if (out.size() >= limit) {
                break;
            }
            out.add(row.snapshot());
        }
        return out;
    }

    @Override
    public synchronized boolean release(UUID jobId, String owner, Instant releasedAt) {
        Row row = rows.get(jobId);
        if (row == null || row.status != AgentJobStatus.RUNNING || !owner.equals(row.owner)) {
            return false;
        }
        row.heartbeat = releasedAt;
        return true;
    }

    private boolean guardHolds(Row row, int observedAttempt, Instant cutoff) {
        return row != null && row.status == AgentJobStatus.RUNNING && row.attempt == observedAttempt
            && row.heartbeat != null && row.heartbeat.isBefore(cutoff);
    }

    @Override
    public synchronized boolean requeueExpired(UUID jobId, int observedAttempt, Instant cutoff, String reason) {
        Row row = rows.get(jobId);
        if (!guardHolds(row, observedAttempt, cutoff)) {
            return false;
        }
        row.status = AgentJobStatus.QUEUED;
        row.owner = null;
        row.heartbeat = null;
        row.resumeReason = reason;
        return true;
    }

    @Override
    public synchronized boolean failExpired(
        UUID jobId, int observedAttempt, Instant cutoff, String errorCode, String errorMessage, Instant now) {
        Row row = rows.get(jobId);
        if (!guardHolds(row, observedAttempt, cutoff)) {
            return false;
        }
        row.status = AgentJobStatus.FAILED;
        row.owner = null;
        row.errorCode = errorCode;
        row.errorMessage = errorMessage;
        return true;
    }

    @Override
    public synchronized boolean cancelExpired(UUID jobId, int observedAttempt, Instant cutoff, Instant now) {
        Row row = rows.get(jobId);
        if (!guardHolds(row, observedAttempt, cutoff) || !row.cancelRequested) {
            return false;
        }
        row.status = AgentJobStatus.CANCELLED;
        row.owner = null;
        return true;
    }
}
