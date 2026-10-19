package com.agenticai.core.rl.trajectory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.domain.EpisodePolicyArm;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.repo.EpisodePolicyArmRepository;
import com.agenticai.core.rl.repo.EpisodeRepository;
import com.agenticai.core.rl.repo.TrajectoryStepRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link TrajectoryService#beginEpisode}'s multi-arm wiring (migration
 * V201__rl_episode_policy_arm.sql, 2026-08-15) - a turn can now select up to one arm per {@link
 * PolicyDimension}, not just {@code MODEL_ROUTE}. Pins two things: {@link Episode#getPolicyArmId()}
 * stays populated from ONLY the MODEL_ROUTE entry (backward compatibility with existing readers of
 * that single column), and the FULL selection map is additionally persisted via {@link
 * EpisodePolicyArmRepository}, one row per entry - what {@code FeedbackService#applyHumanReward}
 * later reads to reward every dimension's arm, not just MODEL_ROUTE's.
 */
@ExtendWith(MockitoExtension.class)
class TrajectoryServiceTest {

    @Mock private EpisodeRepository episodeRepository;
    @Mock private TrajectoryStepRepository stepRepository;
    @Mock private EpisodePolicyArmRepository episodePolicyArmRepository;

    private TrajectoryService service() {
        return new TrajectoryService(episodeRepository, stepRepository, episodePolicyArmRepository);
    }

    private static Episode savedEpisode(UUID id) {
        Episode episode =
                new Episode(
                        UUID.randomUUID(),
                        "session-1",
                        "turn-1",
                        java.time.Instant.now(),
                        null,
                        null);
        // Simulate JPA assigning the id on save - Episode has no public setter,
        // so reflection is the only way to pin an id on the mocked-save return
        // without a real persistence context.
        try {
            var field = Episode.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(episode, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return episode;
    }

    @Test
    void multiArmSelectionPersistsPolicyArmIdFromModelRouteOnlyPlusTheFullMapToTheJoinTable() {
        UUID episodeId = UUID.randomUUID();
        UUID modelRouteArm = UUID.randomUUID();
        UUID toolSubsetArm = UUID.randomUUID();
        UUID promptVariantArm = UUID.randomUUID();
        when(episodeRepository.save(any())).thenReturn(savedEpisode(episodeId));

        Map<PolicyDimension, UUID> selected =
                Map.of(
                        PolicyDimension.MODEL_ROUTE, modelRouteArm,
                        PolicyDimension.TOOL_SUBSET, toolSubsetArm,
                        PolicyDimension.PROMPT_VARIANT, promptVariantArm);

        Episode result =
                service().beginEpisode(UUID.randomUUID(), "session-1", "turn-1", null, selected);

        // Episode.policyArmId (the single legacy column) - MODEL_ROUTE only.
        ArgumentCaptor<Episode> episodeCaptor = ArgumentCaptor.forClass(Episode.class);
        verify(episodeRepository).save(episodeCaptor.capture());
        assertThat(episodeCaptor.getValue().getPolicyArmId()).isEqualTo(modelRouteArm);
        assertThat(result.getId()).isEqualTo(episodeId);

        // The full map - one row per dimension, all pointing at this episode.
        ArgumentCaptor<EpisodePolicyArm> rowCaptor =
                ArgumentCaptor.forClass(EpisodePolicyArm.class);
        verify(episodePolicyArmRepository, org.mockito.Mockito.times(3)).save(rowCaptor.capture());
        List<EpisodePolicyArm> rows = rowCaptor.getAllValues();
        assertThat(rows)
                .extracting(EpisodePolicyArm::getDimension)
                .containsExactlyInAnyOrder(
                        PolicyDimension.MODEL_ROUTE,
                        PolicyDimension.TOOL_SUBSET,
                        PolicyDimension.PROMPT_VARIANT);
        assertThat(rows).allMatch(r -> r.getEpisodeId().equals(episodeId));
        assertThat(rows)
                .filteredOn(r -> r.getDimension() == PolicyDimension.TOOL_SUBSET)
                .extracting(EpisodePolicyArm::getPolicyArmId)
                .containsExactly(toolSubsetArm);
    }

    @Test
    void emptySelectionLeavesPolicyArmIdNullAndWritesNoJoinRows() {
        UUID episodeId = UUID.randomUUID();
        when(episodeRepository.save(any())).thenReturn(savedEpisode(episodeId));

        service().beginEpisode(UUID.randomUUID(), "session-1", "turn-1", null, Map.of());

        ArgumentCaptor<Episode> episodeCaptor = ArgumentCaptor.forClass(Episode.class);
        verify(episodeRepository).save(episodeCaptor.capture());
        assertThat(episodeCaptor.getValue().getPolicyArmId()).isNull();
        verify(episodePolicyArmRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void fourArgOverloadStillWorksWithNoArmsInvolved() {
        UUID episodeId = UUID.randomUUID();
        when(episodeRepository.save(any())).thenReturn(savedEpisode(episodeId));

        Episode result = service().beginEpisode(UUID.randomUUID(), "session-1", "turn-1", "{}");

        assertThat(result.getId()).isEqualTo(episodeId);
        verify(episodePolicyArmRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void theEpisodeRecordsWhoHadTheTurn() {
        com.agenticai.core.security.TenantContext.set(
                com.agenticai.core.security.TenantContext.of("acme", "alice", java.util.Set.of()));
        try {
            when(episodeRepository.save(any())).thenReturn(savedEpisode(UUID.randomUUID()));

            service().beginEpisode(UUID.randomUUID(), "session-1", "turn-1", null, Map.of());

            ArgumentCaptor<Episode> captor = ArgumentCaptor.forClass(Episode.class);
            verify(episodeRepository).save(captor.capture());
            assertThat(captor.getValue().getSubject()).isEqualTo("alice");
        } finally {
            com.agenticai.core.security.TenantContext.clear();
        }
    }
}
