package com.agenticai.core.quality.dto;

import com.agenticai.core.quality.EvaluationType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * API response for the latest quality summary of an agent.
 */
public record QualitySummaryResponse(
    UUID agentId,
    double overallScore,
    Map<String, Double> scoresByType,
    Map<String, String> feedbackByType,
    int turnNumber,
    Instant evaluatedAt
) {
}
