package com.agenticai.core.quality.dataset;

/**
 * Lifecycle of one {@link EvalRun}. A strict subset of {@code AgentJobStatus}
 * on purpose - no AWAITING_APPROVAL, because even a full-pipeline replay is
 * triggered by a human whose authority is frozen on the run row, and a call
 * that needs an approval is held inside the turn without recording a request
 * (nobody is notified and nothing is waited for); a parked approval inside an
 * eval loop would just hang the run (see {@link EvalMode} for the side-effect
 * posture).
 */
public enum EvalRunStatus {

    /** Accepted and sitting in the in-process executor's queue. */
    QUEUED,

    /** A worker thread is replaying and scoring cases right now. */
    RUNNING,

    /**
     * Every case was attempted (individual cases may still have errored -
     * per-case isolation - but the RUN completed). Verdict is computed only
     * in this state.
     */
    SUCCEEDED,

    /** The run itself broke (executor saturation, orphaned by restart, agent vanished). */
    FAILED,

    /** Cooperatively cancelled between cases via {@code cancel_requested}. */
    CANCELLED;

    /** Terminal states never transition again - guarded in {@link EvalRun}. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
