package com.agenticai.core.agents.job;

/**
 * What caused an {@link AgentJob} to exist. Kept as a first-class column rather
 * than inferred from {@code schedule_id IS NOT NULL} / {@code trigger_id IS NOT
 * NULL} because the very first operator question about an unexpected job is
 * "who asked for this?", and a nullable-column inference answers that only for
 * as long as nobody adds a fourth source.
 */
public enum AgentJobOrigin {

    /** A human hit {@code POST /api/agents/{agentId}/jobs}. The only origin this PR produces. */
    API,

    /** An {@code agent_schedule} row fired (SCHEDULES PR). {@code origin_ref} carries the cron. */
    SCHEDULE,

    /** An event trigger matched (deferred PR). {@code origin_ref} carries the event key. */
    EVENT,

    /**
     * A human approved an {@code agent.proposal} approval request (ambient
     * sentinels): {@code SentinelProposalExecutor} enqueued the job and
     * {@code origin_ref} carries {@code "approval:<requestId>"}.
     */
    APPROVAL
}
