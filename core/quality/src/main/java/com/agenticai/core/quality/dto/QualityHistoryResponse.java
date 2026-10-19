package com.agenticai.core.quality.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API response for historical quality data (suitable for charting).
 */
public record QualityHistoryResponse(
    UUID agentId,
    List<String> timestamps,
    List<Double> overallScores,
    Map<String, List<Double>> scoresByType,
    int recordCount
) {
}
