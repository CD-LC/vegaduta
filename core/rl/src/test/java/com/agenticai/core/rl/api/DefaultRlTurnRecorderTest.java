package com.agenticai.core.rl.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.domain.RewardSource;
import com.agenticai.core.rl.reward.RewardService;
import com.agenticai.core.rl.trajectory.TrajectoryService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link DefaultRlTurnRecorder} is the published seam the orchestrator calls: every method must be
 * a faithful delegation to {@link TrajectoryService} / {@link RewardService} - including the
 * double-to-BigDecimal conversion on reward values and a null {@code weight} staying null (which is
 * what selects the configured default weight downstream).
 */
class DefaultRlTurnRecorderTest {

    private final TrajectoryService trajectory = mock(TrajectoryService.class);
    private final RewardService reward = mock(RewardService.class);
    private final DefaultRlTurnRecorder recorder = new DefaultRlTurnRecorder(trajectory, reward);

    @Test
    void beginTurnReturnsTheNewEpisodesId() {
        UUID agentId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        Map<PolicyDimension, UUID> arms = Map.of(PolicyDimension.GEN_PARAMS, UUID.randomUUID());
        Episode episode = new Episode(agentId, "s1", "t1", java.time.Instant.now(), "{}", null);
        ReflectionTestUtils.setField(episode, "id", episodeId);
        when(trajectory.beginEpisode(agentId, "s1", "t1", "{}", arms)).thenReturn(episode);

        assertEquals(episodeId, recorder.beginTurn(agentId, "s1", "t1", "{}", arms));
    }

    @Test
    void stepsAndEpisodeEndPassThroughVerbatim() {
        UUID episodeId = UUID.randomUUID();

        recorder.recordStep(episodeId, "assistant", "message", "hi", null, 12L, 40L);
        recorder.endTurn(episodeId, true);

        verify(trajectory).recordStep(episodeId, "assistant", "message", "hi", null, 12L, 40L);
        verify(trajectory).endEpisode(episodeId, true);
    }

    @Test
    void rewardsConvertToBigDecimalAndNullWeightStaysNull() {
        UUID episodeId = UUID.randomUUID();

        recorder.addReward(episodeId, RewardSource.JUDGE, 0.75, null);
        verify(reward)
                .addRewardRecord(
                        eq(episodeId),
                        eq(RewardSource.JUDGE),
                        eq(BigDecimal.valueOf(0.75)),
                        isNull());

        recorder.addReward(episodeId, RewardSource.COST, 0.5, 0.2);
        verify(reward)
                .addRewardRecord(
                        eq(episodeId),
                        eq(RewardSource.COST),
                        eq(BigDecimal.valueOf(0.5)),
                        eq(BigDecimal.valueOf(0.2)));
    }

    @Test
    void computeRewardDelegatesAndReturnsTheComposite() {
        UUID episodeId = UUID.randomUUID();
        when(reward.computeReward(episodeId)).thenReturn(0.42);

        assertEquals(0.42, recorder.computeReward(episodeId));
    }
}
