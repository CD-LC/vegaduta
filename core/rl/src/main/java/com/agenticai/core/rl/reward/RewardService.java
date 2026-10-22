package com.agenticai.core.rl.reward;

import com.agenticai.core.chat.langfuse.LangfuseScoreClient;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.domain.RewardRecord;
import com.agenticai.core.rl.domain.RewardSource;
import com.agenticai.core.rl.repo.EpisodeRepository;
import com.agenticai.core.rl.repo.RewardRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes an {@link Episode}'s VERIFIABLE COMPOSITE reward and persists it.
 *
 * <p>The reward is a weighted mean of the non-{@link RewardSource#SAFETY} components (each {@link
 * RewardRecord} carries its own weight), then CLIPPED to the configured [{@code clipMin}, {@code
 * clipMax}] range for the bandit, then subjected to a SAFETY VETO: any SAFETY-source violation
 * (value &gt; 0) caps the final reward at {@code rl.reward.safety-veto-cap} (&le; 0.1 by default).
 * The raw per-source breakdown is stored on the episode as {@code rewardBreakdownJson} so the
 * scalar is always auditable back to its parts - the anti-reward-hacking posture (verifiable
 * composite + clipping + safety veto).
 *
 * <p><b>Langfuse score push (infrastructure audit fix).</b> {@link #computeReward} now also pushes
 * the final clipped reward to Langfuse as a session-scoped NUMERIC score via {@link
 * LangfuseScoreClient} - see that call site's own comment for why session-scoped (an episode's
 * trajectory can span multiple LLM calls/traces) and why a fixed score id is used (idempotent
 * upsert across recomputation). This closes the "core/rl's reward signal never writes back to
 * Langfuse" gap.
 */
@Service
public class RewardService {

    private static final Logger log = LoggerFactory.getLogger(RewardService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final EpisodeRepository episodeRepository;
    private final RewardRecordRepository rewardRecordRepository;
    private final RlRewardProperties properties;
    private final LangfuseScoreClient langfuseScoreClient;

    public RewardService(
            EpisodeRepository episodeRepository,
            RewardRecordRepository rewardRecordRepository,
            RlRewardProperties properties,
            LangfuseScoreClient langfuseScoreClient) {
        this.episodeRepository = episodeRepository;
        this.rewardRecordRepository = rewardRecordRepository;
        this.properties = properties;
        this.langfuseScoreClient = langfuseScoreClient;
    }

    /**
     * Records one reward component on an episode. {@code weight} may be null, in which case the
     * configured default weight for the source is used.
     */
    @Transactional
    public RewardRecord addRewardRecord(
            UUID episodeId, RewardSource source, BigDecimal value, BigDecimal weight) {
        BigDecimal effectiveWeight =
                weight != null ? weight : BigDecimal.valueOf(defaultWeightFor(source));
        return rewardRecordRepository.save(
                new RewardRecord(episodeId, source, value, effectiveWeight, Instant.now()));
    }

    /**
     * Recomputes the composite reward for an episode from its current reward records, writing both
     * the clipped scalar and the raw breakdown JSON onto the episode. Returns the clipped reward in
     * [clipMin, clipMax], or null if the episode does not exist.
     */
    @Transactional
    public Double computeReward(UUID episodeId) {
        Episode episode = episodeRepository.findById(episodeId).orElse(null);
        if (episode == null) {
            return null;
        }
        List<RewardRecord> records = rewardRecordRepository.findByEpisodeId(episodeId);

        double weightedSum = 0.0;
        double weightTotal = 0.0;
        boolean safetyViolation = false;
        double maxSafetyValue = 0.0;
        Map<String, Object> breakdown = new LinkedHashMap<>();
        List<Map<String, Object>> components = new ArrayList<>();

        for (RewardRecord r : records) {
            double value = r.getValue() == null ? 0.0 : r.getValue().doubleValue();
            double weight = r.getWeight() == null ? 0.0 : r.getWeight().doubleValue();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("source", r.getSource().name());
            c.put("value", value);
            c.put("weight", weight);
            components.add(c);

            if (r.getSource() == RewardSource.SAFETY) {
                // A SAFETY record is a violation flag, not a blended component.
                if (value > 0.0) {
                    safetyViolation = true;
                    maxSafetyValue = Math.max(maxSafetyValue, value);
                }
                continue;
            }
            weightedSum += value * weight;
            weightTotal += weight;
        }

        double blended = weightTotal > 0.0 ? weightedSum / weightTotal : 0.0;
        double clipped = clip(blended);

        double finalReward = clipped;
        if (safetyViolation) {
            finalReward = Math.min(finalReward, properties.getSafetyVetoCap());
        }

        breakdown.put("components", components);
        breakdown.put("blended", blended);
        breakdown.put("clipped", clipped);
        breakdown.put("safetyViolation", safetyViolation);
        breakdown.put("maxSafetyValue", maxSafetyValue);
        breakdown.put("safetyVetoCap", properties.getSafetyVetoCap());
        breakdown.put("finalReward", finalReward);

        episode.setReward(BigDecimal.valueOf(finalReward).setScale(4, RoundingMode.HALF_UP));
        episode.setRewardBreakdownJson(toJson(breakdown));
        episodeRepository.save(episode);

        // Infrastructure audit fix - core/rl's composite reward never wrote
        // back to Langfuse. Pushed as a session-scoped NUMERIC score (not
        // trace-scoped): one episode's trajectory steps can span several
        // real LLM calls/traces (see TrajectoryService#recordLlmStep), so
        // there is no single trace this reward belongs to - Langfuse's
        // scores API accepts a sessionId in place of a traceId for exactly
        // this case. A fixed, deterministic score id (keyed by episodeId,
        // not random) is passed so a RE-computation of the same episode's
        // reward (this method can run more than once as more RewardRecords
        // arrive) upserts the same Langfuse score row rather than
        // accumulating duplicates - Langfuse's ingestion docs describe
        // repeat submission of the same score id as an upsert.
        langfuseScoreClient.pushScore(
                "rl-reward-" + episode.getId(),
                null,
                episode.getSessionId(),
                "rl_reward",
                LangfuseScoreClient.ScoreDataType.NUMERIC,
                finalReward,
                safetyViolation
                        ? "safety veto applied (cap " + properties.getSafetyVetoCap() + ")"
                        : null);

        return finalReward;
    }

    private double clip(double v) {
        double lo = properties.getClipMin();
        double hi = properties.getClipMax();
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private double defaultWeightFor(RewardSource source) {
        return switch (source) {
            case HUMAN -> properties.getHumanWeight();
            case JUDGE -> properties.getJudgeWeight();
            case TASK_SUCCESS -> properties.getTaskSuccessWeight();
            case COST -> properties.getCostWeight();
            case LATENCY -> properties.getLatencyWeight();
            // SAFETY is a veto, never blended - weight is irrelevant, keep it neutral.
            case SAFETY -> 1.0;
        };
    }

    private static String toJson(Map<String, Object> breakdown) {
        try {
            return MAPPER.writeValueAsString(breakdown);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize reward breakdown - storing null", e);
            return null;
        }
    }
}
