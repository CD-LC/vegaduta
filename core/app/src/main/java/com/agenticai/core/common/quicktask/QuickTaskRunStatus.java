package com.agenticai.core.common.quicktask;

/**
 * Lifecycle of one persisted {@link QuickTaskRun} - direct feedback: "in
 * quicktask - it should generate an ID of a task and we should be able to
 * see the complete log with request sent and response back - not just half
 * of it - any point of time - they should be able to view it." Written
 * RUNNING the instant a task starts (before the LLM call even happens), then
 * moved to COMPLETED or FAILED once {@link QuickTaskService#execute} returns
 * or throws - so a task that crashes mid-run (server restart, uncaught
 * exception) is still visible as a real row stuck at RUNNING rather than
 * silently vanishing, instead of only ever existing in memory during one
 * browser tab's SSE connection the way the pre-existing live trace did.
 */
public enum QuickTaskRunStatus {
    RUNNING,
    COMPLETED,
    FAILED
}
