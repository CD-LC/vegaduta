package com.agenticai.core.quality.gate;

import com.agenticai.core.quality.stats.BinomialReliability;
import com.agenticai.core.quality.stats.WilsonInterval;
import java.util.List;

/**
 * The promotion gate's reliability arithmetic for REPEATED eval runs: a pure
 * function, no Spring, no I/O.
 *
 * <p>A run with {@code repeats > 1} replays every case several times. The
 * run's stored {@code overall_score} is the MEAN judge score, which hides
 * flakiness: a case that passes two of three repetitions still contributes a
 * healthy mean and (by the run's majority rule) a passing case. For an agent
 * that runs unattended that is the wrong question - the operations question is
 * pass^k, "did EVERY repetition pass?" (see {@link BinomialReliability}).
 *
 * <p>Per case, pass^k is evaluated with {@code k = rollouts} (every
 * repetition must pass): {@code BinomialReliability.passHatK(n, c, n)} is 1
 * iff {@code c == n}, else 0. The CASE, not the repetition, is the
 * independent unit (repetitions of one case share a prompt and are correlated),
 * so the headline is the fraction of cases that passed ALL their repetitions,
 * and the gate compares the Wilson 95% LOWER bound of that fraction with the
 * configured bar. Lower bound, not point estimate: 5 of 5 cases is not
 * evidence of a 100% reliable agent, and the bound shrinks honestly with few
 * cases.
 */
public final class PassHatKGate {

    private PassHatKGate() {
        // Static utility.
    }

    /** One case's repetition outcome: {@code rollouts} replays, {@code passes} of them passed. */
    public record CaseRollouts(int rollouts, int passes) {
    }

    /**
     * @param applicable false when there was nothing to evaluate (no case with
     *     more than one rollout) - the caller must then keep the mean-based
     *     verdict untouched
     * @param passed whether the lower bound met {@code threshold}
     * @param cases cases evaluated
     * @param casesAllPassed cases whose every repetition passed
     * @param passHatK point estimate {@code casesAllPassed / cases}
     * @param lowerBound Wilson lower bound of {@code passHatK}
     * @param threshold the bar the lower bound was compared with
     */
    public record Decision(
        boolean applicable,
        boolean passed,
        int cases,
        int casesAllPassed,
        double passHatK,
        double lowerBound,
        double threshold
    ) {
        static Decision notApplicable(double threshold) {
            return new Decision(false, true, 0, 0, 0.0, 0.0, threshold);
        }
    }

    /**
     * Decides the gate from per-case repetition outcomes.
     *
     * @param cases per-case outcomes; entries with {@code rollouts < 2} are
     *     ignored (a single rollout carries no repetition information, and the
     *     existing mean verdict already covers it)
     * @param minLowerBound required Wilson lower bound in [0, 1]
     */
    public static Decision decide(List<CaseRollouts> cases, double minLowerBound) {
        if (Double.isNaN(minLowerBound) || minLowerBound < 0.0 || minLowerBound > 1.0) {
            throw new IllegalArgumentException("minLowerBound must lie in [0, 1], got " + minLowerBound);
        }
        if (cases == null) {
            return Decision.notApplicable(minLowerBound);
        }
        int evaluated = 0;
        int allPassed = 0;
        for (CaseRollouts c : cases) {
            if (c == null || c.rollouts() < 2) {
                continue;
            }
            int passes = Math.max(0, Math.min(c.passes(), c.rollouts()));
            evaluated++;
            if (BinomialReliability.passHatK(c.rollouts(), passes, c.rollouts()) >= 1.0) {
                allPassed++;
            }
        }
        if (evaluated == 0) {
            return Decision.notApplicable(minLowerBound);
        }
        double lower = WilsonInterval.forProportion(allPassed, evaluated).low();
        double point = (double) allPassed / evaluated;
        // Epsilon-safe: a bar of exactly the bound must pass.
        boolean passed = lower >= minLowerBound - 1e-9;
        return new Decision(true, passed, evaluated, allPassed, point, lower, minLowerBound);
    }
}
