package com.agenticai.core.quality.stats;

import java.util.List;

/**
 * Suite-level reliability: turns a list of per-case rollout vectors into the
 * handful of numbers a quality console should show instead of one score.
 *
 * <p><strong>What this replaces.</strong> {@code EvalRun#overallScore} is the
 * mean of one measurement per case from one execution of the suite. It is a
 * perfectly good summary of THAT EXECUTION and a poor estimate of the agent,
 * and the console cannot currently tell the two apart. The summary produced
 * here reports, side by side:
 * <ul>
 *   <li><strong>pass@1</strong> with a Wilson interval - the per-rollout pass
 *       rate, pooled over every rollout of every case (the "micro" rate). The
 *       headline number, now with the uncertainty attached.</li>
 *   <li><strong>pass@k</strong> - would it ever get these right in k tries
 *       (the hiring question).</li>
 *   <li><strong>pass^k</strong> with a bootstrap interval over cases - would
 *       it get them ALL right k times running (the operations question). The
 *       gap between this and pass@1 is the column a customer actually buys.</li>
 *   <li><strong>k</strong> itself, and the replication spread, so nobody has
 *       to guess what the other three were computed at.</li>
 * </ul>
 *
 * <p><strong>Why k is the MINIMUM rollout count, not the maximum or the mean.</strong>
 * Reconstructed repeats are ragged in practice: a case added to the dataset
 * last week has fewer observed rollouts than one that has been there for
 * months. pass@k and pass^k are only defined per case for {@code k <= n_i},
 * so the largest k the whole suite can honestly support is
 * {@code min_i n_i}. Using the mean or max would force either imputation or
 * silent per-case k variation, and a suite number computed at different k for
 * different cases is not a suite number. The consequence is deliberate and
 * visible: ONE unreplicated case drags the suite's reportable k down to 1, and
 * the report says so rather than quietly reporting a flattering k.
 *
 * <p><strong>Why cases are weighted equally.</strong> pass^k is averaged over
 * cases, not pooled over rollouts, because an operations buyer cares about
 * "every case in my suite", and a case that happens to have been rerun more
 * often must not get a louder vote. pass@1 IS pooled over rollouts, because
 * there it is the per-attempt rate that is being described. Two different
 * questions, two different weightings, both stated.
 *
 * <p>Pure computation over values; no repository, no Spring, no clock.
 */
public final class ReliabilityStats {

    private ReliabilityStats() {
        // Static utility - never instantiated.
    }

    /**
     * Everything this module can honestly say about one suite's reliability.
     *
     * <p>Interval fields are NULLABLE by design, and null means "not
     * computable from this data", never "zero width": a suite with a single
     * case has no case-level sampling distribution to resample, so
     * {@code passHatKInterval} is absent rather than invented. Callers must
     * render the absence (the REST layer omits the field).
     *
     * @param cases how many distinct eval cases contributed at least one rollout
     * @param rollouts total rollouts observed across all cases
     * @param minRolloutsPerCase the least-replicated case's rollout count -
     *     equal to {@link #k()}
     * @param maxRolloutsPerCase the most-replicated case's rollout count;
     *     printed because a big spread between min and max is the signal that
     *     the suite's replication is ragged
     * @param passedRollouts how many of {@code rollouts} passed
     * @param fullyPassedCases how many cases passed EVERY one of their observed
     *     rollouts - the empirical strict-success case count
     * @param passAtOne pooled per-rollout pass rate
     * @param passAtOneInterval Wilson interval for {@code passAtOne}; never
     *     null (there is always at least one rollout if there is at least one
     *     case)
     * @param k the attempt count pass@k / pass^k were computed at
     * @param passAtK mean over cases of the unbiased pass@k estimator
     * @param passAtKInterval percentile bootstrap over cases for
     *     {@code passAtK}, or null with fewer than
     *     {@link Bootstrap#MIN_CLUSTERS_FOR_INTERVAL} cases
     * @param passHatK mean over cases of the unbiased pass^k estimator
     * @param passHatKInterval percentile bootstrap over cases for
     *     {@code passHatK}, or null with fewer than
     *     {@link Bootstrap#MIN_CLUSTERS_FOR_INTERVAL} cases
     * @param unreplicated true when {@code k == 1}: no case was run twice, so
     *     every number here is a point estimate from single observations and
     *     the console must badge it as unreplicated rather than present it as
     *     a reliability measurement
     */
    public record SuiteReliability(
        int cases,
        int rollouts,
        int minRolloutsPerCase,
        int maxRolloutsPerCase,
        int passedRollouts,
        int fullyPassedCases,
        double passAtOne,
        ConfidenceInterval passAtOneInterval,
        int k,
        double passAtK,
        ConfidenceInterval passAtKInterval,
        double passHatK,
        ConfidenceInterval passHatKInterval,
        boolean unreplicated
    ) {

        /**
         * The reliability gap: how much of the headline pass rate survives the
         * requirement that it hold {@code k} times running. Zero at
         * {@code k == 1} by construction, which is another way of saying an
         * unreplicated suite cannot report reliability at all.
         */
        public double reliabilityGap() {
            return passAtOne - passHatK;
        }
    }

    /** {@link #summarize(List, double, int, long)} at the platform defaults. */
    public static SuiteReliability summarize(List<CaseRollouts> cases, long seed) {
        return summarize(
            cases, WilsonInterval.DEFAULT_CONFIDENCE_LEVEL, Bootstrap.DEFAULT_RESAMPLES, seed);
    }

    /**
     * Summarise a suite.
     *
     * @param cases one entry per eval case, each with at least one rollout;
     *     must be non-empty (an empty suite is an absent measurement, and this
     *     method refuses to manufacture a zero for it)
     * @param confidenceLevel nominal coverage for both intervals
     * @param resamples bootstrap replicates for the pass^k interval
     * @param seed deterministic bootstrap seed - see {@link Bootstrap} for why
     *     this is a parameter rather than an internal {@code Random}
     */
    public static SuiteReliability summarize(
        List<CaseRollouts> cases, double confidenceLevel, int resamples, long seed
    ) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException(
                "No cases - an empty suite has no reliability to report. Render the absence.");
        }
        Bootstrap.requireResamples(resamples);
        Bootstrap.requireLevel(confidenceLevel);

        int totalRollouts = 0;
        int passedRollouts = 0;
        int fullyPassedCases = 0;
        int minRollouts = Integer.MAX_VALUE;
        int maxRollouts = 0;
        for (CaseRollouts caseRollouts : cases) {
            int n = caseRollouts.rollouts();
            int c = caseRollouts.successes();
            totalRollouts += n;
            passedRollouts += c;
            if (c == n) {
                fullyPassedCases++;
            }
            minRollouts = Math.min(minRollouts, n);
            maxRollouts = Math.max(maxRollouts, n);
        }

        int k = minRollouts;
        // The two argument orders on these adjacent lines are both deliberate
        // and neither is a typo: BinomialReliability's estimators take
        // (n, c[, k]) to match the Chen et al. / tau-bench formulations they
        // implement, while WilsonInterval takes (successes, trials) to match
        // the near-universal binomial-CI convention (R's binom.test(x, n),
        // statsmodels' proportion_confint(count, nobs)). Both guard against a
        // swap by rejecting successes > n, so a mistake here throws rather
        // than quietly inverting a rate.
        double passAtOne = BinomialReliability.passAtOne(totalRollouts, passedRollouts);
        ConfidenceInterval passAtOneInterval =
            WilsonInterval.forProportion(passedRollouts, totalRollouts, confidenceLevel);

        double[] perCasePassAtK = new double[cases.size()];
        double[] perCasePassHatK = new double[cases.size()];
        for (int i = 0; i < cases.size(); i++) {
            CaseRollouts caseRollouts = cases.get(i);
            perCasePassAtK[i] =
                BinomialReliability.passAtK(caseRollouts.rollouts(), caseRollouts.successes(), k);
            perCasePassHatK[i] =
                BinomialReliability.passHatK(caseRollouts.rollouts(), caseRollouts.successes(), k);
        }
        double suitePassAtK = mean(perCasePassAtK);
        double suitePassHatK = mean(perCasePassHatK);

        ConfidenceInterval passAtKInterval = null;
        ConfidenceInterval passHatKInterval = null;
        if (cases.size() >= Bootstrap.MIN_CLUSTERS_FOR_INTERVAL) {
            // Same seed for both: they resample the SAME case draws, which is
            // what makes the pair internally consistent (a pass^k interval must
            // never imply a set of cases that the pass@k interval contradicts).
            passAtKInterval = caseLevelBootstrap(perCasePassAtK, confidenceLevel, resamples, seed);
            passHatKInterval = caseLevelBootstrap(perCasePassHatK, confidenceLevel, resamples, seed);
        }

        return new SuiteReliability(
            cases.size(),
            totalRollouts,
            minRollouts,
            maxRollouts,
            passedRollouts,
            fullyPassedCases,
            passAtOne,
            passAtOneInterval,
            k,
            suitePassAtK,
            passAtKInterval,
            suitePassHatK,
            passHatKInterval,
            k == 1
        );
    }

    /**
     * Percentile bootstrap over CASES of an already-per-case statistic.
     *
     * <p>Single-level on purpose, and labelled {@link
     * IntervalMethod#PERCENTILE_BOOTSTRAP} rather than
     * {@code HIERARCHICAL_BOOTSTRAP} to say so honestly: the within-case
     * sampling uncertainty is already handled analytically by the unbiased
     * pass^k estimator, so the only thing left to resample is WHICH CASES the
     * suite happens to contain. Resampling rollouts again underneath would
     * double-count that uncertainty.
     */
    private static ConfidenceInterval caseLevelBootstrap(
        double[] perCaseStatistic, double confidenceLevel, int resamples, long seed
    ) {
        java.util.Random random = new java.util.Random(seed);
        int caseCount = perCaseStatistic.length;
        double[] replicates = new double[resamples];
        for (int r = 0; r < resamples; r++) {
            double sum = 0.0;
            for (int c = 0; c < caseCount; c++) {
                sum += perCaseStatistic[random.nextInt(caseCount)];
            }
            replicates[r] = sum / caseCount;
        }
        return Bootstrap.intervalFromReplicates(
            replicates, confidenceLevel, IntervalMethod.PERCENTILE_BOOTSTRAP);
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }
}
