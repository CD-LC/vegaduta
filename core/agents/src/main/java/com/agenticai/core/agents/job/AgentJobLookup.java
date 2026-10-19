package com.agenticai.core.agents.job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only port onto agent jobs for code that stays in {@code core/agents}.
 *
 * <p>Jobs (the {@code AgentJob} entity, its repository and services) live in {@code
 * core/agents-jobs} (ADR-0023), which depends on {@code core/agents}. The two readers left here -
 * the approval replay backstop in {@code AgentToolCallApprovalExecutor} and {@code
 * SkillDistillationService} - see jobs only through this interface and the {@link JobFacts}
 * snapshot, never the entity. The implementation is {@code AgentJobLookupAdapter} in {@code
 * agents-jobs}.
 */
public interface AgentJobLookup {

    /**
     * What a reader outside the jobs module may know about a job.
     *
     * @param status the {@code AgentJobStatus} name (for example {@code SUCCEEDED})
     * @param terminal whether that status is terminal (no further transitions)
     */
    record JobFacts(
            UUID id,
            UUID agentId,
            String sessionId,
            String goal,
            String status,
            boolean terminal,
            String errorCode) {}

    /** The job with this id in the current tenant schema, if any. */
    Optional<JobFacts> findById(UUID jobId);

    /** The agent's most recent SUCCEEDED jobs, newest first, at most {@code limit}. */
    List<JobFacts> recentSucceeded(UUID agentId, int limit);
}
