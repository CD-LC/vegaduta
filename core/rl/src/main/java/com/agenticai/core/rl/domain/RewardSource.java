package com.agenticai.core.rl.domain;

/**
 * Where one {@link RewardRecord} component came from. RewardService blends the
 * non-{@link #SAFETY} components into a weighted, clipped [0,1] scalar;
 * {@link #SAFETY} is not blended - it acts as a veto (any SAFETY violation
 * caps the final reward).
 */
public enum RewardSource {
    /** A human thumbs/rating/edit signal (see {@code FeedbackService}). */
    HUMAN,
    /** An automated LLM-as-judge score. */
    JUDGE,
    /** Programmatic task-success check (e.g. a verifier returned true). */
    TASK_SUCCESS,
    /** Cost efficiency, normalized to [0,1] where higher = cheaper/better. */
    COST,
    /** Latency efficiency, normalized to [0,1] where higher = faster/better. */
    LATENCY,
    /**
     * A safety/guardrail signal. Not blended into the weighted mean - a
     * violation (value &gt; 0) VETOES the reward, capping it at the configured
     * {@code rl.reward.safety-veto-cap}. This is the reward-hacking mitigation
     * posture: no amount of positive HUMAN/JUDGE/TASK_SUCCESS reward can buy
     * back an unsafe turn.
     */
    SAFETY
}
