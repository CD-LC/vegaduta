package com.agenticai.core.rl.trajectory;

import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.domain.EpisodePolicyArm;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.domain.TrajectoryStep;
import com.agenticai.core.rl.repo.EpisodePolicyArmRepository;
import com.agenticai.core.rl.repo.EpisodeRepository;
import com.agenticai.core.rl.repo.TrajectoryStepRepository;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records RL episodes and their ordered trajectory steps. The explicit begin/step/end API is what
 * the orchestrator drives through {@code RlTurnRecorder}; {@link #recordLlmStep} is the convenience
 * the {@code RlTrajectoryAdvisor} uses to attach one assistant step per real LLM call without the
 * caller having to thread an episode id through the advisor chain.
 */
@Service
public class TrajectoryService {

    private final EpisodeRepository episodeRepository;
    private final TrajectoryStepRepository stepRepository;
    private final EpisodePolicyArmRepository episodePolicyArmRepository;

    public TrajectoryService(
            EpisodeRepository episodeRepository,
            TrajectoryStepRepository stepRepository,
            EpisodePolicyArmRepository episodePolicyArmRepository) {
        this.episodeRepository = episodeRepository;
        this.stepRepository = stepRepository;
        this.episodePolicyArmRepository = episodePolicyArmRepository;
    }

    @Transactional
    public Episode beginEpisode(
            UUID agentId, String sessionId, String turnRef, String contextJson) {
        return beginEpisode(agentId, sessionId, turnRef, contextJson, Map.of());
    }

    /**
     * @param selectedArms every dimension's selected arm for this turn (may be empty - no policy
     *     active for any dimension). {@link Episode#getPolicyArmId()} is still set, from the {@link
     *     PolicyDimension#MODEL_ROUTE} entry specifically if present, for backward compatibility
     *     with existing readers of that single column; the FULL map is additionally persisted to
     *     {@code rl_episode_policy_arm} (one row per entry) so the later human-reward path can fold
     *     the turn's one observed reward back into every arm that contributed to it, not just
     *     MODEL_ROUTE's.
     */
    @Transactional
    public Episode beginEpisode(
            UUID agentId,
            String sessionId,
            String turnRef,
            String contextJson,
            Map<PolicyDimension, UUID> selectedArms) {
        UUID modelRouteArmId = selectedArms.get(PolicyDimension.MODEL_ROUTE);
        Episode unsaved =
                new Episode(
                        agentId, sessionId, turnRef, Instant.now(), contextJson, modelRouteArmId);
        // Who had the turn (runs on the request thread, before the model call), so only they
        // can rate it - see FeedbackService#record.
        unsaved.setSubject(TenantContext.current().map(TenantContext::getSubject).orElse(null));
        Episode episode = episodeRepository.save(unsaved);
        selectedArms.forEach(
                (dimension, armId) ->
                        episodePolicyArmRepository.save(
                                new EpisodePolicyArm(episode.getId(), armId, dimension)));
        return episode;
    }

    /**
     * Appends a step to an episode, auto-assigning the next step index from the count of steps
     * already recorded for it.
     */
    @Transactional
    public TrajectoryStep recordStep(
            UUID episodeId,
            String role,
            String actionType,
            String content,
            String toolName,
            Long tokens,
            Long latencyMs) {
        int stepIndex = (int) stepRepository.countByEpisodeId(episodeId);
        return stepRepository.save(
                new TrajectoryStep(
                        episodeId,
                        stepIndex,
                        role,
                        actionType,
                        content,
                        toolName,
                        tokens,
                        latencyMs,
                        Instant.now()));
    }

    @Transactional
    public Episode endEpisode(UUID episodeId, boolean terminal) {
        Episode episode = episodeRepository.findById(episodeId).orElse(null);
        if (episode == null) {
            return null;
        }
        episode.setTerminal(terminal);
        episode.setEndedAt(Instant.now());
        return episodeRepository.save(episode);
    }

    /**
     * Records one assistant LLM-call step for the current live turn identified only by (agentId,
     * sessionId) - used by {@code RlTrajectoryAdvisor}, which has no episode id in the advisor
     * context. Attaches to the latest still- open episode for that (agent, session); if none
     * exists, opens an implicit one so the step is never lost.
     */
    @Transactional
    public TrajectoryStep recordLlmStep(
            UUID agentId, String sessionId, String content, Long tokens, Long latencyMs) {
        Episode episode =
                episodeRepository
                        .findFirstByAgentIdAndSessionIdAndTerminalFalseOrderByStartedAtDesc(
                                agentId, sessionId)
                        .orElseGet(() -> beginEpisode(agentId, sessionId, sessionId, null));
        return recordStep(
                episode.getId(), "assistant", "llm_call", content, null, tokens, latencyMs);
    }

    @Transactional(readOnly = true)
    public List<TrajectoryStep> stepsFor(UUID episodeId) {
        return stepRepository.findByEpisodeIdOrderByStepIndexAsc(episodeId);
    }
}
