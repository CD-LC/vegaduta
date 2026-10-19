package com.agenticai.core.quality.dataset.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request/response records for the eval-dataset REST surface
 * ({@code EvalDatasetController}). One file of small records rather than
 * fifteen single-record files - the same grouping trade
 * {@code QualityController.QualityConfigResponse} already makes (a nested
 * record) applied at package scale, because none of these carries behavior
 * and every consumer imports several at once. Entities never cross the
 * controller boundary; these do.
 */
public final class EvalDatasetDtos {

    private EvalDatasetDtos() {
        // Namespace holder - never instantiated.
    }

    // ------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------

    /**
     * Create/update payload for a dataset. {@code passThreshold} and
     * {@code toolExecutionEnabled} are nullable so a create can omit them
     * (defaults 0.7 / false, applied in the service where the validation
     * rules live).
     */
    public record UpsertDatasetRequest(
        String name,
        String description,
        Double passThreshold,
        Boolean toolExecutionEnabled
    ) {
    }

    /**
     * Create/update payload for one case. {@code expectedTools} is the
     * parsed list form (the API never exposes the raw JSON-text storage
     * encoding); {@code position} nullable = append at the end.
     */
    public record UpsertCaseRequest(
        String userMessage,
        String expectedOutput,
        List<String> expectedTools,
        Integer position
    ) {
    }

    /**
     * "Save this turn as an eval case" payload ({@code POST
     * /{datasetId}/cases/import}). {@code sessionId} + {@code turnRef} name
     * the persisted Playground turn (the same pair the RL feedback control
     * already sends, see {@code TurnFeedbackRequest} in core/rl); the server
     * resolves the user message / assistant reply from the RL trajectory
     * when one was recorded for that turn. {@code userMessage} /
     * {@code agentResponse} are the client-side copy of the same two texts
     * and are REQUIRED in practice: an RL episode only exists for a turn when
     * a turnRef was sent AND the RL module recorded it, so the lookup is
     * best-effort and the body is the fallback - see {@code
     * EvalDatasetService#importTurnCase}. {@code expectedOutput} overrides
     * the default (the assistant's reply, pre-filled as the reference answer
     * for the ACCURACY judge); {@code expectedTools} is optional.
     */
    public record ImportTurnCaseRequest(
        String sessionId,
        String turnRef,
        String userMessage,
        String agentResponse,
        String expectedOutput,
        List<String> expectedTools
    ) {
    }

    /**
     * Optional trigger payload for {@code POST /{datasetId}/runs} (V343):
     * all fields nullable = defaults. {@code judgeModel} is a per-run judge
     * override (a catalog model id for the tenant's active judge provider);
     * {@code strictJudge} makes an identical judge/agent model a 400 rather
     * than a warning; {@code repeats} (1-5) is the non-determinism guard
     * (strict majority pass per case); {@code budgetUsd} (default $5) cancels
     * the run once observed replay spend exceeds it. The CI hook's
     * {@code Idempotency-Key} travels as a header, not a body field.
     */
    public record TriggerRunRequest(
        String judgeModel,
        Boolean strictJudge,
        Integer repeats,
        java.math.BigDecimal budgetUsd
    ) {
    }

    // ------------------------------------------------------------------
    // Responses
    // ------------------------------------------------------------------

    public record DatasetResponse(
        UUID id,
        UUID agentId,
        String name,
        String description,
        double passThreshold,
        boolean toolExecutionEnabled,
        long caseCount,
        String contentHash,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record DatasetDetailResponse(
        DatasetResponse dataset,
        List<CaseResponse> cases
    ) {
    }

    public record CaseResponse(
        UUID id,
        String userMessage,
        String expectedOutput,
        List<String> expectedTools,
        int position,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    /**
     * One run, with its diff-vs-previous precomputed server-side:
     * {@code baselineRunId}/{@code baselineScore}/{@code scoreDelta} name
     * the most recent earlier SUCCEEDED-and-measured run of the same
     * dataset, or are all null when there is nothing to compare against
     * (first run, or no measured predecessor - absence, never zero).
     */
    public record RunResponse(
        UUID id,
        UUID datasetId,
        UUID agentId,
        UUID agentVersionId,
        String status,
        String verdict,
        double passThreshold,
        Double overallScore,
        int caseCount,
        int scoredCaseCount,
        int failedAssertionCount,
        int erroredCaseCount,
        String judgeModel,
        String datasetHash,
        String requestedJudgeModel,
        boolean strictJudge,
        int repeats,
        java.math.BigDecimal budgetUsd,
        java.math.BigDecimal costUsd,
        String errorMessage,
        String requestedBy,
        boolean cancelRequested,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID baselineRunId,
        Double baselineScore,
        Double scoreDelta,
        /**
         * Wave E judge-trust: whether the LLM judge that scored this run was ever calibrated against human
         * labels (status UNCALIBRATED | LOW | OK, latest kappa, n). Omitted from the JSON entirely (not
         * {@code null}) unless {@code app.quality.eval.calibration.enabled} and the run used a judge, so
         * flag-off responses are byte-identical. Informational: it never changes pass/fail.
         */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        JudgeCalibrationInfo judgeCalibration
    ) {
    }

    /** {@code status}: UNCALIBRATED (no usable calibration), LOW (latest kappa under the threshold) or OK. */
    public record JudgeCalibrationInfo(String status, Double kappa, Integer n) {
    }

    public record RunDetailResponse(
        RunResponse run,
        List<CaseResultResponse> caseResults
    ) {
    }

    /** One dimension's judge outcome inside {@link CaseResultResponse#scores}. */
    public record DimensionScore(
        Double score,
        String status,
        String feedback
    ) {
    }

    public record ReplayedToolCallResponse(
        String toolName,
        String input,
        String output
    ) {
    }

    /**
     * {@code traceId}/{@code langfuseTraceUrl} (2026-09): the OTel trace id
     * {@code LlmMetricsAdvisor} durably logged for the case's replayed turn
     * (looked up by the replay session id at read time - no result column
     * exists for it, see {@code EvalDatasetService#traceIdForRunCase}) and
     * the browser-facing Langfuse deep link for it. Both null when tracing
     * or Langfuse is not configured, or the replay never reached the model
     * (an ERROR case) - absence, never a fabricated link.
     */
    public record CaseResultResponse(
        UUID id,
        UUID caseId,
        int position,
        String userMessage,
        String expectedOutput,
        List<String> expectedTools,
        String agentResponse,
        List<ReplayedToolCallResponse> toolCalls,
        Map<String, DimensionScore> scores,
        Double overallScore,
        Boolean expectedToolsSatisfied,
        Boolean passed,
        String status,
        String errorMessage,
        Instant finishedAt,
        String traceId,
        String langfuseTraceUrl
    ) {
    }
}
