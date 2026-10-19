package com.agenticai.core.platform.abuse;

import java.util.List;

/**
 * Phase P5 (Fraud & Anomaly) - the pure, side-effect-free math of turning a set
 * of {@link AbuseSignal}s into an aggregate 0..100 risk score and mapping that
 * score to an {@link AbuseActionTier}. Deliberately has zero Spring/DB
 * dependencies so it is exhaustively unit-testable (see {@code
 * AbuseScoreCalculatorTest}) - all the IO (querying signals, taking actions)
 * lives in {@link AbuseSignalService}, none of it here.
 *
 * <p>Aggregation is a weighted mean over ONLY the available signals, normalized
 * by the sum of their weights. This is the "degrade gracefully" contract: an
 * unavailable signal (geo-velocity with no IP feed, a query that failed) drops
 * out of both numerator and denominator, so the remaining signals still produce
 * a meaningful score on the same 0..100 scale instead of being diluted toward 0.
 */
public final class AbuseScoreCalculator {

    private AbuseScoreCalculator() {}

    /** clamp(value / threshold, 0, 1); a non-positive threshold means "disabled" and yields 0. */
    public static double normalizeRate(double value, double threshold) {
        if (threshold <= 0 || value <= 0) {
            return 0.0;
        }
        return Math.min(1.0, value / threshold);
    }

    /**
     * A ratio-vs-baseline spike, normalized so ratio&lt;=1 is benign (0) and
     * ratio&gt;=multiplier is maximal (1), linear in between. {@code multiplier}
     * &lt;=1 is treated as "any excess is maximal" to avoid a divide-by-~0.
     */
    public static double normalizeSpike(double ratio, double multiplier) {
        if (ratio <= 1.0) {
            return 0.0;
        }
        if (multiplier <= 1.0) {
            return 1.0;
        }
        return Math.min(1.0, (ratio - 1.0) / (multiplier - 1.0));
    }

    /**
     * Weighted mean of available signals * 100. No available signal (all
     * unavailable, or an empty list) yields 0.0 - the fail-open default: absence
     * of evidence is not evidence of abuse.
     */
    public static double aggregate(List<AbuseSignal> signals) {
        double weightedSum = 0.0;
        double weightTotal = 0.0;
        for (AbuseSignal s : signals) {
            if (!s.available() || s.weight() <= 0) {
                continue;
            }
            weightedSum += s.normalizedScore() * s.weight();
            weightTotal += s.weight();
        }
        if (weightTotal <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(100.0, (weightedSum / weightTotal) * 100.0));
    }

    /**
     * Map an aggregate score to a tier. Boundaries are inclusive on the lower
     * edge ({@code score >= suspendThreshold} is SUSPEND). Thresholds are read
     * defensively: if they are mis-ordered by an admin edit, the highest
     * applicable tier still wins because they are checked most-severe-first.
     */
    public static AbuseActionTier decideTier(double score, AbuseThresholds t) {
        if (score >= t.suspendThreshold()) {
            return AbuseActionTier.SUSPEND;
        }
        if (score >= t.throttleThreshold()) {
            return AbuseActionTier.THROTTLE;
        }
        if (score >= t.watchThreshold()) {
            return AbuseActionTier.WATCH;
        }
        return AbuseActionTier.NORMAL;
    }
}
