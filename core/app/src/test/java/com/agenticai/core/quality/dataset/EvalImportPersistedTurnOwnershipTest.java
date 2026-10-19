package com.agenticai.core.quality.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.domain.TrajectoryStep;
import com.agenticai.core.rl.repo.EpisodeRepository;
import com.agenticai.core.rl.repo.TrajectoryStepRepository;
import com.agenticai.core.security.TenantContext;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

/**
 * Importing a chat turn into an eval dataset copies the server's recorded copy of the turn only
 * when the caller had that turn: an agent editor must not be able to pull another user's
 * conversation into a dataset by naming its session and turn. Lives in core/app because the lookup
 * reaches core/rl's repositories reflectively and only this module has both on the classpath.
 */
class EvalImportPersistedTurnOwnershipTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();

    private EvalDatasetCaseRepository caseRepository;
    private EvalDatasetService service;
    private Episode episode;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        EvalDatasetRepository datasetRepository = mock(EvalDatasetRepository.class);
        caseRepository = mock(EvalDatasetCaseRepository.class);
        when(caseRepository.save(any(EvalDatasetCase.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasetRepository.findById(DATASET_ID))
                .thenReturn(
                        Optional.of(new EvalDataset(AGENT_ID, "Set", null, 0.7, false, "alice")));

        episode = new Episode(AGENT_ID, "s1", "t1", Instant.now(), null, null);
        UUID episodeId = UUID.randomUUID();
        Field id = Episode.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(episode, episodeId);
        EpisodeRepository episodes = mock(EpisodeRepository.class);
        when(episodes.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                        AGENT_ID, "s1", "t1"))
                .thenReturn(Optional.of(episode));
        TrajectoryStepRepository steps = mock(TrajectoryStepRepository.class);
        when(steps.findByEpisodeIdOrderByStepIndexAsc(episodeId))
                .thenReturn(
                        List.of(
                                step(episodeId, 0, "user", "the real question"),
                                step(episodeId, 1, "assistant", "the real answer")));
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(EpisodeRepository.class)).thenReturn(episodes);
        when(context.getBean(TrajectoryStepRepository.class)).thenReturn(steps);

        ObjectProvider<LlmCallLogService> calls = mock(ObjectProvider.class);
        service =
                new EvalDatasetService(
                        datasetRepository,
                        caseRepository,
                        mock(EvalRunRepository.class),
                        calls,
                        context,
                        "http://localhost:3002",
                        "",
                        "",
                        "");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static TrajectoryStep step(UUID episodeId, int index, String role, String content) {
        return new TrajectoryStep(
                episodeId, index, role, "message", content, null, null, null, Instant.now());
    }

    private EvalDatasetCase importAs(String subject) {
        TenantContext.set(TenantContext.of("acme", subject, Set.of()));
        return service.importTurnCase(
                AGENT_ID, DATASET_ID, "s1", "t1", "body question", "body answer", null, List.of());
    }

    @Test
    void theCallersOwnTurnUsesTheRecordedCopy() {
        episode.setSubject("alice");
        assertThat(importAs("alice").getUserMessage()).isEqualTo("the real question");
    }

    @Test
    void someoneElsesTurnFallsBackToTheRequestBody() {
        episode.setSubject("bob");
        assertThat(importAs("alice").getUserMessage()).isEqualTo("body question");
    }

    @Test
    void anUnattributedTurnFallsBackToTheRequestBody() {
        assertThat(importAs("alice").getUserMessage()).isEqualTo("body question");
    }
}
