package com.agenticai.core.quality.simulation;

/** Lifecycle of one {@link SimulationSession}. */
public enum SimulationSessionStatus {

    /** Created, one {@link SimulationRun} per persona submitted to the executor. */
    QUEUED,

    /** At least one run is still QUEUED or RUNNING. */
    RUNNING,

    /** Every run reached a terminal state (PASSED/FAILED/ERROR/CANCELLED). */
    COMPLETED,

    /**
     * Cooperatively cancelled (V344) before every run completed - set when
     * the last live run drains after {@code cancel_requested} was set, so a
     * cancelled session is distinguishable from a naturally completed one.
     */
    CANCELLED
}
