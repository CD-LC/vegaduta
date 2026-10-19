package com.agenticai.core.quality.stats;

import java.util.List;

/**
 * The pass/fail outcomes of every observed rollout of ONE eval case.
 *
 * <p><strong>Why a vector and not a boolean.</strong> The stored shape in this
 * module today is one row per (run, case) carrying one {@code Boolean}-ish
 * outcome, which structurally forces every consumer into a point estimate -
 * exactly the trap {@code EvaluationResponse.isPass()} sets one layer down.
 * Reliability is a property of the DISTRIBUTION of outcomes for a case, so
 * every function in this package takes the vector. Collapsing it is the
 * caller's decision, made visibly, with an interval attached.
 *
 * <p><strong>On "rollout".</strong> One rollout is one independent execution
 * of the case: fresh session, no carried conversation state, no reused
 * cached tool result. Two outcomes produced inside one conversation are not
 * two rollouts, and neither are two judge dimensions scoring one response -
 * treating either as a repeat is the inflation documented in
 * {@link BinomialReliability}.
 *
 * @param caseKey stable identifier for the case across runs. The reliability
 *     service uses the {@code eval_dataset_case} id when present and falls
 *     back to {@code "pos:<position>"}, because case rows are editable and
 *     deletable while results are append-only snapshots
 * @param outcomes one entry per observed rollout, in observation order;
 *     {@code TRUE} = the case passed that rollout. Never null, never empty,
 *     never containing nulls - an unmeasurable rollout (no judge provider, a
 *     provider outage) must be EXCLUDED by the caller rather than smuggled
 *     in as {@code false}, because "we could not measure it" and "the agent
 *     got it wrong" are different facts and this module refuses to blur them
 */
public record CaseRollouts(String caseKey, List<Boolean> outcomes) {

    public CaseRollouts {
        if (caseKey == null || caseKey.isBlank()) {
            throw new IllegalArgumentException("caseKey is required - reliability is reported per case");
        }
        if (outcomes == null || outcomes.isEmpty()) {
            throw new IllegalArgumentException(
                "caseKey=" + caseKey + " has no rollouts - a case with nothing measured must be "
                    + "reported as absent, not as a case that failed");
        }
        // Element-wise rather than contains(null): the immutable lists returned
        // by List.of throw NPE from contains(null) instead of answering it.
        for (Boolean outcome : outcomes) {
            if (outcome == null) {
                throw new IllegalArgumentException(
                    "caseKey=" + caseKey + " carries a null outcome. An unmeasurable rollout must be "
                        + "dropped by the caller, never coerced to false.");
            }
        }
        // Defensive copy: these records are held across bootstrap resampling
        // loops and handed to the REST layer; a caller mutating the list
        // underneath would change an already-reported interval.
        outcomes = List.copyOf(outcomes);
    }

    /** Convenience factory for the common "c of n" shape, successes first. */
    public static CaseRollouts of(String caseKey, int successes, int rollouts) {
        if (rollouts <= 0 || successes < 0 || successes > rollouts) {
            throw new IllegalArgumentException(
                "of(" + caseKey + ", successes=" + successes + ", rollouts=" + rollouts + ") is not a "
                    + "valid count pair");
        }
        List<Boolean> outcomes = new java.util.ArrayList<>(rollouts);
        for (int i = 0; i < rollouts; i++) {
            outcomes.add(i < successes);
        }
        return new CaseRollouts(caseKey, outcomes);
    }

    /** {@code n} - how many independent rollouts were observed for this case. */
    public int rollouts() {
        return outcomes.size();
    }

    /** {@code c} - how many of those rollouts passed. */
    public int successes() {
        int passed = 0;
        for (Boolean outcome : outcomes) {
            if (outcome) {
                passed++;
            }
        }
        return passed;
    }

    /** Empirical pass rate for this case, {@code c/n}. */
    public double passRate() {
        return BinomialReliability.passAtOne(rollouts(), successes());
    }

    /**
     * Whether EVERY observed rollout passed - the empirical pass^n for this
     * case. This is the strict, operations-grade per-case verdict.
     */
    public boolean allPassed() {
        return successes() == rollouts();
    }

    /**
     * Whether this case was observed exactly once. A single observation is
     * not a measurement of reliability, and the console must mark it as such
     * rather than rendering a bare rate - the {@code unreplicated} flag on
     * the REST surface comes from here.
     */
    public boolean unreplicated() {
        return rollouts() == 1;
    }
}
