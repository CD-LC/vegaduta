package com.agenticai.core.agents.experiment;

import java.time.Instant;
import java.util.UUID;

/**
 * Request/response records for the experiment REST surface ({@link
 * AgentExperimentController}) - same one-file grouping {@code EvalGateDtos}
 * documents for the eval-gate package.
 */
public final class AgentExperimentDtos {

    private AgentExperimentDtos() {
        // Namespace holder - never instantiated.
    }

    public record CreateExperimentRequest(
        String name,
        UUID treatmentVersionId,
        Integer trafficPercent,
        Integer autoStopErrorThresholdPct
    ) {
    }

    public record StopExperimentRequest(String reason) {
    }

    public record ExperimentResponse(
        UUID id,
        UUID agentId,
        String name,
        ExperimentStatus status,
        UUID controlAgentId,
        UUID treatmentAgentId,
        UUID treatmentVersionId,
        int trafficPercent,
        Integer autoStopErrorThresholdPct,
        String stopReason,
        String createdBy,
        Instant createdAt,
        Instant startedAt,
        Instant stoppedAt
    ) {
        public static ExperimentResponse of(AgentExperiment e) {
            return new ExperimentResponse(
                e.getId(), e.getAgentId(), e.getName(), e.getStatus(),
                e.getControlAgentId(), e.getTreatmentAgentId(), e.getTreatmentVersionId(),
                e.getTrafficPercent(), e.getAutoStopErrorThresholdPct(), e.getStopReason(),
                e.getCreatedBy(), e.getCreatedAt(), e.getStartedAt(), e.getStoppedAt());
        }
    }

    public record VariantStatsResponse(
        UUID agentId,
        long callCount,
        Double avgLatencyMs,
        Double totalCostUsd,
        Double errorRatePct,
        long feedbackCount,
        Double thumbsUpRatePct
    ) {
        public static VariantStatsResponse of(AgentExperimentService.VariantStats s) {
            return new VariantStatsResponse(
                s.agentId(), s.callCount(), s.avgLatencyMs(), s.totalCostUsd(),
                s.errorRatePct(), s.feedbackCount(), s.thumbsUpRatePct());
        }
    }

    public record ExperimentMetricsResponse(
        UUID experimentId,
        VariantStatsResponse control,
        VariantStatsResponse treatment
    ) {
        public static ExperimentMetricsResponse of(AgentExperimentService.ExperimentMetrics m) {
            return new ExperimentMetricsResponse(
                m.experimentId(), VariantStatsResponse.of(m.control()), VariantStatsResponse.of(m.treatment()));
        }
    }
}
