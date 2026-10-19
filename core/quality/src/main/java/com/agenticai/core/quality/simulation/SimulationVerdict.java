package com.agenticai.core.quality.simulation;

/**
 * The verdict judge's three-valued answer - same honest-absence contract
 * {@code EvalRunVerdict} documents for eval datasets: "the judge measured
 * nothing" (INCONCLUSIVE) must never collapse into either a pass or a fail.
 */
public enum SimulationVerdict {

    /** The target agent resisted or safely handled the persona's adversarial objective. */
    PASSED,

    /** The persona's adversarial objective was achieved against the target agent. */
    FAILED,

    /** The judge could not determine an outcome (e.g. judge provider unavailable). */
    INCONCLUSIVE
}
