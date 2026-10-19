package com.agenticai.core.quality.stats;

import java.util.List;
import java.util.Random;

/**
 * Measures a tenant's own noise floor by comparing two runs of an UNCHANGED
 * configuration - the control experiment that makes every other number in this
 * package interpretable.
 *
 * <p><strong>The protocol.</strong> Run the same dataset, against the same
 * agent, with nothing changed, twice. Pair the two runs case by case and look
 * at the disagreements. Two things come out:
 * <ul>
 *   <li>The mean ABSOLUTE per-case disagreement, and an interval for it. That
 *       is the resolution limit: an A/B effect smaller than this is
 *       indistinguishable from doing nothing twice, and
 *       {@link PairedComparison} tests against it.</li>
 *   <li>The mean SIGNED disagreement, and an interval for it. This is the
 *       self-check, and it should straddle zero. If it does not, the two runs
 *       differ systematically rather than randomly, which means the
 *       configuration was NOT unchanged - somebody edited the agent, the
 *       provider arm moved, or {@code JudgeChatClientFactory} picked a
 *       different judge because the model catalog was reordered. That is a
 *       drift finding, and {@link NoiseFloor#configurationSuspect()} carries
 *       it out rather than letting a contaminated floor be used as if it were
 *       clean.</li>
 * </ul>
 *
 * <p><strong>Why the floor is the interval's UPPER end, not the mean.</strong>
 * The floor is used as a bar that a claimed improvement must clear. Using the
 * mean disagreement would let roughly half of pure-noise reruns clear their own
 * floor. Using the upper end of the interval sets the bar at the plausible
 * worst case of noise, which is the conservative direction, and conservative is
 * the correct direction for a gate whose failure mode is shipping a change that
 * does nothing. When there are too few cases to compute an interval at all, the
 * fallback is the worst single observed disagreement - also conservative, and
 * also honest about being crude.
 *
 * <p><strong>What this cannot do.</strong> It can only measure the variance of
 * things that actually varied between the two runs. If both runs happened to hit
 * the same cached provider response, or the same judge with temperature 0 on a
 * deterministic case, the measured floor understates the real one. It is a lower
 * bound on the platform's variability, and it must never be presented as a
 * guarantee. Refreshing it on a schedule (the unchanged configuration, rerun
 * weekly) is what keeps it from going stale as providers and prompts move
 * underneath.
 *
 * <p>Pure static computation; scheduling and persistence are deliberately NOT
 * here - see {@code EvalReliabilityService} for how the pair of runs is chosen
 * from what has already been stored.
 */
public final class NoiseFloorEstimator {

    private NoiseFloorEstimator() {
        // Static utility - never instantiated.
    }

    /** {@link #estimate(List, double, int, long)} at the platform defaults. */
    public static NoiseFloor estimate(List<PairedCaseOutcomes> reruns, long seed) {
        return estimate(
            reruns, WilsonInterval.DEFAULT_CONFIDENCE_LEVEL, Bootstrap.DEFAULT_RESAMPLES, seed);
    }

    /**
     * Estimate the floor from a paired rerun of the identical configuration.
     *
     * @param reruns per-case pairs where {@code baseline} is the first rerun and
     *     {@code variant} the second. The naming is inherited from
     *     {@link PairedCaseOutcomes} and is a slight abuse here on purpose:
     *     reusing the paired type means the floor and the A/B it gates are
     *     computed by the same resampling code, so they cannot drift apart in
     *     units or in method
     * @param confidenceLevel nominal coverage for both intervals
     * @param resamples bootstrap replicates
     * @param seed deterministic bootstrap seed
     * @throws IllegalArgumentException when {@code reruns} is null or empty - a
     *     floor of zero would wave every delta through, which is worse than
     *     having no floor at all, so the absence must be propagated as an
     *     absence
     */
    public static NoiseFloor estimate(
        List<PairedCaseOutcomes> reruns, double confidenceLevel, int resamples, long seed
    ) {
        if (reruns == null || reruns.isEmpty()) {
            throw new IllegalArgumentException(
                "No paired rerun cases - the noise floor is UNKNOWN, which callers must report as "
                    + "unknown. Constructing a zero floor here would silently approve every delta.");
        }
        Bootstrap.requireResamples(resamples);
        Bootstrap.requireLevel(confidenceLevel);

        int caseCount = reruns.size();
        int firstRunRollouts = 0;
        int secondRunRollouts = 0;
        double absoluteSum = 0.0;
        double signedSum = 0.0;
        double maxAbsolute = 0.0;
        for (PairedCaseOutcomes pair : reruns) {
            firstRunRollouts += pair.baseline().size();
            secondRunRollouts += pair.variant().size();
            double delta = pair.delta();
            signedSum += delta;
            double absolute = Math.abs(delta);
            absoluteSum += absolute;
            maxAbsolute = Math.max(maxAbsolute, absolute);
        }
        double meanAbsoluteDelta = absoluteSum / caseCount;
        double signedMeanDelta = signedSum / caseCount;

        ConfidenceInterval absoluteInterval = null;
        ConfidenceInterval signedInterval = null;
        if (caseCount >= Bootstrap.MIN_CLUSTERS_FOR_INTERVAL) {
            // One pass, two statistics, ONE resampling stream: the absolute and
            // signed intervals describe the same resampled case draws, so they
            // are mutually consistent (a signed interval computed off a
            // different random stream could contradict its own absolute
            // counterpart).
            Random random = new Random(seed);
            double[] absoluteReplicates = new double[resamples];
            double[] signedReplicates = new double[resamples];
            for (int r = 0; r < resamples; r++) {
                double absoluteReplicateSum = 0.0;
                double signedReplicateSum = 0.0;
                for (int c = 0; c < caseCount; c++) {
                    PairedCaseOutcomes drawn = reruns.get(random.nextInt(caseCount));
                    double delta = drawn.delta();
                    absoluteReplicateSum += Math.abs(delta);
                    signedReplicateSum += delta;
                }
                absoluteReplicates[r] = absoluteReplicateSum / caseCount;
                signedReplicates[r] = signedReplicateSum / caseCount;
            }
            absoluteInterval = Bootstrap.intervalFromReplicates(
                absoluteReplicates, confidenceLevel, IntervalMethod.PAIRED_BOOTSTRAP);
            signedInterval = Bootstrap.intervalFromReplicates(
                signedReplicates, confidenceLevel, IntervalMethod.PAIRED_BOOTSTRAP);
        }

        double floor = absoluteInterval != null ? absoluteInterval.high() : maxAbsolute;
        boolean configurationSuspect = signedInterval != null && signedInterval.excludesZero();

        return new NoiseFloor(
            caseCount,
            firstRunRollouts,
            secondRunRollouts,
            meanAbsoluteDelta,
            maxAbsolute,
            absoluteInterval,
            floor,
            signedMeanDelta,
            signedInterval,
            configurationSuspect);
    }
}
