package com.agenticai.core.rl.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The Agent Learning dashboard read model (consumed by the web
 * {@code api.getRlSummary} → {@code AgentLearningPanel}): the human-feedback
 * tallies, the reward trend aggregated PER DAY over this agent's rewarded
 * episodes, the bandit arms table, and the current per-dimension policy state.
 * Field names/shapes match that panel exactly - every section degrades to empty
 * rather than error, so a brand-new agent renders a calm empty dashboard.
 */
public record RlSummaryResponse(
    FeedbackCounts feedbackCounts,
    List<RewardPoint> rewardTrend,
    List<ArmStat> arms,
    List<PolicyState> policies
) {

    /**
     * Human reward-signal counts. {@code up/down/rating/edit} are the four the
     * dashboard's stat cards read; {@code resolved/unresolved/avgRating/total}
     * are carried for completeness (the panel ignores extras).
     */
    public record FeedbackCounts(
        long up,
        long down,
        long rating,
        long edit,
        long resolved,
        long unresolved,
        Double avgRating,
        long total
    ) {
    }

    /**
     * One day on the reward trend: {@code avgReward} is the mean composite
     * reward of that day's rewarded episodes and {@code count} how many there
     * were ({@code date} is an ISO {@code yyyy-MM-dd} label).
     */
    public record RewardPoint(String date, double avgReward, long count) {
    }

    /** One bandit arm's learned posterior + usage, for the arms table. */
    public record ArmStat(
        String dimension,
        UUID policyId,
        UUID armId,
        String armKey,
        String armConfigJson,
        String contextBucket,
        long pulls,
        double avgReward,
        double alpha,
        double beta,
        boolean enabled
    ) {
    }

    /** Current state of one per-dimension policy (flat - the fields the policy editor reads). */
    public record PolicyState(
        String dimension,
        UUID policyId,
        boolean enabled,
        BigDecimal explorationRate,
        String algorithm
    ) {
    }
}
