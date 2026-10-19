package com.agenticai.core.agents.collab;

/**
 * Settings for the swarm goal verifier ({@code app.swarm.verifier.*}). OFF by default; when off no
 * {@link SwarmVerifier} is ever built, so {@code delegate_parallel} briefs, schema and result are unchanged.
 *
 * @param enabled         master switch
 * @param maxRepairRounds how many times unanswered sub-tasks may be re-dispatched (0 = verify only)
 * @param resultChars     per-worker-result cap (and per-sub-task cap) inside the verifier prompt
 * @param briefChars      cap on the parent-goal text embedded in each structured worker brief
 * @param timeoutSeconds  wall-clock bound on EACH verifier model call; a stall yields verdict UNVERIFIED
 */
public record SwarmVerifierConfig(boolean enabled, int maxRepairRounds, int resultChars, int briefChars,
                                  int timeoutSeconds) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final SwarmVerifierConfig DISABLED =
        new SwarmVerifierConfig(false, 1, 1500, 1200, DEFAULT_TIMEOUT_SECONDS);

    public SwarmVerifierConfig(boolean enabled, int maxRepairRounds, int resultChars, int briefChars) {
        this(enabled, maxRepairRounds, resultChars, briefChars, DEFAULT_TIMEOUT_SECONDS);
    }

    public SwarmVerifierConfig {
        maxRepairRounds = Math.max(0, Math.min(maxRepairRounds, 3));
        resultChars = Math.max(200, resultChars);
        briefChars = Math.max(200, briefChars);
        timeoutSeconds = Math.max(1, Math.min(timeoutSeconds, 120));
    }
}
