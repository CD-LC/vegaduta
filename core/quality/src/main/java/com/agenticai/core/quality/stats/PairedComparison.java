package com.agenticai.core.quality.stats;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Paired A/B comparison of two configurations run over the SAME eval cases.
 *
 * <p><strong>Why paired, and why it is not a stylistic preference.</strong>
 * The obvious way to compare two agent configurations is to compute each one's
 * mean score with its own confidence interval and see whether the intervals
 * overlap. That test is far weaker than the data deserves, because both
 * intervals are dominated by a nuisance quantity: BETWEEN-CASE variance. Eval
 * suites contain easy cases and brutal cases, and the spread of case difficulty
 * is normally several times larger than the effect of any prompt tweak. Written
 * out, the variance of an unpaired difference of means over {@code m} cases is
 * <pre>
 *   Var(Abar - Bbar) = (Var(A) + Var(B)) / m
 * </pre>
 * while the paired statistic, the mean of the per-case differences
 * {@code d_i = variant_i - baseline_i}, has
 * <pre>
 *   Var(dbar) = (Var(A) + Var(B) - 2*Cov(A,B)) / m
 * </pre>
 * and {@code Cov(A,B)} is LARGE and POSITIVE precisely because the same case
 * drives both arms: a case both configurations find hard scores low in both
 * columns. With a realistic correlation of 0.8 between arms, the paired
 * variance is one fifth of the unpaired one, so the paired interval is roughly
 * 2.2x narrower for the same number of runs. That is the difference between
 * "cannot tell" and a usable answer, bought with zero extra model spend - the
 * only free lunch in this package, and the reason the A/B protocol insists on
 * the same cases in the same order.
 *
 * <p><strong>What must actually be held constant.</strong> Same cases, same
 * case order, and as much else as the platform can control. Anything that
 * differs between the arms other than the change under test leaks into the
 * delta and is indistinguishable from it. The reconstructed-rollout path in
 * this module cannot guarantee, for instance, that the judge model was the same
 * for both runs ({@code JudgeChatClientFactory} picks the first catalog row, and
 * a catalog reorder silently changes it - which is exactly why
 * {@code EvalRun#judgeModel} is stored), so callers must compare that
 * provenance and warn when it differs rather than assuming.
 *
 * <p><strong>Two independent tests, reported together.</strong> The bootstrap
 * interval uses the magnitudes of the per-case deltas; the exact sign test uses
 * only their directions. They fail in different ways - one outlier case can
 * swing the former, a uniform-but-tiny shift can look impressive to the latter -
 * so a claim that both support is much harder to talk yourself into than a
 * claim supported by one p-value.
 *
 * <p>Pure static computation; no Spring, no clock, no persistence.
 */
public final class PairedComparison {

    /**
     * Largest number of discordant cases for which the exact sign test is
     * computed. Above this the binomial point mass {@code 0.5^n} underflows a
     * {@code double}, and rather than silently returning 0 (which would read as
     * infinitely significant) the p-value is reported as absent. No real eval
     * suite comes close; the cap exists so that the failure mode is an honest
     * null instead of a fabricated certainty.
     */
    public static final int MAX_DISCORDANT_FOR_EXACT_SIGN_TEST = 1000;

    private PairedComparison() {
        // Static utility - never instantiated.
    }

    /** {@link #compare(List, double, int, long, NoiseFloor)} at the platform defaults. */
    public static PairedComparisonResult compare(
        List<PairedCaseOutcomes> pairs, long seed, NoiseFloor noiseFloor
    ) {
        return compare(
            pairs,
            WilsonInterval.DEFAULT_CONFIDENCE_LEVEL,
            Bootstrap.DEFAULT_RESAMPLES,
            seed,
            noiseFloor);
    }

    /**
     * Compare two arms measured on the same cases.
     *
     * @param pairs one entry per case measured under BOTH arms. Cases present
     *     in only one arm must be dropped by the caller before this call - a
     *     half-paired case is not comparable and silently including it would
     *     reintroduce the between-case variance pairing exists to remove
     * @param confidenceLevel nominal two-sided coverage for the delta interval
     * @param resamples bootstrap replicates
     * @param seed deterministic bootstrap seed, so the same comparison renders
     *     the same interval on every page load
     * @param noiseFloor this tenant's measured rerun-to-rerun floor, or null
     *     when it has not been measured. MUST NOT be a floor derived from the
     *     very two runs being compared - that would be circular: the
     *     "unchanged" pair and the "changed" pair would be the same data. The
     *     service layer enforces this by sourcing the floor from a different
     *     pair of runs
     * @throws IllegalArgumentException if {@code pairs} is null or empty (an
     *     empty comparison is not a tie, it is an absent experiment)
     */
    public static PairedComparisonResult compare(
        List<PairedCaseOutcomes> pairs,
        double confidenceLevel,
        int resamples,
        long seed,
        NoiseFloor noiseFloor
    ) {
        if (pairs == null || pairs.isEmpty()) {
            throw new IllegalArgumentException(
                "No paired cases - there is no comparison to report. Render the absence.");
        }
        Bootstrap.requireResamples(resamples);
        Bootstrap.requireLevel(confidenceLevel);

        int caseCount = pairs.size();
        int baselineRollouts = 0;
        int variantRollouts = 0;
        double baselineMeanSum = 0.0;
        double variantMeanSum = 0.0;
        double deltaSum = 0.0;
        int variantBetter = 0;
        int baselineBetter = 0;
        int tied = 0;
        boolean unreplicated = false;
        for (PairedCaseOutcomes pair : pairs) {
            baselineRollouts += pair.baseline().size();
            variantRollouts += pair.variant().size();
            baselineMeanSum += pair.baselineMean();
            variantMeanSum += pair.variantMean();
            double delta = pair.delta();
            deltaSum += delta;
            if (delta > 0.0) {
                variantBetter++;
            } else if (delta < 0.0) {
                baselineBetter++;
            } else {
                tied++;
            }
            unreplicated |= pair.unreplicated();
        }
        double meanDelta = deltaSum / caseCount;
        double baselineMean = baselineMeanSum / caseCount;
        double variantMean = variantMeanSum / caseCount;

        ConfidenceInterval deltaInterval = null;
        if (caseCount >= Bootstrap.MIN_CLUSTERS_FOR_INTERVAL) {
            deltaInterval = pairedBootstrap(pairs, confidenceLevel, resamples, seed);
        }
        Double signTestPValue = signTestPValue(baselineBetter, variantBetter);

        ComparisonVerdict verdict = adjudicate(deltaInterval, meanDelta, noiseFloor);
        String explanation = explain(verdict, meanDelta, deltaInterval, noiseFloor, caseCount, unreplicated);

        return new PairedComparisonResult(
            caseCount,
            baselineRollouts,
            variantRollouts,
            baselineMean,
            variantMean,
            meanDelta,
            deltaInterval,
            verdict,
            noiseFloor != null ? noiseFloor.floor() : null,
            variantBetter,
            baselineBetter,
            tied,
            signTestPValue,
            unreplicated,
            explanation);
    }

    /**
     * The paired bootstrap: each replicate draws whole CASE PAIRS with
     * replacement (keeping both arms of a drawn case together - that is the
     * pairing) and then resamples rollouts within each arm of each drawn case.
     *
     * <p>Drawing the pair as a unit is what cancels case difficulty. Drawing
     * the two arms independently would be an unpaired bootstrap wearing this
     * method's name, and would hand back the wide interval this whole approach
     * exists to avoid.
     */
    private static ConfidenceInterval pairedBootstrap(
        List<PairedCaseOutcomes> pairs, double confidenceLevel, int resamples, long seed
    ) {
        Random random = new Random(seed);
        int caseCount = pairs.size();
        double[] replicates = new double[resamples];
        for (int r = 0; r < resamples; r++) {
            double deltaSum = 0.0;
            for (int c = 0; c < caseCount; c++) {
                PairedCaseOutcomes drawn = pairs.get(random.nextInt(caseCount));
                double baseline = resampleMean(drawn.baseline(), random);
                double variant = resampleMean(drawn.variant(), random);
                deltaSum += variant - baseline;
            }
            replicates[r] = deltaSum / caseCount;
        }
        return Bootstrap.intervalFromReplicates(
            replicates, confidenceLevel, IntervalMethod.PAIRED_BOOTSTRAP);
    }

    private static double resampleMean(List<Double> values, Random random) {
        int size = values.size();
        if (size == 1) {
            // Nothing to resample: one observation bootstraps to itself. The
            // within-case uncertainty is then genuinely unmeasured, which is
            // what the unreplicated flag on the result reports.
            return values.get(0);
        }
        double sum = 0.0;
        for (int i = 0; i < size; i++) {
            sum += values.get(random.nextInt(size));
        }
        return sum / size;
    }

    /**
     * Verdict rules, in this order and for these reasons:
     * <ol>
     *   <li>No interval (too few paired cases) -&gt; INSUFFICIENT_DATA. The
     *       honest answer to "which is better" from three cases is "ask again
     *       with more cases", not a direction.</li>
     *   <li>Interval straddles zero -&gt; CANNOT_TELL. Checked BEFORE the noise
     *       floor because it is the weaker requirement: an effect whose
     *       interval includes zero fails regardless of what the floor says, and
     *       reporting INSIDE_NOISE_FLOOR for it would imply we had established
     *       a real-but-small effect.</li>
     *   <li>Interval excludes zero but the effect does not clear the measured
     *       floor -&gt; INSIDE_NOISE_FLOOR. Statistically detectable,
     *       operationally meaningless.</li>
     *   <li>Otherwise the interval's SIGN names the winner - the interval, not
     *       {@code meanDelta}. They agree in practice, and when a skewed
     *       percentile interval makes them disagree, the interval is the thing
     *       the claim was made from.</li>
     * </ol>
     */
    private static ComparisonVerdict adjudicate(
        ConfidenceInterval deltaInterval, double meanDelta, NoiseFloor noiseFloor
    ) {
        if (deltaInterval == null) {
            return ComparisonVerdict.INSUFFICIENT_DATA;
        }
        if (deltaInterval.straddlesZero()) {
            return ComparisonVerdict.CANNOT_TELL;
        }
        if (noiseFloor != null && !noiseFloor.clears(meanDelta)) {
            return ComparisonVerdict.INSIDE_NOISE_FLOOR;
        }
        return deltaInterval.low() > 0.0
            ? ComparisonVerdict.VARIANT_BETTER
            : ComparisonVerdict.BASELINE_BETTER;
    }

    private static String explain(
        ComparisonVerdict verdict,
        double meanDelta,
        ConfidenceInterval interval,
        NoiseFloor noiseFloor,
        int caseCount,
        boolean unreplicated
    ) {
        String delta = formatSigned(meanDelta);
        String range = interval == null
            ? "no interval"
            : String.format(Locale.ROOT, "%.0f%% CI [", interval.confidenceLevel() * 100.0)
                + format(interval.low()) + ", " + format(interval.high()) + "]";
        String replication = unreplicated
            ? " At least one case was measured only once per arm, so this comparison is unreplicated."
            : "";
        return switch (verdict) {
            case INSUFFICIENT_DATA -> "Only " + caseCount + " paired case(s) - too few to compute a "
                + "confidence interval, so no direction can be claimed (observed mean delta " + delta
                + ")." + replication;
            case CANNOT_TELL -> "Cannot tell: mean paired delta " + delta + ", " + range
                + ", which includes zero - the data is consistent with no difference over " + caseCount
                + " case(s)." + replication;
            case INSIDE_NOISE_FLOOR -> "Cannot tell: mean paired delta " + delta + " (" + range
                + ") is real but no larger than this dataset's measured rerun noise floor of "
                + format(noiseFloor == null ? 0.0 : noiseFloor.floor())
                + ", so it is indistinguishable from running the unchanged configuration twice."
                + replication;
            case VARIANT_BETTER -> "Variant better: mean paired delta " + delta + ", " + range
                + " entirely above zero over " + caseCount + " case(s)"
                + floorClause(noiseFloor) + "." + replication;
            case BASELINE_BETTER -> "Baseline better: mean paired delta " + delta + ", " + range
                + " entirely below zero over " + caseCount + " case(s)"
                + floorClause(noiseFloor) + "." + replication;
        };
    }

    private static String floorClause(NoiseFloor noiseFloor) {
        if (noiseFloor == null) {
            return ", with no measured noise floor to test the effect size against";
        }
        return ", clearing the measured noise floor of " + format(noiseFloor.floor());
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /** Always carries an explicit sign - a delta's direction is the point. */
    private static String formatSigned(double value) {
        return String.format(Locale.ROOT, "%+.4f", value);
    }

    /**
     * Exact two-sided sign test over the discordant cases - McNemar's test in
     * its exact binomial form.
     *
     * <p>Under the null hypothesis that the two configurations are equivalent,
     * each case that moved at all was equally likely to move either way, so the
     * number moving the variant's way is {@code Binomial(discordant, 0.5)}. The
     * two-sided p-value is twice the lower tail, capped at 1.
     *
     * <p><strong>Exact, not chi-square.</strong> The familiar
     * {@code (|b-c|-1)^2/(b+c)} chi-square form is an approximation that is
     * unreliable for small numbers of discordant pairs - and "small" here means
     * the single-digit counts a 12-case eval suite produces. The exact binomial
     * is as easy to compute and does not need a chi-square CDF (which the JDK
     * does not provide; see {@link WilsonInterval}'s javadoc for the same
     * missing-special-function problem and the same refusal to approximate in a
     * class whose job is honesty).
     *
     * <p>Concordant (tied) cases are excluded entirely, which is the test's
     * defining feature: it asks only about cases where something changed.
     *
     * @param baselineOnlyWins cases where the baseline arm did better
     * @param variantOnlyWins cases where the variant arm did better
     * @return the two-sided p-value, or null when there are no discordant cases
     *     (nothing moved, so there is nothing to test) or more than
     *     {@link #MAX_DISCORDANT_FOR_EXACT_SIGN_TEST} of them
     */
    public static Double signTestPValue(int baselineOnlyWins, int variantOnlyWins) {
        if (baselineOnlyWins < 0 || variantOnlyWins < 0) {
            throw new IllegalArgumentException("Case counts cannot be negative");
        }
        int discordant = baselineOnlyWins + variantOnlyWins;
        if (discordant == 0 || discordant > MAX_DISCORDANT_FOR_EXACT_SIGN_TEST) {
            return null;
        }
        int smallerTail = Math.min(baselineOnlyWins, variantOnlyWins);
        // Iterative PMF: pmf(0) = 0.5^n, pmf(i+1) = pmf(i) * (n-i)/(i+1).
        // Avoids binomial coefficients (which overflow) and factorials.
        double pmf = Math.pow(0.5, discordant);
        double lowerTail = pmf;
        for (int i = 0; i < smallerTail; i++) {
            pmf = pmf * (discordant - i) / (i + 1);
            lowerTail += pmf;
        }
        return Math.min(1.0, 2.0 * lowerTail);
    }
}
