package com.agenticai.core.quality.stats;

/**
 * An interval estimate: the honest form of a number on this platform.
 *
 * <p><strong>Why this type exists at all.</strong> Everything in
 * {@code core/quality} before this package produced a point estimate - one
 * {@code double} per evaluation ({@code EvaluationResult#score}), one per
 * case ({@code EvalRunCaseResult#overallScore}), one per run
 * ({@code EvalRun#overallScore}) - and the console rendered those as if they
 * were measurements. They are not. A single rollout of a stochastic agent
 * scored by a stochastic judge is ONE DRAW from a distribution, and the
 * difference between two such draws is dominated by sampling noise at the
 * sample sizes a tenant will actually pay for. Printing {@code 0.82} from
 * one run is a reliability claim nobody made. This record is the smallest
 * type that makes the claim sayable: a range, the confidence level it was
 * computed at, and {@linkplain IntervalMethod which estimator} produced it.
 *
 * <p><strong>Why the level and method are fields rather than constants.</strong>
 * They travel to the REST surface and into the console. An interval whose
 * level is implicit gets compared against one computed at a different level
 * the first time two screens are built by two people, and an interval whose
 * method is implicit cannot be reproduced or argued with. Both are cheap to
 * carry and expensive to guess.
 *
 * <p>Bounds are NOT clamped by this record. Clamping is estimator business:
 * {@link WilsonInterval} clamps to [0,1] because a proportion cannot leave
 * that range, while a bootstrap over a signed delta legitimately spans
 * negative values. Forcing a range here would silently corrupt the latter.
 *
 * @param low lower bound of the interval (inclusive, by convention)
 * @param high upper bound of the interval (inclusive, by convention)
 * @param confidenceLevel nominal coverage, exclusive-strictly between 0 and
 *     1 - {@code 0.95} for the 95% intervals this surface defaults to
 * @param method the estimator that produced these bounds
 */
public record ConfidenceInterval(
    double low,
    double high,
    double confidenceLevel,
    IntervalMethod method
) {

    public ConfidenceInterval {
        if (Double.isNaN(low) || Double.isNaN(high)) {
            throw new IllegalArgumentException(
                "ConfidenceInterval bounds must be real numbers, got [" + low + ", " + high + "]");
        }
        if (high < low) {
            throw new IllegalArgumentException(
                "ConfidenceInterval high (" + high + ") must not be below low (" + low + ")");
        }
        if (!(confidenceLevel > 0.0) || !(confidenceLevel < 1.0)) {
            throw new IllegalArgumentException(
                "confidenceLevel must be strictly between 0 and 1, got " + confidenceLevel);
        }
        if (method == null) {
            throw new IllegalArgumentException(
                "ConfidenceInterval requires an IntervalMethod - an interval without its estimator "
                    + "is not reproducible, which is the whole point of this package");
        }
    }

    /** Width of the interval. A wide interval is information, not a defect. */
    public double width() {
        return high - low;
    }

    /** Whether {@code value} lies inside the interval (bounds inclusive). */
    public boolean contains(double value) {
        return value >= low && value <= high;
    }

    /**
     * Whether this interval contains zero - the single most load-bearing
     * predicate in the A/B path. A delta interval that straddles zero means
     * the data is consistent with "no difference", and the console must
     * render "cannot tell" rather than a winner badge. See
     * {@link ComparisonVerdict#CANNOT_TELL}.
     */
    public boolean straddlesZero() {
        return low <= 0.0 && high >= 0.0;
    }

    /** Convenience inverse of {@link #straddlesZero()}. */
    public boolean excludesZero() {
        return !straddlesZero();
    }

    /**
     * Whether this interval lies entirely above {@code other} - the
     * non-overlap test a promotion gate should use instead of a mean delta
     * (the "new version wins only if its lower bound clears the incumbent's
     * upper bound" rule). Deliberately strict: equal bounds are NOT a win.
     */
    public boolean liesEntirelyAbove(ConfidenceInterval other) {
        return this.low > other.high;
    }
}
