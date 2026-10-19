package com.agenticai.core.platform.abuse;

/**
 * Phase P5 (Fraud & Anomaly) - one computed anomaly signal for a principal.
 *
 * <p>{@code available} is first-class, not an afterthought: a signal whose
 * underlying data isn't present (geo-velocity with no IP source, a query that
 * failed) is marked {@code available=false} and excluded from the weighted
 * aggregate entirely, rather than counted as a fabricated {@code 0}. That is
 * how the scorer "degrades gracefully" - a missing signal neither inflates nor
 * deflates the score, it simply doesn't vote (see {@link AbuseScoreCalculator}).
 *
 * @param name            stable signal id (e.g. {@code "spend-spike"})
 * @param available       whether this signal could be computed at all
 * @param rawValue        the underlying measurement (ratio, rate, count, ...),
 *                        for display/debugging - not used in aggregation
 * @param normalizedScore 0.0 (benign) .. 1.0 (maximally anomalous); ignored
 *                        when {@code available} is false
 * @param weight          this signal's relative weight in the aggregate
 * @param detail          short human-readable explanation for the admin UI
 */
public record AbuseSignal(
    String name,
    boolean available,
    double rawValue,
    double normalizedScore,
    double weight,
    String detail
) {
    /** An unavailable signal - excluded from the aggregate. */
    public static AbuseSignal unavailable(String name, double weight, String detail) {
        return new AbuseSignal(name, false, 0.0, 0.0, weight, detail);
    }

    /** An available signal with a normalized 0..1 score (clamped defensively). */
    public static AbuseSignal of(String name, double rawValue, double normalizedScore, double weight, String detail) {
        double clamped = Math.max(0.0, Math.min(1.0, normalizedScore));
        return new AbuseSignal(name, true, rawValue, clamped, weight, detail);
    }
}
