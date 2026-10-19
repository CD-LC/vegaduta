package com.agenticai.core.agents.job;

import java.util.UUID;

/**
 * Optional hook consulted by {@link AgentJobService#submit} before a job row is written. It lets a feature
 * that meters its own runs (deep research) enforce that meter on the generic
 * {@code POST /api/agents/{id}/jobs} path too, instead of only on its own facade - otherwise the facade's
 * cap is bypassed by submitting a job to the same agent directly.
 *
 * <p>Lives in the job package so {@code AgentJobService} never imports a feature package.
 */
public interface AgentJobSubmitGate {

    /**
     * Called for an API job submission by {@code subject} in {@code tenantId}. Returns {@code null} when the
     * agent is not gated; otherwise reserves whatever the feature meters (throwing a
     * {@code ResponseStatusException} to refuse) and returns a callback that gives the reservation back
     * if the submission then fails.
     */
    Runnable reserve(UUID agentId, String tenantId, String subject);
}
