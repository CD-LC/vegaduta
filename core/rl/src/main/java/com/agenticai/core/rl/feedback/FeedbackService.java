package com.agenticai.core.rl.feedback;

import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.domain.EpisodePolicyArm;
import com.agenticai.core.rl.domain.FeedbackSignal;
import com.agenticai.core.rl.domain.RewardSource;
import com.agenticai.core.rl.domain.TurnFeedback;
import com.agenticai.core.rl.policy.ContextualBanditService;
import com.agenticai.core.rl.repo.EpisodePolicyArmRepository;
import com.agenticai.core.rl.repo.EpisodeRepository;
import com.agenticai.core.rl.repo.TurnFeedbackRepository;
import com.agenticai.core.rl.reward.RewardService;
import com.agenticai.core.security.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a human {@link TurnFeedback} signal and, when it can be matched to a recorded {@link
 * Episode} (same agent + session + turn ref), converts it into a HUMAN-source reward record,
 * recomputes that episode's composite reward, and folds the result back into EVERY bandit arm that
 * contributed to that episode (see {@link EpisodePolicyArm}'s javadoc - a turn can select up to one
 * arm per {@link com.agenticai.core.rl.domain.PolicyDimension}, not just {@code MODEL_ROUTE}).
 *
 * <p>This is the human end of the learning loop: a thumbs/rating/edit becomes training signal for
 * whichever arm(s) produced the turn.
 */
@Service
public class FeedbackService {

    private final TurnFeedbackRepository feedbackRepository;
    private final EpisodeRepository episodeRepository;
    private final EpisodePolicyArmRepository episodePolicyArmRepository;
    private final RewardService rewardService;
    private final ContextualBanditService banditService;
    private ApplicationEventPublisher events;

    private RlFeedbackProperties feedbackProperties = new RlFeedbackProperties();

    /** {@code rl.feedback.*}: the reward each signal maps to. Without it the built-in mapping applies. */
    @Autowired(required = false)
    public void setFeedbackProperties(RlFeedbackProperties feedbackProperties) {
        this.feedbackProperties = feedbackProperties == null ? new RlFeedbackProperties() : feedbackProperties;
    }

    public FeedbackService(
            TurnFeedbackRepository feedbackRepository,
            EpisodeRepository episodeRepository,
            EpisodePolicyArmRepository episodePolicyArmRepository,
            RewardService rewardService,
            ContextualBanditService banditService) {
        this.feedbackRepository = feedbackRepository;
        this.episodeRepository = episodeRepository;
        this.episodePolicyArmRepository = episodePolicyArmRepository;
        this.rewardService = rewardService;
        this.banditService = banditService;
    }

    /** Optional: lets other modules learn from a stored comment (see {@link TurnFeedbackRecordedEvent}). */
    @Autowired(required = false)
    void setEvents(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Transactional
    public TurnFeedback record(
            UUID agentId,
            String sessionId,
            String turnRef,
            FeedbackSignal signal,
            Short rating,
            String comment,
            String editedAnswer) {
        String createdBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        // A recorded turn belongs to whoever had it: rating someone else's turn would skew the
        // bandit and, through an EDIT, plant a "chosen" answer in the preference export.
        Optional<Episode> episode =
                episodeRepository.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                        agentId, sessionId, turnRef);
        if (episode.isPresent()
                && episode.get().getSubject() != null
                && !episode.get().getSubject().equals(createdBy)) {
            throw new TurnNotOwnedException();
        }
        TurnFeedback feedback =
                feedbackRepository.save(
                        new TurnFeedback(
                                agentId,
                                sessionId,
                                turnRef,
                                signal,
                                rating,
                                comment,
                                editedAnswer,
                                createdBy,
                                Instant.now()));

        // Convert to a HUMAN reward on the matching episode when one exists. An episode with no
        // recorded subject (before V474) cannot be attributed, so it takes no reward.
        episode.filter(e -> e.getSubject() != null)
                .ifPresent(e -> applyHumanReward(e, signal, rating));

        if (events != null && createdBy != null && comment != null && !comment.isBlank()) {
            try {
                events.publishEvent(
                        new TurnFeedbackRecordedEvent(this, createdBy, agentId, sessionId, comment));
            } catch (RuntimeException ignored) {
                // A listener must never undo a stored rating.
            }
        }
        return feedback;
    }

    /**
     * The turn exists but someone else had it. The controller answers 404, the same as a turn that
     * does not exist, so turn references cannot be probed.
     */
    public static class TurnNotOwnedException extends RuntimeException {
        public TurnNotOwnedException() {
            super("No such turn");
        }
    }

    /**
     * Approval-gated workflow reward loop: a human's approve/reject decision on a workflow Approval
     * node is itself training signal for whatever agent turn led up to that gate, folded in through
     * the exact same HUMAN-reward pipeline {@link #record} already established for chat-UI
     * thumbs/rating feedback (addRewardRecord -&gt; computeReward -&gt; bandit update) - this does
     * not invent a second reward mechanism.
     *
     * <p>The one real difference from {@link #record}: an Approval node's approval-request payload
     * only carries the workflow run id (used as every Agent-node turn's session id inside that
     * run), never a specific agentId/turnRef the way a chat-UI thumbs click does, so this matches
     * by {@code sessionId} alone via {@link
     * com.agenticai.core.rl.repo.EpisodeRepository#findFirstBySessionIdOrderByStartedAtDesc} and
     * rewards the LAST agent turn recorded in that run - the one whose output the human was
     * actually deciding on. No {@link TurnFeedback} row is written (there is no
     * thumbs/rating/comment to store, just a binary decision), so this intentionally bypasses
     * {@link #record} and calls {@link #applyHumanReward} directly.
     *
     * <p>A no-op, not an error, when no episode exists for that session - e.g. the workflow's
     * Approval node had no upstream Agent node in this run, or the run predates this wiring.
     * Callers should treat this as best-effort exactly like every other RL call site in this
     * codebase (see {@code AgentChatService#beginRlEpisode}'s identical posture).
     */
    @Transactional
    public void recordApprovalDecision(String sessionId, boolean approved) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        episodeRepository
                .findFirstBySessionIdOrderByStartedAtDesc(sessionId)
                .ifPresent(
                        episode ->
                                applyHumanReward(
                                        episode,
                                        approved
                                                ? FeedbackSignal.THUMBS_UP
                                                : FeedbackSignal.THUMBS_DOWN,
                                        null));
    }

    private void applyHumanReward(Episode episode, FeedbackSignal signal, Short rating) {
        double value = signalToReward(signal, rating);
        rewardService.addRewardRecord(
                episode.getId(), RewardSource.HUMAN, BigDecimal.valueOf(value), null);
        Double reward = rewardService.computeReward(episode.getId());
        if (reward == null) {
            return;
        }
        // Same observed reward credited to every arm that contributed to this
        // turn's decision - one outcome, potentially several concurrent
        // dimension selections (MODEL_ROUTE, REASONING_DEPTH, GEN_PARAMS,
        // TOOL_SUBSET, PROMPT_VARIANT). Standard joint credit assignment for a
        // single trial's multiple bandit picks, not a bug.
        for (EpisodePolicyArm selection :
                episodePolicyArmRepository.findByEpisodeId(episode.getId())) {
            banditService.update(selection.getPolicyArmId(), reward);
        }
    }

    /**
     * Maps a human signal onto a [0,1] reward target for the arm that produced the turn:
     *
     * <ul>
     *   <li>THUMBS_UP -&gt; 1.0, THUMBS_DOWN -&gt; 0.0 (rl.feedback.thumbs-up-reward / thumbs-down-reward).
     *   <li>RESOLVED -&gt; 1.0, UNRESOLVED -&gt; 0.0 (did the turn actually resolve the user's
     *       need).
     *   <li>RATING -&gt; (rating-1)/4 so 1 star -&gt; 0.0 and 5 stars -&gt; 1.0.
     *   <li>EDIT -&gt; 0.0 (rl.feedback.edit-reward): the arm's own output needed human correction, so it is trained AWAY
     *       from; the corrected text becomes the "chosen" side of a DPO preference pair on export
     *       instead.
     * </ul>
     */
    private double signalToReward(FeedbackSignal signal, Short rating) {
        return switch (signal) {
            case THUMBS_UP -> feedbackProperties.getThumbsUpReward();
            case RESOLVED -> feedbackProperties.getResolvedReward();
            case THUMBS_DOWN -> feedbackProperties.getThumbsDownReward();
            case UNRESOLVED -> feedbackProperties.getUnresolvedReward();
            case EDIT -> feedbackProperties.getEditReward();
            case RATING -> {
                int r = rating == null ? feedbackProperties.getDefaultRating() : Math.max(1, Math.min(5, rating));
                yield (r - 1) / 4.0;
            }
        };
    }
}
