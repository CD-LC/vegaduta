package com.agenticai.core.quality.stats;

/**
 * Wilson score interval for a binomial proportion - the interval to print
 * next to a pass rate.
 *
 * <p><strong>Why Wilson and not the obvious one.</strong> The textbook
 * "normal approximation" (Wald) interval is
 * {@code p +/- z*sqrt(p(1-p)/n)}, and it is the one everybody reaches for.
 * It is also wrong in exactly the regime eval suites live in:
 * <ul>
 *   <li>At {@code p = 1.0} (10 of 10 cases passed) the Wald width collapses
 *       to ZERO, so the console would print "100%, +/-0" off ten rollouts -
 *       a confidence claim of literally infinite strength from ten samples.
 *       Wilson gives {@code [0.722, 1.000]} for 10/10, which is the truth.</li>
 *   <li>At small {@code n} it routinely runs below 0 or above 1, so it has to
 *       be clipped, and after clipping its actual coverage is far below the
 *       nominal 95%.</li>
 *   <li>Its coverage is poor for small {@code n} and for {@code p} near the
 *       boundaries even before clipping; Wilson's is close to nominal across
 *       the whole range, which is why Agresti &amp; Coull recommended it over
 *       Wald for exactly this case.</li>
 * </ul>
 * Since an eval suite with 12 cases and 5 rollouts is SMALL by statistical
 * standards, and since most of its interesting scores sit near 0 or near 1,
 * the small-n boundary behaviour is not an edge case here - it is the normal
 * case. That is the whole reason this class exists rather than a two-line
 * Wald helper.
 *
 * <p><strong>The formula.</strong> With {@code p = c/n} and {@code z} the
 * standard normal quantile for the requested two-sided level, the interval
 * is centred on a value pulled toward 1/2 rather than on {@code p} itself:
 * <pre>
 *   centre = (p + z^2/(2n)) / (1 + z^2/n)
 *   half   = (z / (1 + z^2/n)) * sqrt( p(1-p)/n + z^2/(4n^2) )
 * </pre>
 * That shrinkage is what keeps the interval inside [0,1] and what keeps it
 * non-degenerate at {@code p = 0} and {@code p = 1}.
 *
 * <p><strong>Why only three confidence levels.</strong> Turning an arbitrary
 * level into {@code z} needs the inverse normal CDF (probit), and the JDK has
 * no such function - {@code java.lang.Math} has no {@code erf}/{@code erfc}
 * either. The options were to hand-roll a rational approximation (Acklam,
 * Moro) or to table the levels a console actually offers. A class whose
 * entire purpose is to stop the platform from overstating precision is the
 * worst possible place to hide a silently-approximate constant, and no caller
 * here needs 97.3%, so this tables 90/95/99 and throws loudly on anything
 * else. If a fourth level is ever genuinely needed, add the constant from a
 * published table rather than reaching for an approximation.
 *
 * <p>All methods are static and side-effect free; the class is not a Spring
 * bean on purpose (pure arithmetic, trivially unit-testable, no reason to
 * route it through the container).
 */
public final class WilsonInterval {

    /**
     * Two-sided standard-normal quantile for 95% coverage
     * ({@code z_{0.975}}). Full double precision rather than the folklore
     * {@code 1.96}: the rounded value shifts a 95% bound by roughly 2e-5,
     * which is invisible in the console but would make known-answer tests
     * lie about which formula is implemented.
     */
    public static final double Z_95 = 1.959963984540054;

    /** Two-sided standard-normal quantile for 90% coverage ({@code z_{0.95}}). */
    public static final double Z_90 = 1.6448536269514722;

    /** Two-sided standard-normal quantile for 99% coverage ({@code z_{0.995}}). */
    public static final double Z_99 = 2.5758293035489004;

    /** The level every metric on this surface uses unless told otherwise. */
    public static final double DEFAULT_CONFIDENCE_LEVEL = 0.95;

    private WilsonInterval() {
        // Static utility - never instantiated.
    }

    /**
     * The standard normal quantile for a two-sided interval at
     * {@code confidenceLevel}.
     *
     * @throws IllegalArgumentException for any level outside the supported
     *     table - deliberately loud, see this class's javadoc
     */
    public static double zFor(double confidenceLevel) {
        if (confidenceLevel == 0.95) {
            return Z_95;
        }
        if (confidenceLevel == 0.90) {
            return Z_90;
        }
        if (confidenceLevel == 0.99) {
            return Z_99;
        }
        throw new IllegalArgumentException(
            "Unsupported confidence level " + confidenceLevel
                + " - only 0.90, 0.95 and 0.99 are tabled here. Add the quantile from a published "
                + "table rather than approximating the probit function (see WilsonInterval's javadoc).");
    }

    /**
     * Wilson score interval at the default 95% level.
     *
     * @param successes number of successes {@code c}, {@code 0 <= c <= n}
     * @param trials number of INDEPENDENT trials {@code n}, strictly positive
     */
    public static ConfidenceInterval forProportion(int successes, int trials) {
        return forProportion(successes, trials, DEFAULT_CONFIDENCE_LEVEL);
    }

    /**
     * Wilson score interval at an explicit level.
     *
     * <p>{@code trials} must be the number of INDEPENDENT trials. This is the
     * exact place the 2026 pass@k misapplication bites: feeding the number of
     * assertions, checks or judge dimensions in here instead of the number of
     * independent rollouts produces an interval that is far too narrow and a
     * headline score that is far too high. If you are tempted to pass
     * anything other than a rollout count, the metric you want is not a
     * binomial proportion.
     *
     * @throws IllegalArgumentException when {@code trials <= 0} (no trials is
     *     not a proportion of zero - it is the absence of a measurement, and
     *     callers must render that absence rather than receive a fabricated
     *     {@code [0,1]}), or when {@code successes} is outside
     *     {@code [0, trials]}
     */
    public static ConfidenceInterval forProportion(int successes, int trials, double confidenceLevel) {
        if (trials <= 0) {
            throw new IllegalArgumentException(
                "Wilson interval needs at least one trial - zero trials is an absent measurement, "
                    + "not a proportion. Render the absence instead (see EvaluationResult.UNAVAILABLE "
                    + "for the same contract one layer up).");
        }
        if (successes < 0 || successes > trials) {
            throw new IllegalArgumentException(
                "successes (" + successes + ") must lie in [0, trials=" + trials + "]");
        }
        double z = zFor(confidenceLevel);
        double n = trials;
        double p = successes / n;
        double zz = z * z;
        double denominator = 1.0 + zz / n;
        double centre = (p + zz / (2.0 * n)) / denominator;
        double half = (z / denominator) * Math.sqrt(p * (1.0 - p) / n + zz / (4.0 * n * n));
        // Clamp: a proportion cannot leave [0,1], and Wilson's own algebra
        // keeps it inside up to floating-point slop - the clamp exists to
        // stop that slop reaching the console as "-0.0000000001".
        double low = Math.max(0.0, centre - half);
        double high = Math.min(1.0, centre + half);
        return new ConfidenceInterval(low, high, confidenceLevel, IntervalMethod.WILSON_SCORE);
    }
}
