package com.agenticai.core.quality;

/**
 * Result of running a batch of evaluations and aggregating scores.
 *
 * @param resultsByType individual results from each evaluator - includes
 *     {@link EvaluationStatus#UNAVAILABLE} entries so callers can see WHICH
 *     dimensions were not measured and why, not just which succeeded
 * @param overallScore aggregated quality score (0-1) over the results that
 *     were actually {@link EvaluationStatus#EVALUATED}, or {@code null} when
 *     nothing was measured at all (no evaluator produced a real score).
 *     Never a fabricated neutral value.
 */
public record AggregatedEvaluationResult(
    java.util.Map<EvaluationType, EvaluationResult> resultsByType,
    Double overallScore
) {

    /** True when at least one evaluator produced a real measurement. */
    public boolean hasMeasuredScore() {
        return overallScore != null;
    }
}
