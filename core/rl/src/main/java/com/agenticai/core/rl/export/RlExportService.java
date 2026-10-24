package com.agenticai.core.rl.export;

import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.feedback.RlFeedbackProperties;
import com.agenticai.core.rl.domain.FeedbackSignal;
import com.agenticai.core.rl.domain.TrajectoryStep;
import com.agenticai.core.rl.domain.TurnFeedback;
import com.agenticai.core.rl.repo.EpisodeRepository;
import com.agenticai.core.rl.repo.TrajectoryStepRepository;
import com.agenticai.core.rl.repo.TurnFeedbackRepository;
import com.agenticai.core.rl.web.dto.RlPreferenceExport;
import com.agenticai.core.rl.web.dto.RlPreferenceExport.DpoPair;
import com.agenticai.core.rl.web.dto.RlPreferenceExport.KtoSample;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconstructs offline preference-optimization training data ({@link RlPreferenceExport}) from an
 * agent's recorded feedback + episodes.
 *
 * <p>For each {@link TurnFeedback}, finds its episode (agent+session+turnRef), pulls the {@code
 * user} prompt and {@code assistant} answer trajectory steps, and maps the signal onto a DPO pair
 * (EDIT) or a KTO sample (thumbs / rating / resolution). Read-only; a feedback row with no matching
 * episode/prompt is skipped rather than guessed at.
 */
@Service
public class RlExportService {

    private RlFeedbackProperties feedbackProperties = new RlFeedbackProperties();

    private final TurnFeedbackRepository feedbackRepository;
    private final EpisodeRepository episodeRepository;
    private final TrajectoryStepRepository stepRepository;

    public RlExportService(
            TurnFeedbackRepository feedbackRepository,
            EpisodeRepository episodeRepository,
            TrajectoryStepRepository stepRepository) {
        this.feedbackRepository = feedbackRepository;
        this.episodeRepository = episodeRepository;
        this.stepRepository = stepRepository;
    }

    /** {@code rl.feedback.*}: the rating thresholds. Without it the built-in 4 / 2 apply. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFeedbackProperties(RlFeedbackProperties feedbackProperties) {
        this.feedbackProperties = feedbackProperties == null ? new RlFeedbackProperties() : feedbackProperties;
    }

    @Transactional(readOnly = true)
    public RlPreferenceExport export(UUID agentId, int limit) {
        List<DpoPair> dpo = new ArrayList<>();
        List<KtoSample> kto = new ArrayList<>();

        for (TurnFeedback fb : feedbackRepository.findByAgentIdOrderByCreatedAtDesc(agentId)) {
            if (dpo.size() + kto.size() >= limit) {
                break;
            }
            Optional<Episode> episode =
                    episodeRepository.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                            agentId, fb.getSessionId(), fb.getTurnRef());
            // Only feedback from the person who had the turn becomes a training sample; an
            // episode with no recorded subject (before V474) cannot be attributed.
            if (episode.isEmpty()
                    || episode.get().getSubject() == null
                    || !episode.get().getSubject().equals(fb.getCreatedBy())) {
                continue;
            }
            List<TrajectoryStep> steps =
                    stepRepository.findByEpisodeIdOrderByStepIndexAsc(episode.get().getId());
            String prompt = firstContent(steps, "message");
            String answer = firstContent(steps, "answer");
            if (prompt == null) {
                continue;
            }
            appendSample(fb, prompt, answer, dpo, kto);
        }
        return new RlPreferenceExport(agentId, dpo.size(), kto.size(), dpo, kto);
    }

    private void appendSample(
            TurnFeedback fb, String prompt, String answer, List<DpoPair> dpo, List<KtoSample> kto) {
        switch (fb.getSignal()) {
            case EDIT -> {
                // The human's correction is the preferred completion over the
                // agent's original answer - a true DPO pair (needs both).
                if (fb.getEditedAnswer() != null
                        && !fb.getEditedAnswer().isBlank()
                        && answer != null) {
                    dpo.add(new DpoPair(prompt, fb.getEditedAnswer(), answer));
                }
            }
            case THUMBS_UP, RESOLVED -> ktoIfAnswer(kto, prompt, answer, true, fb.getSignal());
            case THUMBS_DOWN, UNRESOLVED -> ktoIfAnswer(kto, prompt, answer, false, fb.getSignal());
            case RATING -> {
                if (fb.getRating() == null) {
                    return;
                }
                int r = fb.getRating();
                if (r >= feedbackProperties.getExportDesirableMinRating()) {
                    ktoIfAnswer(kto, prompt, answer, true, fb.getSignal());
                } else if (r <= feedbackProperties.getExportUndesirableMaxRating()) {
                    ktoIfAnswer(kto, prompt, answer, false, fb.getSignal());
                }
                // A neutral 3-star rating trains nothing.
            }
        }
    }

    private static void ktoIfAnswer(
            List<KtoSample> kto,
            String prompt,
            String answer,
            boolean desirable,
            FeedbackSignal source) {
        if (answer != null && !answer.isBlank()) {
            kto.add(new KtoSample(prompt, answer, desirable, source.name()));
        }
    }

    /** First trajectory step with the given {@code actionType}'s content, or null. */
    private static String firstContent(List<TrajectoryStep> steps, String actionType) {
        for (TrajectoryStep s : steps) {
            if (actionType.equals(s.getActionType()) && s.getContent() != null) {
                return s.getContent();
            }
        }
        return null;
    }
}
