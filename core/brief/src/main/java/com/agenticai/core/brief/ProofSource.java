package com.agenticai.core.brief;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Where trust evidence comes from. The trust ladder decides from THIS and nothing else - it never
 * invents a number - so the proof ledger (CORE 3) implements it over real eval runs, guardrail
 * events, message ratings and agent experiments, and a test double implements it over fixed
 * values. When no bean implements it the ladder reports "the proof ledger is not connected" and
 * proposes nothing.
 *
 * <p>Implementations read in the caller's tenant context and must return only what was measured;
 * an unmeasured quantity is empty / null, never a default that would satisfy a rule.
 */
public interface ProofSource {

    /** Evidence for the brief's envoy as of {@code now}. */
    ProofSnapshot snapshot(UUID briefId, Instant now);

    /**
     * @param planAllowsEval false when the tenant's plan has no eval runs (then no proposal is ever made)
     * @param planNote human sentence explaining a false {@code planAllowsEval}, else null
     * @param observedSince when the envoy began collecting evidence at its CURRENT rung (apply time or the
     *     last rung change) - the 7-day window is measured from here
     * @param scoreHistory eval-run scores on the envoy's target agent since {@code observedSince}, oldest first
     * @param realRuns number of real (non-eval) runs handled since {@code observedSince}
     * @param guardrailStrikes guardrail strikes on any envoy agent since {@code observedSince}
     * @param ratedTurns thumbs-rated turns since {@code observedSince}
     * @param thumbsUp how many of those were thumbs-up
     * @param experiment the latest experiment of this version against the previous one, or null
     * @param baseline the first measured eval score (the envoy's baseline), or null while none exists
     * @param noiseFloor the measured score noise floor, or null when it could not be estimated
     */
    record ProofSnapshot(
        boolean planAllowsEval,
        String planNote,
        Instant observedSince,
        List<ScorePoint> scoreHistory,
        int realRuns,
        int guardrailStrikes,
        int ratedTurns,
        int thumbsUp,
        ExperimentResult experiment,
        Double baseline,
        Double noiseFloor
    ) {
        public ProofSnapshot {
            scoreHistory = scoreHistory == null ? List.of() : List.copyOf(scoreHistory);
        }
    }

    record ScorePoint(Instant at, double score) {
    }

    /**
     * @param significant the win is outside the noise floor (not just a higher number)
     * @param delta score difference against the previous version (positive is better)
     */
    record ExperimentResult(boolean significant, double delta, Double noiseFloor, String summary) {
    }
}
