package com.agenticai.core.quality.stats;

/**
 * The outcome of one paired A/B - every number a reviewer needs to argue with
 * the conclusion, not just the conclusion.
 *
 * <p>Deliberately fat. The temptation with a comparison type is to return a
 * winner and a delta, which is exactly the shape that lets a 2pp difference on
 * four cases become a product decision. Every field below is something a
 * reviewer asks for the moment they distrust the verdict: how many cases, how
 * many rollouts per arm, which direction each individual case moved, what the
 * interval was, which noise floor it was tested against, and whether anything
 * here was replicated at all.
 *
 * @param pairedCases cases measured under both arms
 * @param baselineRollouts total baseline rollouts across all cases
 * @param variantRollouts total variant rollouts across all cases
 * @param baselineMean unweighted mean over cases of the baseline arm's case means
 * @param variantMean unweighted mean over cases of the variant arm's case means
 * @param meanDelta mean over cases of {@code variant - baseline}; positive
 *     favours the variant. NOT {@code variantMean - baselineMean} in general -
 *     it is that only when every case contributes equally, which is why the
 *     paired statistic is computed per case and then averaged
 * @param deltaInterval paired bootstrap interval for {@code meanDelta}; null
 *     when there were too few paired cases to resample, in which case the
 *     verdict is {@link ComparisonVerdict#INSUFFICIENT_DATA}
 * @param verdict the adjudication - see {@link ComparisonVerdict}, which has
 *     three distinct ways of saying "this does not support a winner"
 * @param noiseFloorTested the {@link NoiseFloor#floor()} the effect was tested
 *     against, or null when no floor was available. Null is NOT a pass: it
 *     means the claim rests on the interval alone and the console should say
 *     the floor is unmeasured
 * @param casesVariantBetter how many individual cases moved in the variant's
 *     favour
 * @param casesBaselineBetter how many moved the other way
 * @param casesTied how many produced exactly equal arm means (common with
 *     single-rollout arms and coarse judges)
 * @param signTestPValue exact two-sided sign-test (McNemar, binomial form)
 *     p-value over the discordant cases, or null when there are no discordant
 *     cases or too many to compute exactly. A distribution-free second opinion
 *     on the bootstrap interval: it uses only WHICH WAY each case moved, not by
 *     how much, so a single wildly-moving case cannot carry it
 * @param unreplicated true when any case had only one rollout in either arm -
 *     the comparison is then between two single observations per case, and the
 *     console must badge it
 * @param explanation one human-readable sentence stating the verdict and its
 *     reason, suitable for a tooltip, a decision memo or a log line. Generated
 *     here rather than in the console so that the REST surface, the logs and
 *     the UI cannot drift into three different stories about the same number
 */
public record PairedComparisonResult(
    int pairedCases,
    int baselineRollouts,
    int variantRollouts,
    double baselineMean,
    double variantMean,
    double meanDelta,
    ConfidenceInterval deltaInterval,
    ComparisonVerdict verdict,
    Double noiseFloorTested,
    int casesVariantBetter,
    int casesBaselineBetter,
    int casesTied,
    Double signTestPValue,
    boolean unreplicated,
    String explanation
) {

    /**
     * Whether this comparison names a winner. False for all three "no"
     * verdicts - the predicate a promotion gate should call instead of
     * inspecting {@code meanDelta}'s sign.
     */
    public boolean namesAWinner() {
        return verdict == ComparisonVerdict.VARIANT_BETTER
            || verdict == ComparisonVerdict.BASELINE_BETTER;
    }
}
