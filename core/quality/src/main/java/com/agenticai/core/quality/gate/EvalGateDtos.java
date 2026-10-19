package com.agenticai.core.quality.gate;

import java.time.Instant;
import java.util.UUID;

/**
 * Request/response records for the eval-gate REST surface
 * ({@code EvalGateController}) - same one-file grouping trade
 * {@code EvalDatasetDtos} documents for the dataset package.
 */
public final class EvalGateDtos {

    private EvalGateDtos() {
        // Namespace holder - never instantiated.
    }

    /**
     * PUT payload. {@code enabled} null means "keep current / true on create".
     * {@code maxRunAgeHours} null clears the freshness window.
     * {@code requireSimulation} null means "off" (V394 - the staging leg is
     * opt-in); {@code maxSimulationAgeHours} null clears the sim window.
     */
    public record UpsertGateRequest(
        UUID datasetId,
        Boolean enabled,
        Integer maxRunAgeHours,
        Boolean requireSimulation,
        Integer maxSimulationAgeHours
    ) {
    }

    public record GateResponse(
        UUID id,
        UUID agentId,
        UUID datasetId,
        String datasetName,
        boolean enabled,
        Integer maxRunAgeHours,
        boolean requireSimulation,
        Integer maxSimulationAgeHours,
        String updatedBy,
        Instant updatedAt
    ) {
    }

    /**
     * The serialized {@link GateEvaluation} - identical fields; the record
     * exists so the REST shape is named and stable even if the internal
     * evaluation type grows fields the API does not expose.
     */
    public record GateEvaluationResponse(
        boolean configured,
        boolean enabled,
        UUID agentId,
        UUID versionId,
        UUID datasetId,
        String datasetName,
        Integer maxRunAgeHours,
        boolean allowed,
        String reason,
        String message,
        UUID qualifyingRunId,
        String verdict,
        Double overallScore,
        Instant runFinishedAt,
        boolean simulationRequired,
        UUID simulationSessionId,
        String simulationVerdict,
        Instant simulationFinishedAt
    ) {
        public static GateEvaluationResponse of(GateEvaluation e) {
            return new GateEvaluationResponse(
                e.configured(), e.enabled(), e.agentId(), e.versionId(),
                e.datasetId(), e.datasetName(), e.maxRunAgeHours(),
                e.allowed(), e.reason().name(), e.message(),
                e.qualifyingRunId(), e.verdict(), e.overallScore(), e.runFinishedAt(),
                e.simulationRequired(), e.simulationSessionId(),
                e.simulationVerdict(), e.simulationFinishedAt());
        }
    }

    /**
     * GET / response: the configured gate (or null) plus the evaluation for
     * the agent's CURRENT live config - what a "promote now" badge needs.
     * {@code headVersionId} is null when the agent has no version rows yet.
     */
    public record GateStatusResponse(
        GateResponse gate,
        UUID headVersionId,
        GateEvaluationResponse headEvaluation
    ) {
    }
}
