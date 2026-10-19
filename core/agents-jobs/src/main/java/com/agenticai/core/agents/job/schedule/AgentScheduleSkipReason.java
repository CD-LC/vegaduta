package com.agenticai.core.agents.job.schedule;

/**
 * Why a due schedule did NOT produce a job this tick.
 *
 * <p>A skip is a normal, designed outcome, not an error - a five-minute
 * schedule whose job legitimately takes twenty minutes skips roughly three
 * times per run, forever, and that is the feature working. What is NOT
 * acceptable is silence about it: "my schedule stopped running" with nothing
 * anywhere to explain why is the failure mode this enum exists to prevent. The
 * reason is persisted on {@code agent_schedule.last_skip_reason}, surfaced by
 * {@code GET /api/agents/{id}/schedule}, and logged with the blocking job's id
 * and age.
 *
 * <p>Deliberately NOT an {@code AuditOutcome} constant. Adding {@code
 * AuditOutcome.SKIPPED} was considered and rejected: it would mean editing
 * {@code core/audit} plus the console's shared outcome-badge switch, for no
 * capability the schedule row and its notification do not already provide. This
 * entire workstream leaves {@code core/audit} untouched.
 */
public enum AgentScheduleSkipReason {

    /**
     * The previous run is still sitting in the queue unclaimed - the worker pool
     * is saturated. Counts as live on purpose: letting QUEUED through would let a
     * backed-up pool accumulate a queue of identical goals that then all run at
     * once when capacity returns.
     */
    PREVIOUS_RUN_QUEUED,

    /** The previous run is still executing. The reason this guard exists. */
    PREVIOUS_RUN_RUNNING,

    /**
     * The previous run is parked on a human approval gate. Non-terminal, so it
     * still blocks - firing again would only queue a second copy behind the same
     * unanswered question.
     */
    PREVIOUS_RUN_AWAITING_APPROVAL,

    /**
     * Another replica inserted this occurrence first and V208's unique index
     * rejected ours. This is the guard of record doing its job during an
     * advisory-lock outage, not a fault - recorded as a skip so the counters stay
     * honest rather than swallowed as if nothing happened.
     */
    CONCURRENT_FIRING_LOST,

    /**
     * The agent is gone, or has been switched to {@code SUPERVISED} since the
     * schedule was configured. SUPERVISED gates every tool call on a human, so a
     * scheduled run would park on its first tool call and the overlap guard would
     * then mute this schedule indefinitely while every layer reported itself
     * healthy - skipping loudly is strictly better than that.
     */
    AGENT_UNAVAILABLE,

    /** The target workflow no longer exists (WORKFLOW targets only). */
    WORKFLOW_UNAVAILABLE,

    /** The workflow runner pool was full or unavailable, so the run was not started (WORKFLOW targets only). */
    WORKFLOW_BUSY
}
