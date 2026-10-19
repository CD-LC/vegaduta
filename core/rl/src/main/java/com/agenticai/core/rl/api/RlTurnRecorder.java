package com.agenticai.core.rl.api;

import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.domain.RewardSource;
import java.util.Map;
import java.util.UUID;

/**
 * The orchestrator-facing seam for recording one agent turn as a bandit
 * {@code rl_episode} plus its ordered {@code rl_trajectory_step}s, then
 * attaching the verifiable reward components that make up its composite
 * reward. A stable facade over the internal {@code TrajectoryService}/
 * {@code RewardService} so the agent runtime records a turn through one type.
 *
 * <p>Like {@link RlPolicyPort}, this is published but not self-wired: the
 * orchestrator drives {@link #beginTurn}/{@link #recordStep}/{@link #endTurn}
 * around a turn and adds automated reward components ({@code JUDGE},
 * {@code TASK_SUCCESS}, {@code COST}, {@code LATENCY}, {@code SAFETY}) via
 * {@link #addReward}; the human {@code HUMAN} component is added separately by
 * {@code FeedbackService} when a thumbs/rating/edit arrives. Calling
 * {@link #computeReward} recomputes and persists the clipped, safety-vetoed
 * composite from whatever components exist so far.
 */
public interface RlTurnRecorder {

    /**
     * Opens an episode for a turn; returns its id. {@code selectedArms} is
     * every {@link PolicyDimension} that had an arm selected for this turn
     * (empty map when none did) - see {@code TrajectoryService#beginEpisode}'s
     * javadoc for why this is a map rather than a single arm id.
     */
    UUID beginTurn(UUID agentId, String sessionId, String turnRef, String contextJson, Map<PolicyDimension, UUID> selectedArms);

    /** Appends one ordered trajectory step (message / tool call / tool result) to an episode. */
    void recordStep(UUID episodeId, String role, String actionType, String content, String toolName, Long tokens, Long latencyMs);

    /** Marks the episode ended; {@code terminal} = whether this was the turn's final step. */
    void endTurn(UUID episodeId, boolean terminal);

    /** Records one verifiable reward component; {@code weight} null uses the configured default for the source. */
    void addReward(UUID episodeId, RewardSource source, double value, Double weight);

    /** Recomputes and persists the episode's clipped, safety-vetoed composite reward; returns it (or null if absent). */
    Double computeReward(UUID episodeId);
}
