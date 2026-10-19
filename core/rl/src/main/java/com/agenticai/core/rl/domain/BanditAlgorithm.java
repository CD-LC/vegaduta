package com.agenticai.core.rl.domain;

/**
 * Which arm-selection strategy {@link com.agenticai.core.rl.policy.ContextualBanditService#selectArm}
 * uses for a given {@link AgentPolicy}. Both read the same {@link PolicyArm}
 * posterior (alpha/beta/pulls/rewardSum) - this only changes how an arm is
 * picked, not what's tracked.
 */
public enum BanditAlgorithm {
    /** Thompson sampling: draw theta ~ Beta(alpha, beta) per arm, pick the argmax sample. Good default - naturally balances explore/exploit and handles non-stationary rewards gracefully. */
    THOMPSON,
    /** UCB1: pick the arm maximizing avgReward + sqrt(2*ln(totalPulls)/armPulls). Deterministic given the same counts (no per-call randomness) and front-loads exploration of under-tried arms more aggressively than Thompson's epsilon-floor alone. */
    UCB1
}
