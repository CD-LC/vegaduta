package com.agenticai.core.rl.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.domain.FeedbackSignal;
import com.agenticai.core.rl.domain.RewardSource;
import com.agenticai.core.rl.domain.TrajectoryStep;
import com.agenticai.core.rl.domain.TurnFeedback;
import com.agenticai.core.rl.export.RlExportService;
import com.agenticai.core.rl.policy.ContextualBanditService;
import com.agenticai.core.rl.repo.EpisodePolicyArmRepository;
import com.agenticai.core.rl.repo.EpisodeRepository;
import com.agenticai.core.rl.repo.TrajectoryStepRepository;
import com.agenticai.core.rl.repo.TurnFeedbackRepository;
import com.agenticai.core.rl.reward.RewardService;
import com.agenticai.core.rl.web.dto.RlPreferenceExport;
import com.agenticai.core.security.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@code rl.feedback.*}: what a signal is worth as a reward and where the export draws desirable / undesirable. */
class RlFeedbackPropertiesTest {

    private static final UUID AGENT = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aConfiguredRewardIsWhatTheHumanRecordCarries() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of("tenant-admin")));
        EpisodeRepository episodes = mock(EpisodeRepository.class);
        RewardService rewards = mock(RewardService.class);
        Episode episode = new Episode(AGENT, "s", "t", Instant.now(), null, null);
        episode.setSubject("user-1");
        when(episodes.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(AGENT, "s", "t"))
            .thenReturn(Optional.of(episode));
        when(rewards.computeReward(any())).thenReturn(null);
        FeedbackService service = new FeedbackService(
            mock(TurnFeedbackRepository.class), episodes, mock(EpisodePolicyArmRepository.class), rewards,
            mock(ContextualBanditService.class));
        RlFeedbackProperties properties = new RlFeedbackProperties();
        properties.setThumbsUpReward(0.8);
        properties.setEditReward(0.2);
        service.setFeedbackProperties(properties);

        service.record(AGENT, "s", "t", FeedbackSignal.THUMBS_UP, null, null, null);
        service.record(AGENT, "s", "t", FeedbackSignal.EDIT, null, null, "better");
        service.record(AGENT, "s", "t", FeedbackSignal.RATING, null, null, null);

        verify(rewards).addRewardRecord(any(), eq(RewardSource.HUMAN), eq(BigDecimal.valueOf(0.8)), eq(null));
        verify(rewards).addRewardRecord(any(), eq(RewardSource.HUMAN), eq(BigDecimal.valueOf(0.2)), eq(null));
        // a rating without stars is the default 3: (3-1)/4
        verify(rewards).addRewardRecord(any(), eq(RewardSource.HUMAN), eq(BigDecimal.valueOf(0.5)), eq(null));
    }

    @Test
    void theExportThresholdsAreConfigurable() {
        TurnFeedbackRepository feedback = mock(TurnFeedbackRepository.class);
        EpisodeRepository episodes = mock(EpisodeRepository.class);
        TrajectoryStepRepository steps = mock(TrajectoryStepRepository.class);
        Episode episode = new Episode(AGENT, "s-t", "t", Instant.now(), null, null);
        episode.setSubject("u");
        when(episodes.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(any(), any(), any()))
            .thenReturn(Optional.of(episode));
        when(steps.findByEpisodeIdOrderByStepIndexAsc(any())).thenReturn(List.of(
            new TrajectoryStep(episode.getId(), 0, "user", "message", "p", null, null, null, Instant.now()),
            new TrajectoryStep(episode.getId(), 1, "assistant", "answer", "a", null, null, null, Instant.now())));
        when(feedback.findByAgentIdOrderByCreatedAtDesc(AGENT)).thenReturn(List.of(
            new TurnFeedback(AGENT, "s-t", "t", FeedbackSignal.RATING, (short) 3, null, null, "u", Instant.now())));
        RlExportService export = new RlExportService(feedback, episodes, steps);

        assertThat(export.export(AGENT, 10).kto()).isEmpty(); // 3 stars: neutral by default

        RlFeedbackProperties properties = new RlFeedbackProperties();
        properties.setExportDesirableMinRating(3);
        export.setFeedbackProperties(properties);
        RlPreferenceExport out = export.export(AGENT, 10);
        assertThat(out.kto()).hasSize(1);
        assertThat(out.kto().get(0).desirable()).isTrue();
    }
}
