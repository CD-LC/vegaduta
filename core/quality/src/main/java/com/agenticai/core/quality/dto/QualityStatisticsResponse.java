package com.agenticai.core.quality.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * API response for quality statistics and summary metrics.
 *
 * <p>{@code averageQualityScore} is {@code null} (serialized as JSON null)
 * when the agent has no evaluation records - clients must render "no data",
 * not assume a number is always present.
 */
public record QualityStatisticsResponse(
    UUID agentId,
    long totalEvaluations,
    Double averageQualityScore,
    Map<String, Double> averageScoresByType,
    Instant generatedAt
) {
}
