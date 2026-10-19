package com.agenticai.core.quality.stats;

/**
 * What the {@code n} beside a metric is counting.
 *
 * <p>This exists because "n=5" is ambiguous and the ambiguity is dangerous.
 * Five rollouts of one case and five cases run once are wildly different
 * evidence for the same printed number, and the interval attached to each is
 * computed over a different sampling unit. Rather than leave the console to
 * guess (or, worse, to label them all "samples"), every metric says which unit
 * its {@code n} and its interval refer to.
 *
 * <p>Constant names are serialised verbatim to the console.
 */
public enum SampleUnit {

    /**
     * {@code n} counts individual rollouts, pooled across cases. Used by
     * pass@1, whose Wilson interval is over per-attempt outcomes.
     */
    ROLLOUT,

    /**
     * {@code n} counts eval cases. Used by the case-averaged metrics (pass@k,
     * pass^k, mean score), whose intervals resample which cases the suite
     * happens to contain - the uncertainty that actually limits a suite-level
     * claim.
     */
    CASE,

    /**
     * {@code n} counts (baseline, variant) case PAIRS. Used by A/B deltas and
     * the noise floor, where the unit of analysis is a case measured twice.
     */
    CASE_PAIR
}
