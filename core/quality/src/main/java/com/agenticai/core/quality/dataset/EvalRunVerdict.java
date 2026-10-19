package com.agenticai.core.quality.dataset;

/**
 * The pass/fail judgement of one completed {@link EvalRun} against its
 * frozen {@code pass_threshold}. Three-valued deliberately: an all-
 * UNAVAILABLE run (no judge provider configured, provider outage) produced
 * NO measurement, and "we measured nothing" must never read as "the agent
 * regressed to zero" - the same honest-absence contract
 * {@code EvaluationResult} enforces per dimension and {@code TokenBaseline}
 * argues for at the CI level ("an absent baseline is nothing to compare
 * against, never a failure").
 *
 * <p>This verdict is PUBLISHED, not ENFORCED. There is no activation
 * lifecycle on {@code Agent} to gate (verified: no status/published/draft
 * field exists - agents are live the instant they are saved), and the
 * natural future gate points (an agent-version activation transition, or a
 * WorkflowStatus-style DRAFT/ACTIVE model) live in files owned by the
 * concurrent agent-version workstream. Wiring enforcement is recorded as a
 * cross-workstream dependency rather than invented here.
 */
public enum EvalRunVerdict {

    /** Measured aggregate met the bar and no hard assertion failed. */
    PASSED,

    /**
     * Measured aggregate fell below the bar, or at least one expected-tools
     * assertion was violated, or a case errored outright (an eval run whose
     * cases crash is not a pass, whatever the surviving cases scored).
     */
    FAILED,

    /** Nothing was measured - no comparison against the bar is possible. */
    INCONCLUSIVE
}
