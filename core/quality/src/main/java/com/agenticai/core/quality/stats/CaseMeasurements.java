package com.agenticai.core.quality.stats;

import java.util.List;

/**
 * The continuous measurements of every observed rollout of ONE eval case -
 * the non-proportion sibling of {@link CaseRollouts}.
 *
 * <p>Judge scores in this module are continuous in {@code [0,1]}
 * ({@code EvaluationResult#score}, {@code EvalRunCaseResult#overallScore}),
 * and collapsing them to pass/fail against a threshold throws away real
 * information: an agent that moved from 0.69 to 0.71 across a 0.70 bar looks
 * like a total reversal through the binomial lens and like noise through this
 * one. Both views are needed, so both types exist. Binomial machinery
 * (Wilson, pass@k, pass^k) does not apply to these values - there is no
 * closed-form interval for a mean of bounded-but-not-binary scores at
 * {@code n = 5}, which is why {@link Bootstrap} exists.
 *
 * <p>Usable for any per-rollout number, not just judge scores: latency and
 * token cost are the obvious other two, and both are worth an interval for
 * the same reason.
 *
 * @param caseKey stable identifier for the case across runs - same convention
 *     as {@link CaseRollouts#caseKey()}
 * @param values one measurement per observed rollout, in observation order.
 *     Never null/empty/containing nulls or NaN: a rollout that produced no
 *     measurement must be dropped by the caller, never imputed - an imputed
 *     mid-range score is precisely the fabrication
 *     {@code EvaluationResult.UNAVAILABLE} exists to prevent
 */
public record CaseMeasurements(String caseKey, List<Double> values) {

    public CaseMeasurements {
        if (caseKey == null || caseKey.isBlank()) {
            throw new IllegalArgumentException("caseKey is required - measurements are reported per case");
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                "caseKey=" + caseKey + " has no measurements - report the absence instead of an empty mean");
        }
        for (Double value : values) {
            if (value == null || value.isNaN()) {
                throw new IllegalArgumentException(
                    "caseKey=" + caseKey + " carries a null/NaN measurement. Drop the unmeasured rollout; "
                        + "never impute a score.");
            }
        }
        values = List.copyOf(values);
    }

    /** How many rollouts contributed a measurement. */
    public int size() {
        return values.size();
    }

    /**
     * Arithmetic mean of this case's measurements - the per-case statistic the
     * hierarchical bootstrap resamples at the inner level.
     */
    public double mean() {
        double sum = 0.0;
        for (Double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    /** Whether this case was observed exactly once - see {@link CaseRollouts#unreplicated()}. */
    public boolean unreplicated() {
        return values.size() == 1;
    }
}
