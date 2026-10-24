package com.agenticai.core.agents.job.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/agents/{agentId}/jobs}.
 *
 * <p><b>There is deliberately no {@code sessionId} field.</b> The session id is derived from the
 * job's own id ({@code AgentJobSession}) and can never be client-supplied: two jobs sharing a
 * session would cross-correlate each other's tool-call logs, and - once the approval-parking PR
 * lands - each other's approval blockers, so one job could be unblocked by approving an action
 * requested by a different one.
 *
 * @param goal what the agent should accomplish. Becomes the user message of the job's first turn.
 * @param timeoutSeconds {@code null} takes {@code app.agent-jobs.default-timeout}. Clamped to
 *     {@code max-timeout} rather than rejected: a client asking for longer than the platform allows
 *     wants "as long as possible", and a 400 there teaches nothing useful.
 * @param resultSchemaJson OPTIONAL JSON Schema the final answer must satisfy. Snapshotted onto the
 *     row at enqueue, so editing the agent mid-flight cannot retroactively invalidate a running
 *     job. Validated and enforced by the OBSERVABILITY PR; this PR only stores it.
 * @param maxToolCalls {@code null} derives it as {@code maxTurns x
 *     app.guardrails.max-tool-calls-per-turn}.
 * @param maxTurns {@code null} takes {@code app.agent-jobs.max-turns} (the submit default - 1, a
 *     single turn); when given, clamped to {@code [1, app.agent-jobs.max-turns-cap]} so a caller
 *     cannot queue unbounded multi-turn work.
 */
public record AgentJobStartRequest(
        @NotBlank @Size(max = 8000) String goal,
        Integer timeoutSeconds,
        String resultSchemaJson,
        Integer maxToolCalls,
        Integer maxTurns) {

    /** The shape every caller used before {@code maxTurns} existed. */
    public AgentJobStartRequest(
            String goal, Integer timeoutSeconds, String resultSchemaJson, Integer maxToolCalls) {
        this(goal, timeoutSeconds, resultSchemaJson, maxToolCalls, null);
    }
}
