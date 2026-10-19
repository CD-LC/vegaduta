package com.agenticai.core.platform.ops;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Wire shapes of {@code /api/platform/traces/**} (camelCase JSON). Timestamps
 * are passed through as Langfuse's own ISO-8601 strings. Every nullable field
 * means "Langfuse did not say" and is never coerced to 0.
 */
public final class PlatformTraceDtos {

    private PlatformTraceDtos() {
    }

    public record TraceRow(String id, String name, String timestamp, String userId, String sessionId,
                           String tenantId, Long latencyMs, Double totalCostUsd, Long inputTokens,
                           Long outputTokens, String level, List<String> tags) {
    }

    /** {@link TraceRow} plus input/output, each truncated to 4000 characters. */
    public record TraceDetail(String id, String name, String timestamp, String userId, String sessionId,
                              String tenantId, Long latencyMs, Double totalCostUsd, Long inputTokens,
                              Long outputTokens, String level, List<String> tags, String input, String output) {
    }

    public record TraceListMeta(int page, int limit, long totalItems, int totalPages) {
    }

    public record TraceListResponse(boolean available, @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
                                    List<TraceRow> data, TraceListMeta meta) {
        static TraceListResponse unavailable(String reason, int page, int limit) {
            return new TraceListResponse(false, reason, List.of(), new TraceListMeta(page, limit, 0, 0));
        }
    }

    public record ObservationRow(String id, String type, String name, String startTime, String endTime,
                                 String model, Long inputTokens, Long outputTokens, Double costUsd, String level,
                                 String statusMessage) {
    }

    public record TraceDetailResponse(boolean available, @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
                                      TraceDetail trace, List<ObservationRow> observations) {
        static TraceDetailResponse unavailable(String reason) {
            return new TraceDetailResponse(false, reason, null, List.of());
        }
    }

    public record TraceDayMetric(String date, Long traces, Double costUsd, Long p50LatencyMs, Long p95LatencyMs) {
    }

    public record TraceModelMetric(String model, Long calls, Double costUsd, Long inputTokens, Long outputTokens) {
    }

    public record TraceMetricsResponse(boolean available, @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
                                       List<TraceDayMetric> byDay, List<TraceModelMetric> byModel) {
        static TraceMetricsResponse unavailable(String reason) {
            return new TraceMetricsResponse(false, reason, List.of(), List.of());
        }
    }
}
