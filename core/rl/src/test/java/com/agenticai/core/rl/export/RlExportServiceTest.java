package com.agenticai.core.rl.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.domain.FeedbackSignal;
import com.agenticai.core.rl.domain.TrajectoryStep;
import com.agenticai.core.rl.domain.TurnFeedback;
import com.agenticai.core.rl.repo.EpisodeRepository;
import com.agenticai.core.rl.repo.TrajectoryStepRepository;
import com.agenticai.core.rl.repo.TurnFeedbackRepository;
import com.agenticai.core.rl.web.dto.RlPreferenceExport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the feedback → (DPO pair | KTO sample) mapping: EDIT yields a paired chosen/rejected
 * example, thumbs/ratings/resolution yield unpaired binary KTO samples, a neutral rating trains
 * nothing, and rows with no matching episode or answer are dropped rather than guessed at.
 */
@ExtendWith(MockitoExtension.class)
class RlExportServiceTest {

    @Mock private TurnFeedbackRepository feedbackRepository;
    @Mock private EpisodeRepository episodeRepository;
    @Mock private TrajectoryStepRepository stepRepository;

    private RlExportService service;
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RlExportService(feedbackRepository, episodeRepository, stepRepository);
    }

    private TurnFeedback fb(String turnRef, FeedbackSignal signal, Short rating, String edited) {
        return new TurnFeedback(
                agentId, "s-" + turnRef, turnRef, signal, rating, null, edited, "u", Instant.now());
    }

    /** Stubs the episode + its (prompt, answer) steps for a given feedback turnRef. */
    private void stubEpisode(String turnRef, String prompt, String answer) {
        Episode e = new Episode(agentId, "s-" + turnRef, turnRef, Instant.now(), null, null);
        e.setSubject("u"); // the subject the feedback rows below are created by
        lenient()
                .when(
                        episodeRepository
                                .findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                                        eq(agentId), eq("s-" + turnRef), eq(turnRef)))
                .thenReturn(Optional.of(e));
        List<TrajectoryStep> steps = new java.util.ArrayList<>();
        steps.add(
                new TrajectoryStep(
                        e.getId(), 0, "user", "message", prompt, null, null, null, Instant.now()));
        if (answer != null) {
            steps.add(
                    new TrajectoryStep(
                            e.getId(),
                            1,
                            "assistant",
                            "answer",
                            answer,
                            null,
                            null,
                            null,
                            Instant.now()));
        }
        lenient()
                .when(stepRepository.findByEpisodeIdOrderByStepIndexAsc(e.getId()))
                .thenReturn(steps);
    }

    @Test
    void editBecomesADpoPairChosenOverOriginal() {
        when(feedbackRepository.findByAgentIdOrderByCreatedAtDesc(agentId))
                .thenReturn(List.of(fb("t1", FeedbackSignal.EDIT, null, "the better answer")));
        stubEpisode("t1", "what is 2+2?", "the original answer");

        RlPreferenceExport out = service.export(agentId, 500);

        assertThat(out.dpo()).hasSize(1);
        assertThat(out.dpo().get(0).prompt()).isEqualTo("what is 2+2?");
        assertThat(out.dpo().get(0).chosen()).isEqualTo("the better answer");
        assertThat(out.dpo().get(0).rejected()).isEqualTo("the original answer");
        assertThat(out.kto()).isEmpty();
    }

    @Test
    void thumbsAndRatingsBecomeBinaryKtoSamples() {
        when(feedbackRepository.findByAgentIdOrderByCreatedAtDesc(agentId))
                .thenReturn(
                        List.of(
                                fb("t1", FeedbackSignal.THUMBS_UP, null, null),
                                fb("t2", FeedbackSignal.THUMBS_DOWN, null, null),
                                fb("t3", FeedbackSignal.RATING, (short) 5, null),
                                fb("t4", FeedbackSignal.RATING, (short) 1, null),
                                fb("t5", FeedbackSignal.RATING, (short) 3, null)));
        stubEpisode("t1", "p1", "a1");
        stubEpisode("t2", "p2", "a2");
        stubEpisode("t3", "p3", "a3");
        stubEpisode("t4", "p4", "a4");
        stubEpisode("t5", "p5", "a5");

        RlPreferenceExport out = service.export(agentId, 500);

        // 4 KTO samples (the neutral 3-star trains nothing); no DPO pairs.
        assertThat(out.dpo()).isEmpty();
        assertThat(out.kto()).hasSize(4);
        assertThat(out.kto())
                .filteredOn(RlPreferenceExport.KtoSample::desirable)
                .hasSize(2); // THUMBS_UP + 5-star
        assertThat(out.kto()).filteredOn(k -> !k.desirable()).hasSize(2); // THUMBS_DOWN + 1-star
    }

    @Test
    void feedbackWithNoMatchingEpisodeIsSkipped() {
        when(feedbackRepository.findByAgentIdOrderByCreatedAtDesc(agentId))
                .thenReturn(List.of(fb("t1", FeedbackSignal.THUMBS_UP, null, null)));
        // no stubEpisode -> repository returns empty Optional (Mockito default)
        when(episodeRepository.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                        any(), any(), any()))
                .thenReturn(Optional.empty());

        RlPreferenceExport out = service.export(agentId, 500);

        assertThat(out.dpo()).isEmpty();
        assertThat(out.kto()).isEmpty();
    }

    @Test
    void ktoNeedsAnAnswer_editStillNeedsOriginalToPair() {
        when(feedbackRepository.findByAgentIdOrderByCreatedAtDesc(agentId))
                .thenReturn(
                        List.of(
                                fb("t1", FeedbackSignal.THUMBS_UP, null, null),
                                fb("t2", FeedbackSignal.EDIT, null, "chosen")));
        stubEpisode("t1", "p1", null); // answer missing -> KTO skipped
        stubEpisode("t2", "p2", null); // no original answer -> DPO pair can't form

        RlPreferenceExport out = service.export(agentId, 500);

        assertThat(out.dpo()).isEmpty();
        assertThat(out.kto()).isEmpty();
    }

    @Test
    void feedbackFromSomeoneOtherThanWhoHadTheTurnIsNotATrainingSample() {
        Episode e = new Episode(agentId, "s-t9", "t9", Instant.now(), null, null);
        e.setSubject("someone-else");
        when(episodeRepository.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                        agentId, "s-t9", "t9"))
                .thenReturn(Optional.of(e));
        when(feedbackRepository.findByAgentIdOrderByCreatedAtDesc(agentId))
                .thenReturn(List.of(fb("t9", FeedbackSignal.EDIT, null, "planted")));

        RlPreferenceExport export = service.export(agentId, 100);

        assertThat(export.dpo()).isEmpty();
        assertThat(export.kto()).isEmpty();
    }
}
