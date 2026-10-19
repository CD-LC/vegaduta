package com.agenticai.core.agents.job.ownership;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * {@link JobLeaseStore} over {@link AgentJobRepository}: a thin delegate, every method is one guarded UPDATE
 * or one bounded read. Holds no state.
 */
@Component
public class JpaJobLeaseStore implements JobLeaseStore {

    private final AgentJobRepository repository;

    public JpaJobLeaseStore(AgentJobRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean renew(UUID jobId, String owner, Instant now) {
        return repository.renewLease(jobId, owner, now) > 0;
    }

    @Override
    public Optional<LeaseView> view(UUID jobId) {
        Optional<AgentJobStatus> status = repository.findStatusById(jobId);
        if (status.isEmpty()) {
            return Optional.empty();
        }
        String owner = repository.findOwnerInstanceById(jobId).orElse(null);
        return Optional.of(new LeaseView(status.get(), owner));
    }

    @Override
    public Optional<LeasedJob> find(UUID jobId) {
        return repository.findById(jobId).map(LeasedJob::from);
    }

    @Override
    public List<LeasedJob> findExpired(Instant cutoff, int limit) {
        List<AgentJob> rows = repository.findExpiredLeases(
            AgentJobStatus.RUNNING, cutoff, PageRequest.of(0, Math.max(1, limit)));
        return rows.stream().map(LeasedJob::from).toList();
    }

    @Override
    public boolean release(UUID jobId, String owner, Instant releasedAt) {
        return repository.releaseLease(jobId, owner, releasedAt) > 0;
    }

    @Override
    public boolean requeueExpired(UUID jobId, int observedAttempt, Instant cutoff, String reason) {
        return repository.requeueIfLeaseExpired(jobId, observedAttempt, cutoff, reason) > 0;
    }

    @Override
    public boolean failExpired(
        UUID jobId, int observedAttempt, Instant cutoff, String errorCode, String errorMessage, Instant now) {
        return repository.failIfLeaseExpired(jobId, observedAttempt, cutoff, errorCode, errorMessage, now) > 0;
    }

    @Override
    public boolean cancelExpired(UUID jobId, int observedAttempt, Instant cutoff, Instant now) {
        return repository.cancelIfLeaseExpired(jobId, observedAttempt, cutoff, now) > 0;
    }
}
