package com.agenticai.core.quality;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * High-level service for agent response quality evaluation.
 *
 * Coordinates:
 * - Running evaluations asynchronously after responses
 * - Storing results in the database
 * - Querying historical evaluation data
 * - Aggregating scores for dashboards
 */
@Service
public class QualityEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(QualityEvaluationService.class);

    private final EvalEngine evalEngine;
    private final AgentQualityRecordRepository qualityRepository;

    public QualityEvaluationService(
        EvalEngine evalEngine,
        AgentQualityRecordRepository qualityRepository
    ) {
        this.evalEngine = evalEngine;
        this.qualityRepository = qualityRepository;
    }

    /**
     * Asynchronously evaluate a response and store the results.
     *
     * This is the main entry point - call this after an agent generates a response.
     * Returns immediately (does not wait for eval to complete).
     *
     * @param agentId the agent that generated the response
     * @param turnNumber which turn in the conversation
     * @param context evaluation context (user message, response, etc.)
     * @param isPremium whether the tenant has premium features
     */
    public void evaluateResponseAsync(
        UUID agentId,
        int turnNumber,
        EvaluationContext context,
        boolean isPremium
    ) {
        List<EvaluationType> enabledTypes = EvalEngine.getEnabledForTier(isPremium);

        evalEngine
            .evaluateAsync(context, enabledTypes)
            .thenAccept(result -> {
                try {
                    AgentQualityRecord stored = evalEngine.recordEvaluation(
                        agentId.toString(),
                        turnNumber,
                        context,
                        result,
                        qualityRepository
                    );
                    if (stored != null) {
                        log.debug("Quality evaluation recorded for agent {} turn {}", agentId, turnNumber);
                    }
                } catch (Exception e) {
                    log.error("Failed to store quality evaluation result", e);
                }
            })
            .exceptionally(e -> {
                log.error("Quality evaluation pipeline failed", e);
                return null;
            });
    }

    /**
     * Get the latest quality summary for an agent.
     *
     * @param agentId the agent ID
     * @return latest quality record if available
     */
    public Optional<AgentQualityRecord> getLatestSummary(UUID agentId) {
        AgentQualityRecord latest = qualityRepository.findFirstByAgentIdOrderByCreatedAtDesc(agentId);
        return Optional.ofNullable(latest);
    }

    /**
     * Get historical quality data for an agent.
     *
     * Returns a map suitable for chart rendering:
     * {
     *   "timestamps": [...],
     *   "overall_scores": [...],
     *   "helpfulness_scores": [...],
     *   "accuracy_scores": [...],
     *   ...
     * }
     *
     * @param agentId the agent ID
     * @param limit max number of recent records to return
     * @return chart-ready data structure
     */
    public Map<String, Object> getHistoricalData(UUID agentId, int limit) {
        // Page#getContent() is unmodifiable - copy before re-sorting ASC for
        // the chart (the repo query returns DESC); in-place sort threw
        // UnsupportedOperationException and 500'd /quality/history in prod.
        List<AgentQualityRecord> records = new ArrayList<>(qualityRepository
            .findByAgentIdOrderByCreatedAtDesc(agentId, org.springframework.data.domain.PageRequest.of(0, limit))
            .getContent());

        records.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));

        Map<String, Object> data = new HashMap<>();

        List<String> timestamps = records.stream()
            .map(r -> r.getCreatedAt().toString())
            .toList();
        data.put("timestamps", timestamps);

        List<Double> overallScores = records.stream()
            .map(AgentQualityRecord::getOverallScore)
            .toList();
        data.put("overall_scores", overallScores);

        // Add per-type scores
        for (EvaluationType type : EvaluationType.values()) {
            List<Double> typeScores = records.stream()
                .map(r -> {
                    Double score = r.getScores().get(type.name());
                    return score != null ? score : Double.NaN;
                })
                .filter(s -> !Double.isNaN(s))
                .toList();
            if (!typeScores.isEmpty()) {
                data.put(type.name().toLowerCase(java.util.Locale.ROOT) + "_scores", typeScores);
            }
        }

        return data;
    }

    /**
     * Get aggregated statistics for an agent.
     *
     * @param agentId the agent ID
     * @return summary stats (average score, total evals, etc.)
     */
    public QualityStatistics getStatistics(UUID agentId) {
        long totalRecords = qualityRepository.countByAgentId(agentId);
        // Null when this agent has no evaluation records - reported as null
        // (no data), NOT coerced to a neutral 0.5 that would read as a real
        // mid-range measurement.
        Double averageScore = qualityRepository.getAverageScoreForAgent(agentId);

        Map<String, Double> averagesByType = new HashMap<>();
        for (EvaluationType type : EvaluationType.values()) {
            Double avg = qualityRepository.getAverageScoreForEvaluationType(agentId, type.name());
            if (avg != null) {
                averagesByType.put(type.name(), avg);
            }
        }

        return new QualityStatistics(
            agentId,
            totalRecords,
            averageScore,
            averagesByType,
            Instant.now()
        );
    }

    /**
     * Statistics for agent quality over time.
     *
     * @param averageQualityScore average of the stored overall scores, or
     *     {@code null} when this agent has no evaluation records yet -
     *     honest absence, never a fabricated neutral value
     */
    public record QualityStatistics(
        UUID agentId,
        long totalEvaluations,
        Double averageQualityScore,
        Map<String, Double> averageScoresByType,
        Instant generatedAt
    ) {
    }
}
