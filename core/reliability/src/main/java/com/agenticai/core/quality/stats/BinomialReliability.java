package com.agenticai.core.quality.stats;

/**
 * pass@k and pass^k: two numbers that are routinely confused and answer
 * completely different business questions.
 *
 * <p><strong>pass@k is a hiring question.</strong> "If I let this agent try
 * {@code k} times, will it EVER get this case right?" That is the right
 * question when a human screens the output, when the agent proposes and a
 * reviewer picks, or when you are asking whether the capability exists at
 * all. pass@k goes UP with {@code k} and asymptotically to 1 for any agent
 * with a non-zero success probability, which is precisely why it flatters:
 * at a per-attempt pass rate of 0.9, pass@5 is 0.99999. No customer
 * experiences 0.99999 of anything.
 *
 * <p><strong>pass^k is an operations question.</strong> "If this runs
 * {@code k} times unattended, will it get EVERY one right?" That is the right
 * question when the agent answers a phone line, files a ticket, moves money,
 * or acts without a human in the loop. pass^k goes DOWN with {@code k}, as
 * roughly {@code p^k}. Same agent, same {@code p = 0.9}, and pass^5 is 0.59.
 * The gap between those two numbers - 0.99999 versus 0.59 - is the entire
 * reliability story, and only one of them is honest to put in front of an
 * operations buyer. tau-bench introduced pass^k for exactly this reason and
 * reported GPT-4o under 50% at pass^1 and under 25% at pass^8 on its retail
 * domain, which is the shape of the decay in practice (arXiv 2406.12045).
 *
 * <p><strong>Why unbiased estimators rather than plug-in powers.</strong>
 * Given {@code n} observed rollouts of which {@code c} passed, the naive
 * approach is to estimate {@code p = c/n} and then raise it to a power. That
 * is biased, badly so at small {@code n}: {@code (c/n)^k} systematically
 * mis-estimates the probability that {@code k} fresh draws all succeed, and
 * {@code 1 - (1 - c/n)^k} likewise for pass@k. The standard fix, and the one
 * used here, is the combinatorial estimator over the rollouts you actually
 * have - draw {@code k} of the {@code n} observed rollouts without
 * replacement:
 * <ul>
 *   <li>pass@k {@code = 1 - C(n-c, k) / C(n, k)} (Chen et al. 2021, the
 *       HumanEval estimator)</li>
 *   <li>pass^k {@code = C(c, k) / C(n, k)} (tau-bench)</li>
 * </ul>
 * Both are computed as telescoping products here rather than via factorials,
 * so {@code n = 400} does not overflow and no {@code BigInteger} is needed.
 *
 * <p><strong>The misapplication to avoid.</strong> The 2026 critique of
 * coding-agent benchmarks found pass@k being computed with {@code k} derived
 * from the number of TEST ASSERTIONS rather than the number of INDEPENDENT
 * ROLLOUTS - conflating test-suite size with attempt independence. Corrected,
 * reported scores of 0.96-0.98 became 0.00-0.12 (arXiv 2608.14711). Every
 * method here takes {@code n} as a rollout count and says so; if the number
 * you are about to pass came from counting checks, dimensions or judges, stop.
 *
 * <p>Pure static arithmetic, deliberately not a Spring bean.
 */
public final class BinomialReliability {

    private BinomialReliability() {
        // Static utility - never instantiated.
    }

    /**
     * The per-rollout pass rate, {@code c/n} - the number the platform used
     * to print on its own as "the score".
     *
     * <p>Kept as a named method rather than inlined division so that call
     * sites read as a metric and so the {@code n <= 0} guard lives in one
     * place: with no rollouts there is no rate, and returning {@code 0.0}
     * would turn "we measured nothing" into "it failed everything" - the same
     * honest-absence rule {@code EvaluationResult} and
     * {@code EvalRunVerdict.INCONCLUSIVE} already enforce.
     *
     * <p>Parameter order is {@code (rollouts, successes)} - the same order as
     * {@link #passAtK} and {@link #passHatK}, deliberately, because all three
     * take the same two unlabelled ints and a class where one of them is
     * reversed is a class that will eventually be called with them swapped.
     * The guard below makes the swap throw rather than silently invert the
     * rate for every case except {@code c == n}, but throwing in production
     * is not the goal: agreeing with its siblings is.
     *
     * @param rollouts {@code n}, the number of rollouts observed
     * @param successes {@code c}, how many of them passed
     */
    public static double passAtOne(int rollouts, int successes) {
        requireCounts(successes, rollouts);
        return (double) successes / rollouts;
    }

    /**
     * Unbiased pass@k: the probability that AT LEAST ONE of {@code k}
     * independent attempts succeeds, estimated from {@code successes} of
     * {@code rollouts} observed attempts.
     *
     * <p>{@code 1 - C(n-c, k)/C(n, k)}, evaluated as
     * {@code 1 - prod_{i=0..k-1} (n-c-i)/(n-i)}.
     *
     * <p>Answers the HIRING question - see this class's javadoc. Reaching for
     * this number to describe unattended production behaviour is the most
     * common way an agent platform overstates its reliability.
     *
     * @param rollouts {@code n}, the number of INDEPENDENT rollouts observed
     * @param successes {@code c}, how many of them passed
     * @param k attempts the consumer would be allowed; must satisfy
     *     {@code 1 <= k <= n}, because an estimate of "any of 10 succeeds"
     *     cannot be drawn from 5 observations without assuming the model of
     *     the world this estimator exists to avoid assuming
     */
    public static double passAtK(int rollouts, int successes, int k) {
        requireCounts(successes, rollouts);
        requireK(k, rollouts);
        int failures = rollouts - successes;
        if (failures < k) {
            // Fewer failures than draws: every possible k-subset contains at
            // least one success, so the estimator is exactly 1.
            return 1.0;
        }
        double probabilityAllDrawnFail = 1.0;
        for (int i = 0; i < k; i++) {
            probabilityAllDrawnFail *= (double) (failures - i) / (rollouts - i);
        }
        return 1.0 - probabilityAllDrawnFail;
    }

    /**
     * Unbiased pass^k: the probability that ALL {@code k} independent attempts
     * succeed, estimated from {@code successes} of {@code rollouts} observed
     * attempts.
     *
     * <p>{@code C(c, k)/C(n, k)}, evaluated as
     * {@code prod_{i=0..k-1} (c-i)/(n-i)}.
     *
     * <p>Answers the OPERATIONS question - see this class's javadoc. This is
     * the reliability number a customer actually buys, and the gap between it
     * and {@link #passAtOne(int, int)} is the single most informative column
     * the quality console can show.
     *
     * @param rollouts {@code n}, the number of INDEPENDENT rollouts observed
     * @param successes {@code c}, how many of them passed
     * @param k consecutive successes required; {@code 1 <= k <= n}
     */
    public static double passHatK(int rollouts, int successes, int k) {
        requireCounts(successes, rollouts);
        requireK(k, rollouts);
        if (successes < k) {
            // Not enough observed successes to fill a k-subset: the estimator
            // is exactly 0. (Honestly 0, not "unavailable" - we did observe
            // k rollouts and they did not all pass.)
            return 0.0;
        }
        double probabilityAllDrawnPass = 1.0;
        for (int i = 0; i < k; i++) {
            probabilityAllDrawnPass *= (double) (successes - i) / (rollouts - i);
        }
        return probabilityAllDrawnPass;
    }

    /**
     * pass@k computed from a KNOWN per-attempt probability:
     * {@code 1 - (1-p)^k}.
     *
     * <p>Not for estimating from data - {@link #passAtK(int, int, int)} is
     * the estimator. This exists for the forward direction: "our measured
     * rate is 0.9; what does that imply at k=5?", the calculation that makes
     * the pass@k / pass^k gap concrete (0.99999 versus 0.59049 at p=0.9).
     */
    public static double passAtKFromRate(double perAttemptPassRate, int k) {
        requireRate(perAttemptPassRate);
        requirePositiveK(k);
        return 1.0 - Math.pow(1.0 - perAttemptPassRate, k);
    }

    /**
     * pass^k computed from a KNOWN per-attempt probability: {@code p^k},
     * assuming independent attempts.
     *
     * <p>The independence assumption is load-bearing and is the reason the
     * eval runner must give each rollout fresh session state: shared
     * conversation history, a warm provider prefix cache or a memoised tool
     * result correlates rollouts, and correlated rollouts make this number
     * optimistic in the same direction as every other shortcut here.
     */
    public static double passHatKFromRate(double perAttemptPassRate, int k) {
        requireRate(perAttemptPassRate);
        requirePositiveK(k);
        return Math.pow(perAttemptPassRate, k);
    }

    private static void requireCounts(int successes, int rollouts) {
        if (rollouts <= 0) {
            throw new IllegalArgumentException(
                "Need at least one rollout - zero rollouts is an absent measurement, not a score of zero.");
        }
        if (successes < 0 || successes > rollouts) {
            throw new IllegalArgumentException(
                "successes (" + successes + ") must lie in [0, rollouts=" + rollouts + "]");
        }
    }

    private static void requireK(int k, int rollouts) {
        requirePositiveK(k);
        if (k > rollouts) {
            throw new IllegalArgumentException(
                "k (" + k + ") exceeds the observed rollout count (" + rollouts + ") - this estimator "
                    + "only reports what the rollouts can support. Run more rollouts, or lower k.");
        }
    }

    private static void requirePositiveK(int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be at least 1, got " + k);
        }
    }

    private static void requireRate(double rate) {
        if (Double.isNaN(rate) || rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException("A pass rate must lie in [0, 1], got " + rate);
        }
    }
}
