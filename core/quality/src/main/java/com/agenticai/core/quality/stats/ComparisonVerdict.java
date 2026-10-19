package com.agenticai.core.quality.stats;

/**
 * The adjudication of one paired A/B - and, importantly, the three ways of
 * saying "no".
 *
 * <p>The console currently has no vocabulary for "we ran the comparison and it
 * does not support a conclusion", so any delta becomes a winner. Three of the
 * five constants below exist to give that state a name, because a 2026 paired
 * noise-floor study on tau^2-bench retail found paired coordination gaps
 * spanning roughly -3 to +18 percentage points across seeds with a pooled
 * +5pp at a Wilson interval of [-2, +12] (not significant), and concluded that
 * seven of ten recently published multi-agent coordination architectures report
 * headline effects BELOW that floor (arXiv 2605.13841). A platform that renders
 * every positive delta as a win reproduces that error for its tenants, one
 * feature flag at a time.
 *
 * <p>Constant names are part of the API contract with the console - they are
 * serialised verbatim by the REST surface.
 */
public enum ComparisonVerdict {

    /**
     * The variant is better: the delta interval lies entirely above zero AND
     * the effect is larger than the measured noise floor (or no floor was
     * available to test against, in which case the interval alone carries the
     * claim and the response says the floor was absent).
     */
    VARIANT_BETTER,

    /** The baseline is better, by the mirror-image test. */
    BASELINE_BETTER,

    /**
     * Cannot tell: the delta's confidence interval straddles zero. The data is
     * consistent with no difference at all. This is the state the console must
     * render instead of a winner badge - not a neutral "tie", an explicit
     * "this experiment did not answer the question".
     */
    CANNOT_TELL,

    /**
     * Cannot tell, for the more interesting reason: the interval excludes zero,
     * but the effect is no bigger than what this tenant's OWN platform produces
     * when nothing changes at all ({@link NoiseFloor}). Statistically
     * detectable, operationally meaningless. Distinguished from
     * {@link #CANNOT_TELL} because the remedy differs - that one needs more
     * rollouts, this one needs a bigger idea.
     */
    INSIDE_NOISE_FLOOR,

    /**
     * Not enough paired cases to compute an interval at all (fewer than
     * {@link Bootstrap#MIN_CLUSTERS_FOR_INTERVAL}). Reported as its own state
     * rather than folded into {@link #CANNOT_TELL} so the console can say
     * "run more cases" instead of "no difference found".
     */
    INSUFFICIENT_DATA
}
