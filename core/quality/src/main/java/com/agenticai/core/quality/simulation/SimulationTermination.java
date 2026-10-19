package com.agenticai.core.quality.simulation;

/**
 * WHY a {@link SimulationRun}'s conversation ended (V344) - orthogonal to
 * {@code verdict}, which is the judge's opinion of WHAT happened. A run can
 * terminate MAX_TURNS and still verdict PASSED; termination explains the
 * transcript's shape, verdict judges its content.
 */
public enum SimulationTermination {

    /** The persona simulator emitted its completion sentinel (objective achieved or approaches exhausted). */
    GOAL_MET,

    /** Reserved for a simulator-declared "I give up" outcome distinct from GOAL_MET. */
    GOAL_FAILED,

    /** The conversation used every configured turn without the simulator stopping early. */
    MAX_TURNS,

    /** The target agent's replay threw - the conversation could not continue. */
    AGENT_ERROR,

    /** The simulator produced nothing usable (blank output after lint/retry) or broke character. */
    SIMULATOR_ERROR,

    /** Reserved: the conversation stopped on a guardrail/safety trip. */
    SAFETY_TRIP,

    /**
     * Three consecutive near-duplicate persona turns (normalized-text
     * equality) - the polite-infinite-loop detector: a simulator that keeps
     * repeating itself is generating heat, not signal.
     */
    NO_PROGRESS
}
