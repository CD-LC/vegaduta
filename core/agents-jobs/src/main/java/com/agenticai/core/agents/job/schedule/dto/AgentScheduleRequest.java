package com.agenticai.core.agents.job.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Body of {@code PUT /api/agents/{agentId}/schedule}. The PUT is an upsert -
 * one schedule per agent, so there is no create-vs-update distinction to make
 * the caller reason about, matching the workflow schedule API's shape.
 *
 * @param intervalSeconds alternative to {@code cron}: fire every N seconds (60 to one
 *     year). EXACTLY ONE of {@code cron} / {@code intervalSeconds} must be set.
 * @param targetType {@code AGENT} (default) or {@code WORKFLOW}
 * @param workflowId required iff {@code targetType} is {@code WORKFLOW}; the workflow
 *     is run (as the configuring human) instead of enqueuing an agent job. For a
 *     workflow target {@code goalTemplate} is the run's input text.
 * @param cron either a plain 5-field unix cron or Spring's 6-field form; it is
 *     normalized to the 6-field form before it is persisted (see {@code
 *     AgentCronSchedules#normalize}). Rejected with 400 if it cannot be parsed,
 *     or if its own two next firings are closer together than {@code
 *     app.agent-jobs.schedule.min-interval-minutes}.
 * @param timezone an IANA zone id ({@code Asia/Kolkata}); {@code null} means
 *     UTC. This is what makes "9am daily" mean 9am where the tenant actually is
 *     - the workflow scheduler evaluates everything at UTC and this is the one
 *     place the agent version deliberately does not copy it.
 * @param goalTemplate what the agent is asked to do on each firing. Supports
 *     exactly three substitutions - <code>{{scheduledAt}}</code>,
 *     <code>{{agentName}}</code>, <code>{{lastRunAt}}</code> - rendered at
 *     ENQUEUE so the goal stored on the job is frozen and auditable. An unknown
 *     <code>{{...}}</code> is left LITERAL rather than blanked, so a typo shows
 *     up in the job's goal instead of silently truncating the instruction.
 * @param sentinel when {@code true} (V413) the cadence is a watch brief: each
 *     firing's goal carries the sentinel verdict contract, and the finished
 *     job's {@code ```sentinel} block is turned into a notification or a
 *     parked {@code agent.proposal} approval request instead of being treated
 *     as a deliverable. Only valid with {@code targetType} {@code AGENT} - the
 *     service rejects {@code sentinel && WORKFLOW} because a workflow run has
 *     no result text for a verdict to live in.
 */
public record AgentScheduleRequest(
    @Size(max = 64) String cron,
    @Size(max = 64) String timezone,
    @NotBlank @Size(max = 4000) String goalTemplate,
    Integer intervalSeconds,
    @Size(max = 16) String targetType,
    UUID workflowId,
    Boolean sentinel
) {

    /** Back-compat constructor: the pre-V413 (no sentinel flag) shape. */
    public AgentScheduleRequest(
        String cron, String timezone, String goalTemplate,
        Integer intervalSeconds, String targetType, UUID workflowId
    ) {
        this(cron, timezone, goalTemplate, intervalSeconds, targetType, workflowId, null);
    }

    /** Back-compat constructor: the pre-V338 cron-only, agent-target shape. */
    public AgentScheduleRequest(String cron, String timezone, String goalTemplate) {
        this(cron, timezone, goalTemplate, null, null, null);
    }
}
