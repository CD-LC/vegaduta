package com.agenticai.core.quality.dataset;

/**
 * Outcome of replaying + scoring ONE dataset case within a run. Mirrors the
 * per-dimension {@code EvaluationStatus} contract one level up: SCORED and
 * UNAVAILABLE are both "the replay worked", distinguished by whether any
 * judge produced a real measurement.
 */
public enum EvalCaseResultStatus {

    /** Replay succeeded and at least one judge produced a real score. */
    SCORED,

    /**
     * Replay succeeded but NO judge could measure it (no active provider,
     * judge outage, malformed verdicts on every dimension). Not a failure of
     * the agent under test - contributes to INCONCLUSIVE, never to FAILED.
     */
    UNAVAILABLE,

    /**
     * The replay itself broke (agent deleted, provider error on the turn,
     * timeout). This IS a hard failure of the run - an agent that cannot
     * answer its golden cases has regressed, whatever the reason.
     */
    ERROR
}
