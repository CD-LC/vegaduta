package com.agenticai.core.quality.stats;

/**
 * How much this tenant's eval numbers move when NOTHING changes.
 *
 * <p><strong>Why a platform needs its own floor.</strong> Two runs of the
 * identical configuration do not agree. The model samples, the judge samples,
 * tool latency varies, a retry fires, a provider silently rolls a minor
 * version. The resulting disagreement is the resolution limit of the whole
 * measurement apparatus: any reported improvement smaller than it is
 * indistinguishable from rerunning the same thing twice. That floor is
 * TENANT-SPECIFIC - it depends on which provider arm they are on, which judge
 * model was picked, how long their tool chains are, how many cases they have -
 * so it cannot be a platform constant and must be measured per tenant, per
 * dataset.
 *
 * <p><strong>Why this is the first thing to build.</strong> Without it, the
 * honest ceiling on every A/B claim is unknown, and a console will render +2pp
 * as a win. The 2026 paired-noise-floor protocol that motivated this package
 * measured the floor on tau^2-bench retail and found that seven of ten
 * published multi-agent coordination architectures reported headline effects
 * below it (arXiv 2605.13841). A tenant shipping prompt changes off +2pp deltas
 * is making the same mistake in miniature, every week.
 *
 * @param pairedCases how many cases were measured under both reruns
 * @param baselineRollouts total rollouts in the first rerun
 * @param variantRollouts total rollouts in the second rerun
 * @param meanAbsoluteDelta mean over cases of {@code |rerun2 - rerun1|} - the
 *     point estimate of trial-to-trial disagreement
 * @param maxAbsoluteDelta the single worst per-case disagreement observed;
 *     printed because a suite with one wildly unstable case and nine stable
 *     ones has a small mean and a real problem
 * @param absoluteDeltaInterval paired bootstrap interval over cases for
 *     {@code meanAbsoluteDelta}; null when there were too few cases to
 *     resample, in which case {@link #floor()} falls back to
 *     {@code maxAbsoluteDelta}
 * @param floor the value an observed effect must EXCEED to be reportable -
 *     the upper end of {@code absoluteDeltaInterval} when available (so the
 *     bar is the plausible worst case of pure noise, not its average),
 *     otherwise {@code maxAbsoluteDelta}
 * @param signedMeanDelta mean over cases of {@code rerun2 - rerun1} - this
 *     SHOULD be near zero for a genuine rerun; it is the self-check
 * @param signedDeltaInterval paired bootstrap interval for
 *     {@code signedMeanDelta}, or null when not computable
 * @param configurationSuspect true when {@code signedDeltaInterval} excludes
 *     zero, i.e. the two "identical" runs differ SYSTEMATICALLY rather than
 *     randomly. That is evidence the configuration was not actually identical
 *     (agent prompt edited, provider arm changed, judge model reordered by a
 *     catalog change - {@code EvalRun#judgeModel} exists precisely because
 *     that last one is invisible otherwise). A suspect pair is a drift
 *     finding, not a noise floor, and callers must surface it instead of
 *     quietly using the number
 */
public record NoiseFloor(
    int pairedCases,
    int baselineRollouts,
    int variantRollouts,
    double meanAbsoluteDelta,
    double maxAbsoluteDelta,
    ConfidenceInterval absoluteDeltaInterval,
    double floor,
    double signedMeanDelta,
    ConfidenceInterval signedDeltaInterval,
    boolean configurationSuspect
) {

    public NoiseFloor {
        if (pairedCases < 1) {
            throw new IllegalArgumentException(
                "A noise floor needs at least one paired case; with none, report that the floor is "
                    + "UNKNOWN rather than constructing a floor of zero - a zero floor would wave every "
                    + "delta through, which is worse than having no floor at all");
        }
        if (floor < 0.0) {
            throw new IllegalArgumentException("A noise floor cannot be negative, got " + floor);
        }
    }

    /**
     * Whether an observed effect is large enough to be distinguishable from
     * this platform's own rerun-to-rerun variation.
     *
     * <p>Compares the ABSOLUTE effect against the floor, because the floor is a
     * magnitude of disagreement with no direction. Strictly greater-than: an
     * effect exactly at the floor is not above it.
     */
    public boolean clears(double observedDelta) {
        return Math.abs(observedDelta) > floor;
    }
}
