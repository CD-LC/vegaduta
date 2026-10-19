package com.agenticai.core.rl.api;

import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.domain.RewardSource;
import com.agenticai.core.rl.reward.RewardService;
import com.agenticai.core.rl.trajectory.TrajectoryService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Default {@link RlTurnRecorder} - delegates episode/step recording to
 * {@link TrajectoryService} and reward-component accrual to
 * {@link RewardService}, so the orchestrator drives both halves of a turn's
 * bandit record through the single published seam.
 */
@Service
public class DefaultRlTurnRecorder implements RlTurnRecorder {

    private final TrajectoryService trajectoryService;
    private final RewardService rewardService;

    public DefaultRlTurnRecorder(TrajectoryService trajectoryService, RewardService rewardService) {
        this.trajectoryService = trajectoryService;
        this.rewardService = rewardService;
    }

    @Override
    public UUID beginTurn(UUID agentId, String sessionId, String turnRef, String contextJson, Map<PolicyDimension, UUID> selectedArms) {
        Episode episode = trajectoryService.beginEpisode(agentId, sessionId, turnRef, contextJson, selectedArms);
        return episode.getId();
    }

    @Override
    public void recordStep(UUID episodeId, String role, String actionType, String content, String toolName, Long tokens, Long latencyMs) {
        trajectoryService.recordStep(episodeId, role, actionType, content, toolName, tokens, latencyMs);
    }

    @Override
    public void endTurn(UUID episodeId, boolean terminal) {
        trajectoryService.endEpisode(episodeId, terminal);
    }

    @Override
    public void addReward(UUID episodeId, RewardSource source, double value, Double weight) {
        rewardService.addRewardRecord(
            episodeId, source, BigDecimal.valueOf(value), weight == null ? null : BigDecimal.valueOf(weight));
    }

    @Override
    public Double computeReward(UUID episodeId) {
        return rewardService.computeReward(episodeId);
    }
}
