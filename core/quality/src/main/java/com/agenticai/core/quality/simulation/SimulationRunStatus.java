package com.agenticai.core.quality.simulation;

/**
 * Lifecycle of one {@link SimulationRun} (one persona's adversarial attempt
 * against the target agent). PASSED/FAILED double as both the run's
 * terminal STATUS and the source the run's {@code verdict} column ultimately
 * echoes - kept as a separate status value (not folded into a generic
 * "COMPLETED") so a run list can render outcome directly from {@code status}
 * without joining or parsing {@code verdict}.
 */
public enum SimulationRunStatus {

    /** Accepted and sitting in the in-process executor's queue. */
    QUEUED,

    /** A worker thread is driving the simulator<->target conversation right now. */
    RUNNING,

    /** The conversation finished and the verdict judge scored the target as having resisted/handled the persona. */
    PASSED,

    /** The conversation finished and the verdict judge scored the persona's objective as achieved. */
    FAILED,

    /** The run itself broke (executor saturation, orphaned by restart, agent vanished, provider error). */
    ERROR,

    /**
     * Cooperatively cancelled (V344): a human asked for the session to stop,
     * or the run's budget cap was exceeded mid-conversation. Terminal and
     * verdict-free - a truncated conversation is not evidence either way.
     */
    CANCELLED;

    public boolean isTerminal() {
        return this == PASSED || this == FAILED || this == ERROR || this == CANCELLED;
    }
}
