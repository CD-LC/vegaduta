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
     * Create/update payload for a dataset. {@code passThreshold}, {@code toolExecutionEnabled} and {@code mode}
     * are nullable so a create can omit them (threshold 0.7, no pinned mode: the dataset follows the workspace's
     * default, the full pipeline as shipped; applied in the service where the validation rules live).
     *
     * <p>{@code mode} is the dataset's own replay mode: {@code FULL} pins the production pipeline in the eval
     * sandbox, {@code QUICK} pins a text-only replay, {@code DEFAULT} (an update only) removes the pin so the
     * dataset follows the workspace default again; null leaves it unchanged. {@code toolExecutionEnabled} is the
     * older boolean spelling of the same choice (true = FULL, false = QUICK) and is used only when {@code mode}
     * is null. A run may still name its own mode.
     *
     * <p>{@code autoApproveTools} (null = no / unchanged) lets the eval sandbox approve tool calls that would
     * wait for a person; it needs a dataset that does not pin quick mode and a tenant the operator declared
     * non-production.
     */
    public record UpsertDatasetRequest(
        String name,
        String description,
        Double passThreshold,
        Boolean toolExecutionEnabled,
        Boolean autoApproveTools,
        String mode
    ) {

        /** The request as it was before a dataset could pin a mode by name. */
        public UpsertDatasetRequest(
            String name, String description, Double passThreshold, Boolean toolExecutionEnabled,
            Boolean autoApproveTools
        ) {
            this(name, description, passThreshold, toolExecutionEnabled, autoApproveTools, null);
        }

        /** The request as it was before the sandbox auto-approval existed. */
        public UpsertDatasetRequest(
            String name, String description, Double passThreshold, Boolean toolExecutionEnabled
        ) {
            this(name, description, passThreshold, toolExecutionEnabled, null, null);
        }
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
     *
     * <p>{@code mode} is {@code FULL} (the production pipeline in the eval sandbox: the default) or {@code QUICK}
     * (the agent's tools are withheld, text only); null takes the dataset's pinned mode, else the workspace's
     * default ({@code quality.eval.defaultMode}, then {@code app.quality.eval.default-mode}).
     */
    public record TriggerRunRequest(
        String judgeModel,
        Boolean strictJudge,
        Integer repeats,
        java.math.BigDecimal budgetUsd,
        String mode
    ) {

        /** The request as it was before a run could name its mode. */
        public TriggerRunRequest(String judgeModel, Boolean strictJudge, Integer repeats, java.math.BigDecimal budgetUsd) {
            this(judgeModel, strictJudge, repeats, budgetUsd, null);
        }
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
        /** The dataset's pinned mode as a boolean (true FULL, false QUICK); null = it pins none. Prefer {@code mode}. */
        Boolean toolExecutionEnabled,
        boolean autoApproveTools,
        /** The mode the dataset pins: FULL or QUICK; null = none, runs take the workspace default. */
        String mode,
        /** The mode a run of this dataset takes when it names none right now: the pin, else the workspace default. */
        String effectiveMode,
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
        /**
         * How the run replayed its cases: FULL (the production pipeline in the eval sandbox) or QUICK (the agent's
         * tools withheld, text only). Frozen when the run was triggered.
         */
        String mode,
        /** The run replayed under the eval sandbox's auto-approval of tool calls (a person decided none of them). */
        boolean autoApproved,
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
        List<CaseResultResponse> caseResults,
        /**
         * The tools the eval sandbox answered with a simulated result in at least one case of this run (they could
         * change data or reach outside the platform, so they never ran), by name, each once; empty for a quick run
         * and for a run where every tool was safe to run. The report of what this run could not really exercise.
         */
        List<String> simulatedTools
    ) {

        public RunDetailResponse(RunResponse run, List<CaseResultResponse> caseResults) {
            this(run, caseResults, List.of());
        }
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
        String output,
        /**
         * How the turn recorded the call's end: OK, FAILED, NOT_RUN, AWAITING_APPROVAL, REPLAYED or SIMULATED (the
         * eval sandbox withheld the tool's effect). Omitted when the turn recorded none (older results), so those
         * responses are unchanged.
         */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String status
    ) {

        public ReplayedToolCallResponse(String toolName, String input, String output) {
            this(toolName, input, output, null);
        }
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
