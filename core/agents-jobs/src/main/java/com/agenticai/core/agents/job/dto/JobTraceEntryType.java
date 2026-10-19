package com.agenticai.core.agents.job.dto;

/**
 * What kind of thing a {@link JobTraceEntry} is.
 *
 * <p>Two values, and only two, because there are exactly two real sources being
 * merged: the job's own progress notes and its {@code tool_call_log} rows. This
 * enum is deliberately NOT a place to add "LLM_CALL" or "APPROVAL" speculatively
 * - each additional value has to correspond to a table someone actually reads,
 * and an enum constant a client can never receive is worse than no constant at
 * all (it invites client-side handling for a case that will never arrive).
 */
public enum JobTraceEntryType {

    /** An {@code agent_job_progress} row. */
    PROGRESS,

    /** A {@code tool_call_log} row for this job's session. */
    TOOL_CALL
}
