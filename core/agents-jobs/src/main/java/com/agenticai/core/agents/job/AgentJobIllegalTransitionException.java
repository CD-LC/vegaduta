package com.agenticai.core.agents.job;

import java.util.UUID;

/**
 * An attempt to move a job to a status {@link AgentJobStatus#canTransitionTo}
 * forbids - most usefully, anything at all after a terminal state.
 *
 * <p>Deliberately an {@link IllegalStateException} and deliberately NOT mapped
 * to a 4xx anywhere. This is never a client mistake: the API surface exposes no
 * way to request a specific transition, so reaching this means two internal
 * code paths both believed they owned the same job (a worker finishing a job
 * the deadline sweep already failed, say). It should fall through to {@code
 * GlobalExceptionHandler}'s catch-all, become a 500 and be recorded as a
 * PlatformIssue, because that is exactly what it is - a bug worth an operator's
 * triage attention, not a status code to paper over.
 */
public class AgentJobIllegalTransitionException extends IllegalStateException {

    private final UUID jobId;
    private final AgentJobStatus from;
    private final AgentJobStatus to;

    public AgentJobIllegalTransitionException(UUID jobId, AgentJobStatus from, AgentJobStatus to) {
        super("Agent job " + jobId + " cannot transition from " + from + " to " + to);
        this.jobId = jobId;
        this.from = from;
        this.to = to;
    }

    public UUID getJobId() {
        return jobId;
    }

    public AgentJobStatus getFrom() {
        return from;
    }

    public AgentJobStatus getTo() {
        return to;
    }
}
