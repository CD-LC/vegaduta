package com.agenticai.core.agents.job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Implements {@link AgentJobLookup}, the port {@code core/agents} reads jobs through, over {@link
 * AgentJobRepository}. Plain repository reads with no transaction of its own: the same calls the
 * two callers made directly before jobs moved to {@code core/agents-jobs} (ADR-0023).
 */
@Component
public class AgentJobLookupAdapter implements AgentJobLookup {

    private final AgentJobRepository jobs;

    public AgentJobLookupAdapter(AgentJobRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    public Optional<JobFacts> findById(UUID jobId) {
        return jobs.findById(jobId).map(AgentJobLookupAdapter::facts);
    }

    @Override
    public List<JobFacts> recentSucceeded(UUID agentId, int limit) {
        return jobs
                .findByAgentIdAndStatusInOrderByCreatedAtDesc(
                        agentId, List.of(AgentJobStatus.SUCCEEDED), PageRequest.of(0, limit))
                .getContent()
                .stream()
                .map(AgentJobLookupAdapter::facts)
                .toList();
    }

    static JobFacts facts(AgentJob job) {
        AgentJobStatus status = job.getStatus();
        return new JobFacts(
                job.getId(),
                job.getAgentId(),
                job.getSessionId(),
                job.getGoal(),
                status == null ? null : status.name(),
                status != null && status.isTerminal(),
                job.getErrorCode());
    }
}
