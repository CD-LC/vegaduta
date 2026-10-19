package com.agenticai.core.rl.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.langfuse.LangfuseScoreClient;
import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.domain.RewardRecord;
import com.agenticai.core.rl.domain.RewardSource;
import com.agenticai.core.rl.repo.EpisodeRepository;
import com.agenticai.core.rl.repo.RewardRecordRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the verifiable-composite reward math: weighted mean of non-SAFETY
 * components, clipping to [clipMin,clipMax], and the multiplicative SAFETY
 * veto. These three together are the anti-reward-hacking posture, so they are
 * the properties most worth pinning down.
 */
@ExtendWith(MockitoExtension.class)
class RewardServiceTest {

    @Mock
    private EpisodeRepository episodeRepository;
    @Mock
    private RewardRecordRepository rewardRecordRepository;
    @Mock
    private LangfuseScoreClient langfuseScoreClient;

    private RewardService rewardService;
    private Episode episode;
    private UUID episodeId;

    @BeforeEach
    void setUp() {
        // langfuseScoreClient is a plain fire-and-forget dependency here -
        // its mock accepts any pushScore(...) call as a no-op (lenient, same
        // reasoning as the other lenient stubs below), since this test class
        // exists to pin down the reward MATH, not to assert on the Langfuse
        // side-channel call.
        rewardService = new RewardService(episodeRepository, rewardRecordRepository, new RlRewardProperties(), langfuseScoreClient);
        episodeId = UUID.randomUUID();
        episode = new Episode(UUID.randomUUID(), "s1", "t1", Instant.now(), null, null);
        // lenient: missingEpisodeReturnsNull stubs a different id and never
        // touches these, which strict stubbing would otherwise flag.
        lenient().when(episodeRepository.findById(episodeId)).thenReturn(java.util.Optional.of(episode));
        lenient().when(episodeRepository.save(any(Episode.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private RewardRecord record(RewardSource source, double value, double weight) {
        return new RewardRecord(episodeId, source, BigDecimal.valueOf(value), BigDecimal.valueOf(weight), Instant.now());
    }

    @Test
    void weightedMeanOfNonSafetyComponents() {
        when(rewardRecordRepository.findByEpisodeId(episodeId)).thenReturn(List.of(
            record(RewardSource.HUMAN, 1.0, 1.0),
            record(RewardSource.JUDGE, 0.0, 0.6)
        ));
        // (1.0*1.0 + 0.0*0.6) / (1.0 + 0.6) = 0.625
        Double reward = rewardService.computeReward(episodeId);
        assertThat(reward).isEqualTo(0.625);
        assertThat(episode.getReward()).isEqualByComparingTo("0.6250");
    }

    @Test
    void safetyViolationVetoesReward() {
        when(rewardRecordRepository.findByEpisodeId(episodeId)).thenReturn(List.of(
            record(RewardSource.HUMAN, 1.0, 1.0),
            record(RewardSource.SAFETY, 1.0, 1.0)
        ));
        // Blended 1.0, but a SAFETY violation caps it at the veto cap (0.1).
        Double reward = rewardService.computeReward(episodeId);
        assertThat(reward).isEqualTo(0.1);
    }

    @Test
    void blendedRewardIsClippedToUnitInterval() {
        when(rewardRecordRepository.findByEpisodeId(episodeId)).thenReturn(List.of(
            record(RewardSource.TASK_SUCCESS, 2.0, 1.0)
        ));
        // Out-of-range component value is clipped to clipMax (1.0).
        Double reward = rewardService.computeReward(episodeId);
        assertThat(reward).isEqualTo(1.0);
    }

    @Test
    void noComponentsYieldsZero() {
        when(rewardRecordRepository.findByEpisodeId(episodeId)).thenReturn(List.of());
        assertThat(rewardService.computeReward(episodeId)).isEqualTo(0.0);
    }

    @Test
    void missingEpisodeReturnsNull() {
        UUID unknown = UUID.randomUUID();
        when(episodeRepository.findById(unknown)).thenReturn(java.util.Optional.empty());
        assertThat(rewardService.computeReward(unknown)).isNull();
    }
}
