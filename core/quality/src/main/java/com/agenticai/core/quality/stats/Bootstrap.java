package com.agenticai.core.quality.stats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Percentile bootstrap confidence intervals, flat and hierarchical - the
 * interval machinery for everything that is NOT a proportion.
 *
 * <p><strong>Why a bootstrap at all.</strong> {@link WilsonInterval} covers
 * pass rates. It does not cover the mean judge score, mean latency, mean token
 * cost, or a signed A/B delta, and there is no usable closed form for those at
 * {@code n = 5}: the t-interval needs approximate normality of the sampling
 * distribution, which a bounded, heavily-skewed, frequently-bimodal judge
 * score at tiny {@code n} does not have. Resampling makes no distributional
 * assumption beyond "the observed sample is representative", which is the
 * weakest assumption available and the one already implicit in reporting the
 * mean at all.
 *
 * <p><strong>Why hierarchical, and what goes wrong without it.</strong> An
 * agent eval suite is NESTED: rollouts sit inside cases, and rollouts of the
 * same case are far more alike than rollouts of different cases (same prompt,
 * same expected tools, same difficulty). A flat bootstrap that resamples all
 * {@code cases x rollouts} measurements as one pool implicitly claims
 * {@code cases * rollouts} independent observations of suite quality when
 * there are really only {@code cases} of them, and the interval it returns is
 * too narrow - it will shrink if you simply rerun the same cases more times,
 * which is an interval that can be bought rather than earned. The two-level
 * version resamples CASES first and only then rollouts within each drawn case,
 * so adding rollouts reduces within-case uncertainty (correctly) without ever
 * pretending the suite covered more ground than it did. This is the pairing
 * that the 2026 statistical-precipice work recommends alongside Wilson
 * intervals for exactly this nested structure (arXiv 2606.20695).
 *
 * <p><strong>Why the seed is a parameter.</strong> A resampling interval is a
 * random quantity. If the seed were internal, two loads of the same console
 * page would show two different intervals for unchanged data, which destroys
 * the credibility of the whole feature faster than having no interval at all.
 * Every entry point therefore takes a seed, and the service layer derives it
 * deterministically from the identity of the data being summarised.
 *
 * <p><strong>Percentile method, stated plainly.</strong> This is the plain
 * percentile bootstrap: resample, recompute the statistic, and read the
 * empirical quantiles. It is not BCa and does not correct for bias or
 * skewness, so for strongly skewed statistics at very small {@code n} its
 * coverage is somewhat below nominal. Saying so is better than implying
 * precision nobody computed; BCa is a reasonable later upgrade and would
 * arrive as a new {@link IntervalMethod} constant rather than a silent change
 * of meaning for an existing one.
 */
public final class Bootstrap {

    /**
     * Default resample count. 2000 is comfortably past the point where the
     * Monte-Carlo error of a 95% percentile interval stops mattering at the
     * two decimal places a console prints, and is still sub-millisecond for
     * the sample sizes here (tens of cases, single-digit rollouts).
     */
    public static final int DEFAULT_RESAMPLES = 2000;

    /** Below this many clusters a percentile interval is not reportable - see {@link #hierarchicalMeanOfCaseMeans}. */
    public static final int MIN_CLUSTERS_FOR_INTERVAL = 2;

    private Bootstrap() {
        // Static utility - never instantiated.
    }

    /**
     * Percentile bootstrap interval for the mean of a FLAT sample.
     *
     * <p>Use for a single case's rollouts, or for any sample that genuinely
     * has no cluster structure. For a whole suite use
     * {@link #hierarchicalMeanOfCaseMeans} instead - see this class's javadoc
     * for why the flat version understates a suite interval.
     *
     * @param values the observed sample; at least 2 values (a one-element
     *     sample has no spread to resample, and an interval of zero width from
     *     one observation is the exact overstatement this package exists to
     *     stop)
     * @param resamples bootstrap replicate count, {@code >= 100}
     * @param confidenceLevel nominal two-sided coverage, e.g. {@code 0.95}
     * @param seed deterministic seed - see this class's javadoc
     */
    public static ConfidenceInterval forMean(
        double[] values, int resamples, double confidenceLevel, long seed
    ) {
        if (values == null || values.length < 2) {
            throw new IllegalArgumentException(
                "A percentile bootstrap needs at least 2 observations; got "
                    + (values == null ? "null" : String.valueOf(values.length))
                    + ". One observation is not a measurement of spread.");
        }
        requireResamples(resamples);
        requireLevel(confidenceLevel);
        Random random = new Random(seed);
        double[] replicateMeans = new double[resamples];
        for (int r = 0; r < resamples; r++) {
            double sum = 0.0;
            for (int i = 0; i < values.length; i++) {
                sum += values[random.nextInt(values.length)];
            }
            replicateMeans[r] = sum / values.length;
        }
        return intervalFromReplicates(
            replicateMeans, confidenceLevel, IntervalMethod.PERCENTILE_BOOTSTRAP);
    }

    /**
     * Two-level (hierarchical) percentile bootstrap interval for the suite
     * statistic "mean over cases of that case's mean measurement".
     *
     * <p>Each replicate draws {@code cases.size()} cases with replacement;
     * for each drawn case it then draws that case's own number of rollouts
     * with replacement from that case's measurements, takes the case mean, and
     * averages the case means. Cases are weighted EQUALLY regardless of how
     * many rollouts each has - a case that happened to be rerun more often is
     * not a more important case, and weighting by rollout count would let the
     * suite aggregate drift as reruns accumulate unevenly.
     *
     * <p>The point estimate the interval accompanies is therefore the
     * unweighted mean of case means, NOT the grand mean over all rollouts.
     * Callers must report the same statistic they asked for an interval on;
     * {@link #meanOfCaseMeans(List)} is provided so that cannot drift apart.
     *
     * @param cases one entry per case, each with at least one measurement; at
     *     least {@link #MIN_CLUSTERS_FOR_INTERVAL} cases, because a
     *     case-resampling interval over a single case is vacuous (every
     *     replicate draws the same case) and would print a spuriously narrow
     *     range
     */
    public static ConfidenceInterval hierarchicalMeanOfCaseMeans(
        List<CaseMeasurements> cases, int resamples, double confidenceLevel, long seed
    ) {
        if (cases == null || cases.size() < MIN_CLUSTERS_FOR_INTERVAL) {
            throw new IllegalArgumentException(
                "A hierarchical bootstrap needs at least " + MIN_CLUSTERS_FOR_INTERVAL + " cases; got "
                    + (cases == null ? "null" : String.valueOf(cases.size()))
                    + ". With one case there is nothing to resample at the case level.");
        }
        requireResamples(resamples);
        requireLevel(confidenceLevel);
        Random random = new Random(seed);
        int caseCount = cases.size();
        double[] replicateMeans = new double[resamples];
        for (int r = 0; r < resamples; r++) {
            double sumOfCaseMeans = 0.0;
            for (int c = 0; c < caseCount; c++) {
                CaseMeasurements drawnCase = cases.get(random.nextInt(caseCount));
                List<Double> caseValues = drawnCase.values();
                int rollouts = caseValues.size();
                double caseSum = 0.0;
                for (int i = 0; i < rollouts; i++) {
                    caseSum += caseValues.get(random.nextInt(rollouts));
                }
                sumOfCaseMeans += caseSum / rollouts;
            }
            replicateMeans[r] = sumOfCaseMeans / caseCount;
        }
        return intervalFromReplicates(
            replicateMeans, confidenceLevel, IntervalMethod.HIERARCHICAL_BOOTSTRAP);
    }

    /**
     * The point estimate that {@link #hierarchicalMeanOfCaseMeans} produces an
     * interval for: the unweighted mean of per-case means.
     */
    public static double meanOfCaseMeans(List<CaseMeasurements> cases) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("No cases - there is no suite mean to report");
        }
        double sum = 0.0;
        for (CaseMeasurements measurements : cases) {
            sum += measurements.mean();
        }
        return sum / cases.size();
    }

    /**
     * Turns a set of bootstrap replicates into a percentile interval. Exposed
     * (rather than private) because {@link PairedComparison} and
     * {@link NoiseFloorEstimator} generate their own replicates with a
     * different resampling scheme but must read the quantiles identically -
     * two implementations of "where do the tails go" would eventually
     * disagree by one index and nobody would notice.
     *
     * @param replicates the bootstrap distribution of the statistic; mutated
     *     by sorting, so pass an array you own
     */
    public static ConfidenceInterval intervalFromReplicates(
        double[] replicates, double confidenceLevel, IntervalMethod method
    ) {
        requireLevel(confidenceLevel);
        if (replicates == null || replicates.length == 0) {
            throw new IllegalArgumentException("No bootstrap replicates to read quantiles from");
        }
        Arrays.sort(replicates);
        double tail = (1.0 - confidenceLevel) / 2.0;
        double low = percentile(replicates, tail);
        double high = percentile(replicates, 1.0 - tail);
        return new ConfidenceInterval(low, high, confidenceLevel, method);
    }

    /**
     * Linear-interpolated quantile of an ALREADY-SORTED ascending array (the
     * R type-7 / {@code numpy.percentile} default definition:
     * position {@code q*(n-1)}, interpolating between neighbours).
     *
     * <p>Public because the definition matters: "the 2.5th percentile" is
     * ambiguous across at least nine published conventions, and a reviewer
     * reproducing a printed interval in a spreadsheet needs to know which one
     * this is. Interpolation rather than nearest-rank so that the interval
     * moves smoothly as replicates are added instead of stepping.
     */
    public static double percentile(double[] sortedAscending, double quantile) {
        if (sortedAscending == null || sortedAscending.length == 0) {
            throw new IllegalArgumentException("Cannot take a percentile of an empty sample");
        }
        if (quantile < 0.0 || quantile > 1.0) {
            throw new IllegalArgumentException("quantile must lie in [0, 1], got " + quantile);
        }
        if (sortedAscending.length == 1) {
            return sortedAscending[0];
        }
        double position = quantile * (sortedAscending.length - 1);
        int lowerIndex = (int) Math.floor(position);
        int upperIndex = (int) Math.ceil(position);
        if (lowerIndex == upperIndex) {
            return sortedAscending[lowerIndex];
        }
        double fraction = position - lowerIndex;
        return sortedAscending[lowerIndex]
            + fraction * (sortedAscending[upperIndex] - sortedAscending[lowerIndex]);
    }

    /**
     * Flattens a nested sample into the flat pool a naive bootstrap would use.
     * Exists so tests can demonstrate the understatement described in this
     * class's javadoc rather than it being an untested claim in a comment.
     */
    public static double[] flatten(List<CaseMeasurements> cases) {
        List<Double> pooled = new ArrayList<>();
        for (CaseMeasurements measurements : cases) {
            pooled.addAll(measurements.values());
        }
        double[] flat = new double[pooled.size()];
        for (int i = 0; i < flat.length; i++) {
            flat[i] = pooled.get(i);
        }
        return flat;
    }

    static void requireResamples(int resamples) {
        if (resamples < 100) {
            throw new IllegalArgumentException(
                "resamples must be at least 100 for the tail quantiles to mean anything, got " + resamples);
        }
    }

    static void requireLevel(double confidenceLevel) {
        if (!(confidenceLevel > 0.0) || !(confidenceLevel < 1.0)) {
            throw new IllegalArgumentException(
                "confidenceLevel must be strictly between 0 and 1, got " + confidenceLevel);
        }
    }
}
