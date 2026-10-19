package com.agenticai.core.rl.feedback;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.domain.EpisodePolicyArm;
import com.agenticai.core.rl.domain.FeedbackSignal;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.domain.RewardSource;
import com.agenticai.core.rl.policy.ContextualBanditService;
import com.agenticai.core.rl.repo.EpisodePolicyArmRepository;
import com.agenticai.core.rl.repo.EpisodeRepository;
import com.agenticai.core.rl.repo.TurnFeedbackRepository;
import com.agenticai.core.rl.reward.RewardService;
import com.agenticai.core.security.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link FeedbackService#applyHumanReward}'s multi-arm loop (2026-08-15 wiring) - a human
 * thumbs/rating/edit must fold the turn's ONE observed reward into EVERY arm recorded against the
 * episode via {@link EpisodePolicyArmRepository}, not just whichever arm used to occupy {@link
 * Episode#getPolicyArmId()}. See {@code TrajectoryServiceTest} for the write side this reads back.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    @Mock private TurnFeedbackRepository feedbackRepository;
    @Mock private EpisodeRepository episodeRepository;
    @Mock private EpisodePolicyArmRepository episodePolicyArmRepository;
    @Mock private RewardService rewardService;
    @Mock private ContextualBanditService banditService;

    private FeedbackService service() {
        return new FeedbackService(
                feedbackRepository,
                episodeRepository,
                episodePolicyArmRepository,
                rewardService,
                banditService);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private static Episode episodeWithId(UUID id) {
        Episode episode = new Episode(AGENT_ID, "session-1", "turn-1", Instant.now(), null, null);
        try {
            var field = Episode.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(episode, id);
            // Had by the subject every test below logs in as.
            episode.setSubject("user-1");
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return episode;
    }

    @Test
    void thumbsUpRewardsEveryArmRecordedAgainstTheEpisodeNotJustOne() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of("tenant-admin")));
        UUID episodeId = UUID.randomUUID();
        Episode episode = episodeWithId(episodeId);
        UUID modelRouteArm = UUID.randomUUID();
        UUID reasoningDepthArm = UUID.randomUUID();
        UUID genParamsArm = UUID.randomUUID();

        when(episodeRepository.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                        AGENT_ID, "session-1", "turn-1"))
                .thenReturn(Optional.of(episode));
        when(rewardService.computeReward(episodeId)).thenReturn(0.8);
        when(episodePolicyArmRepository.findByEpisodeId(episodeId))
                .thenReturn(
                        List.of(
                                new EpisodePolicyArm(
                                        episodeId, modelRouteArm, PolicyDimension.MODEL_ROUTE),
                                new EpisodePolicyArm(
                                        episodeId,
                                        reasoningDepthArm,
                                        PolicyDimension.REASONING_DEPTH),
                                new EpisodePolicyArm(
                                        episodeId, genParamsArm, PolicyDimension.GEN_PARAMS)));

        service()
                .record(
                        AGENT_ID,
                        "session-1",
                        "turn-1",
                        FeedbackSignal.THUMBS_UP,
                        null,
                        null,
                        null);

        verify(rewardService)
                .addRewardRecord(
                        eq(episodeId),
                        eq(RewardSource.HUMAN),
                        eq(BigDecimal.valueOf(1.0)),
                        eq(null));
        verify(banditService).update(modelRouteArm, 0.8);
        verify(banditService).update(reasoningDepthArm, 0.8);
        verify(banditService).update(genParamsArm, 0.8);
        verify(banditService, times(3)).update(any(), eq(0.8));
    }

    @Test
    void thumbsDownWithASingleRecordedArmStillWorksLikeBefore() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of("tenant-admin")));
        UUID episodeId = UUID.randomUUID();
        Episode episode = episodeWithId(episodeId);
        UUID onlyArm = UUID.randomUUID();

        when(episodeRepository.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                        AGENT_ID, "session-1", "turn-1"))
                .thenReturn(Optional.of(episode));
        when(rewardService.computeReward(episodeId)).thenReturn(0.0);
        when(episodePolicyArmRepository.findByEpisodeId(episodeId))
                .thenReturn(
                        List.of(
                                new EpisodePolicyArm(
                                        episodeId, onlyArm, PolicyDimension.MODEL_ROUTE)));

        service()
                .record(
                        AGENT_ID,
                        "session-1",
                        "turn-1",
                        FeedbackSignal.THUMBS_DOWN,
                        null,
                        null,
                        null);

        verify(banditService).update(onlyArm, 0.0);
        verify(banditService, times(1)).update(any(), any(Double.class));
    }

    @Test
    void noRecordedArmsMeansNoBanditUpdateButRewardIsStillPersisted() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of("tenant-admin")));
        UUID episodeId = UUID.randomUUID();
        Episode episode = episodeWithId(episodeId);

        when(episodeRepository.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                        AGENT_ID, "session-1", "turn-1"))
                .thenReturn(Optional.of(episode));
        when(rewardService.computeReward(episodeId)).thenReturn(0.5);
        when(episodePolicyArmRepository.findByEpisodeId(episodeId)).thenReturn(List.of());

        service()
                .record(AGENT_ID, "session-1", "turn-1", FeedbackSignal.RESOLVED, null, null, null);

        verify(rewardService)
                .addRewardRecord(
                        eq(episodeId), eq(RewardSource.HUMAN), any(BigDecimal.class), eq(null));
        verify(banditService, never()).update(any(), any(Double.class));
    }

    @Test
    void nullComputedRewardShortCircuitsBeforeEverLookingUpArms() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of("tenant-admin")));
        UUID episodeId = UUID.randomUUID();
        Episode episode = episodeWithId(episodeId);

        when(episodeRepository.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                        AGENT_ID, "session-1", "turn-1"))
                .thenReturn(Optional.of(episode));
        when(rewardService.computeReward(episodeId)).thenReturn(null);

        service()
                .record(
                        AGENT_ID,
                        "session-1",
                        "turn-1",
                        FeedbackSignal.UNRESOLVED,
                        null,
                        null,
                        null);

        verify(episodePolicyArmRepository, never()).findByEpisodeId(any());
        verify(banditService, never()).update(any(), any(Double.class));
    }

    @Test
    void noMatchingEpisodeIsANoOpForRewardButFeedbackIsStillSaved() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of("tenant-admin")));
        when(episodeRepository.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                        AGENT_ID, "session-1", "turn-1"))
                .thenReturn(Optional.empty());

        service()
                .record(
                        AGENT_ID,
                        "session-1",
                        "turn-1",
                        FeedbackSignal.THUMBS_UP,
                        null,
                        null,
                        null);

        verify(rewardService, never()).addRewardRecord(any(), any(), any(), any());
        verify(banditService, never()).update(any(), any(Double.class));
    }

    @Test
    void approvalDecisionRewardsEveryArmOnTheMatchedEpisodeToo() {
        // recordApprovalDecision bypasses #record but shares the same
        // applyHumanReward path - the multi-arm loop must apply here too.
        String sessionId = "workflow-run-42";
        UUID episodeId = UUID.randomUUID();
        Episode episode = episodeWithId(episodeId);
        UUID armA = UUID.randomUUID();
        UUID armB = UUID.randomUUID();

        when(episodeRepository.findFirstBySessionIdOrderByStartedAtDesc(sessionId))
                .thenReturn(Optional.of(episode));
        when(rewardService.computeReward(episodeId)).thenReturn(1.0);
        when(episodePolicyArmRepository.findByEpisodeId(episodeId))
                .thenReturn(
                        List.of(
                                new EpisodePolicyArm(episodeId, armA, PolicyDimension.TOOL_SUBSET),
                                new EpisodePolicyArm(
                                        episodeId, armB, PolicyDimension.PROMPT_VARIANT)));

        service().recordApprovalDecision(sessionId, true);

        verify(banditService).update(armA, 1.0);
        verify(banditService).update(armB, 1.0);
    }

    @Test
    void someoneElsesTurnCannotBeRatedAndNothingIsStored() {
        TenantContext.set(TenantContext.of("acme", "user-2", Set.of()));
        Episode episode = episodeWithId(UUID.randomUUID());
        when(episodeRepository.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                        AGENT_ID, "session-1", "turn-1"))
                .thenReturn(Optional.of(episode));

        assertThrows(
                FeedbackService.TurnNotOwnedException.class,
                () ->
                        service()
                                .record(
                                        AGENT_ID,
                                        "session-1",
                                        "turn-1",
                                        FeedbackSignal.EDIT,
                                        null,
                                        null,
                                        "planted answer"));
        verify(feedbackRepository, never()).save(any());
        verify(rewardService, never()).addRewardRecord(any(), any(), any(), any());
    }

    @Test
    void anUnattributedEpisodeStoresTheFeedbackButTakesNoReward() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        Episode episode = episodeWithId(UUID.randomUUID());
        episode.setSubject(null);
        when(episodeRepository.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                        AGENT_ID, "session-1", "turn-1"))
                .thenReturn(Optional.of(episode));

        service()
                .record(
                        AGENT_ID,
                        "session-1",
                        "turn-1",
                        FeedbackSignal.THUMBS_UP,
                        null,
                        null,
                        null);

        verify(feedbackRepository).save(any());
        verify(rewardService, never()).addRewardRecord(any(), any(), any(), any());
    }
}
