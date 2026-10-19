package com.agenticai.core.quality.stats;

import java.util.List;

/**
 * One eval case measured under BOTH arms of an A/B - the unit the paired
 * comparison resamples.
 *
 * <p>The pairing is the whole point: the same case, with the same prompt, the
 * same expected tools and the same intrinsic difficulty, run under the
 * baseline configuration and under the variant. Because both arms saw the same
 * case, the case's difficulty cancels out of the per-case difference, and the
 * comparison stops being dominated by which cases happened to be in the suite.
 * See {@link PairedComparison} for the arithmetic of why that matters so much.
 *
 * <p>The two arms need NOT have the same number of rollouts. Ragged arms are
 * normal when repeats are reconstructed from however many runs each
 * configuration happened to get, and the per-case difference of means is
 * defined regardless. Equal rollout counts are still preferable - the paired
 * bootstrap is tighter when they match - but requiring them would mean
 * discarding real data.
 *
 * @param caseKey stable case identifier, the same convention as
 *     {@link CaseRollouts#caseKey()}; the key is what makes two measurements
 *     a PAIR rather than two unrelated samples
 * @param baseline the incumbent arm's measurements for this case, one per
 *     rollout; never null/empty
 * @param variant the challenger arm's measurements for this case, one per
 *     rollout; never null/empty
 */
public record PairedCaseOutcomes(String caseKey, List<Double> baseline, List<Double> variant) {

    public PairedCaseOutcomes {
        if (caseKey == null || caseKey.isBlank()) {
            throw new IllegalArgumentException(
                "caseKey is required - without it there is no pairing, and an unpaired comparison of the "
                    + "same data is far weaker (see PairedComparison's javadoc)");
        }
        baseline = requireMeasurements(caseKey, "baseline", baseline);
        variant = requireMeasurements(caseKey, "variant", variant);
    }

    private static List<Double> requireMeasurements(String caseKey, String arm, List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                "caseKey=" + caseKey + " has no " + arm + " measurements - a case measured under only one "
                    + "arm is not a pair and must be dropped from the comparison, not half-counted");
        }
        for (Double value : values) {
            if (value == null || value.isNaN()) {
                throw new IllegalArgumentException(
                    "caseKey=" + caseKey + " carries a null/NaN " + arm + " measurement; drop the "
                        + "unmeasured rollout rather than imputing one");
            }
        }
        return List.copyOf(values);
    }

    /** Convenience for the single-rollout-per-arm shape. */
    public static PairedCaseOutcomes of(String caseKey, double baselineValue, double variantValue) {
        return new PairedCaseOutcomes(caseKey, List.of(baselineValue), List.of(variantValue));
    }

    /** Mean of the baseline arm's rollouts for this case. */
    public double baselineMean() {
        return mean(baseline);
    }

    /** Mean of the variant arm's rollouts for this case. */
    public double variantMean() {
        return mean(variant);
    }

    /**
     * The signed per-case difference, {@code variant - baseline}. Positive
     * means the challenger did better ON THIS CASE. This is the quantity the
     * paired bootstrap averages and builds an interval around.
     */
    public double delta() {
        return variantMean() - baselineMean();
    }

    /** Whether either arm was observed exactly once for this case. */
    public boolean unreplicated() {
        return baseline.size() == 1 || variant.size() == 1;
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (Double value : values) {
            sum += value;
        }
        return sum / values.size();
    }
}
