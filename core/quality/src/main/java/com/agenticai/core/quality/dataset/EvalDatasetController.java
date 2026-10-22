package com.agenticai.core.quality.dataset;

import com.agenticai.core.quality.calibration.JudgeCalibrationSetService;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.CaseResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.CaseResultResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.DatasetDetailResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.DatasetResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.DimensionScore;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.ImportTurnCaseRequest;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.JudgeCalibrationInfo;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.ReplayedToolCallResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.RunDetailResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.RunResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.TriggerRunRequest;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.UpsertCaseRequest;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.UpsertDatasetRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for eval datasets and their regression runs, nested under the existing quality
 * surface so the console's quality page owns the whole story in one URL family:
 *
 * <ul>
 *   <li>GET /api/agents/{agentId}/quality/datasets - list datasets
 *   <li>POST /api/agents/{agentId}/quality/datasets - create dataset
 *   <li>GET /api/agents/{agentId}/quality/datasets/{datasetId} - dataset + cases
 *   <li>PUT /api/agents/{agentId}/quality/datasets/{datasetId} - update dataset
 *   <li>DELETE /api/agents/{agentId}/quality/datasets/{datasetId} - delete dataset
 *   <li>POST /api/agents/{agentId}/quality/datasets/{datasetId}/cases - add case
 *   <li>PUT /api/agents/{agentId}/quality/datasets/{datasetId}/cases/{caseId} - update case
 *   <li>DELETE /api/agents/{agentId}/quality/datasets/{datasetId}/cases/{caseId} - delete case
 *   <li>POST /api/agents/{agentId}/quality/datasets/{datasetId}/runs - trigger a run
 *   <li>GET /api/agents/{agentId}/quality/datasets/{datasetId}/runs - run history (+deltas)
 *   <li>GET /api/agents/{agentId}/quality/datasets/{datasetId}/runs/{runId} - run + case results
 *   <li>POST /api/agents/{agentId}/quality/datasets/{datasetId}/runs/{runId}/cancel - cancel
 *   <li>POST /api/agents/{agentId}/quality/datasets/{datasetId}/cases/import - save a real turn as
 *       a case (2026-09)
 *   <li>POST /api/agents/{agentId}/quality/datasets/seed-golden - new dataset from the eval
 *       harness's golden set (2026-09)
 * </ul>
 *
 * <p>Run detail responses (2026-09) carry {@code traceId}/{@code langfuseTraceUrl} per case result
 * so a scored case links straight to the Langfuse trace of its replayed turn - see {@link
 * EvalDatasetService#traceIdForRunCase} for how the id is recovered.
 *
 * <p>Security: no {@code SecurityConfig} change is needed or made - a new {@code /api/**} route
 * falls through to {@code .anyRequest().authenticated()} (deliberate, and it also avoids touching a
 * file the concurrent workflow has open). The plan gate below mirrors {@code
 * QualityController#assertPremiumPlan} exactly - this surface EXTENDS that one and must never be a
 * cheaper way onto the judges. Per-resource ownership checks are consciously absent, matching the
 * rest of this module; see {@code EvalDatasetService}'s javadoc for the boundary story and the
 * recorded follow-up.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/quality/datasets")
public class EvalDatasetController {

    private final EvalDatasetService datasetService;
    private final EvalRunService runService;
    private final TenantLimits tenantLimits;
    // 2026-10-02 research ungating: app.evals.free-plan-enabled opens datasets
    // and regression runs to FREE tenants under the tighter FREE_* bounds
    // below (a run still spends model calls, so the caps stay small and the
    // per-user budget gate inside EvalRunService still applies).
    private final boolean freePlanEnabled;

    // Wave E judge-trust stamp on run responses. Setter-injected (optional) so the constructor and
    // every
    // existing unit-test construction stay unchanged; null = no stamp.
    private JudgeCalibrationSetService judgeTrust;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJudgeTrust(JudgeCalibrationSetService judgeTrust) {
        this.judgeTrust = judgeTrust;
    }

    /** FREE-tier bounds - deliberately a fraction of the Premium caps. */
    static final int FREE_MAX_CASES_PER_DATASET = 25;

    static final int FREE_MAX_REPEATS = 2;
    static final java.math.BigDecimal FREE_MAX_BUDGET_USD = new java.math.BigDecimal("2.00");

    public EvalDatasetController(
            EvalDatasetService datasetService,
            EvalRunService runService,
            TenantLimits tenantLimits,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.evals.free-plan-enabled:true}")
                    boolean freePlanEnabled) {
        this.datasetService = datasetService;
        this.runService = runService;
        this.tenantLimits = tenantLimits;
        this.freePlanEnabled = freePlanEnabled;
    }

    /**
     * FREE-tier gate - copied from {@code QualityController#assertPremiumPlan} (see its javadoc for
     * the C1-audit history), not shared with it, because pulling the helper up would mean editing
     * that class for a refactor's sake; the four lines are cheaper than the coupling.
     *
     * <p>Returns the resolved plan so spend-bearing endpoints can apply the FREE-tier clamps
     * (rejects rather than silently truncates - same style as {@code EvalRunService}'s own bounds
     * checks).
     */
    private TenantPlan assertEvalAccess() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        if (plan == TenantPlan.FREE && !freePlanEnabled) {
            throw new TenantLimitExceededException(
                    "Eval datasets and regression runs are a Premium feature - upgrade your plan to use them.");
        }
        return plan;
    }

    // ------------------------------------------------------------------
    // Datasets
    // ------------------------------------------------------------------

    /** GET / - all datasets for this agent, newest first, with case counts. */
    @GetMapping
    public ResponseEntity<List<DatasetResponse>> listDatasets(@PathVariable UUID agentId) {
        assertEvalAccess();
        List<DatasetResponse> datasets =
                datasetService.listDatasets(agentId).stream().map(this::toDatasetResponse).toList();
        return ResponseEntity.ok(datasets);
    }

    /** POST / - create a dataset for this agent. */
    @PostMapping
    public ResponseEntity<DatasetResponse> createDataset(
            @PathVariable UUID agentId, @RequestBody UpsertDatasetRequest request) {
        assertEvalAccess();
        EvalDataset dataset =
                datasetService.createDataset(
                        agentId,
                        request.name(),
                        request.description(),
                        request.passThreshold(),
                        request.toolExecutionEnabled());
        return ResponseEntity.ok(toDatasetResponse(dataset));
    }

    /** GET /{datasetId} - the dataset plus all of its cases. */
    @GetMapping("/{datasetId}")
    public ResponseEntity<DatasetDetailResponse> getDataset(
            @PathVariable UUID agentId, @PathVariable UUID datasetId) {
        assertEvalAccess();
        EvalDataset dataset = datasetService.requireDataset(agentId, datasetId);
        List<CaseResponse> cases =
                datasetService.listCases(datasetId).stream()
                        .map(EvalDatasetController::toCaseResponse)
                        .toList();
        return ResponseEntity.ok(new DatasetDetailResponse(toDatasetResponse(dataset), cases));
    }

    /** PUT /{datasetId} - update name/description/threshold/tool flag. */
    @PutMapping("/{datasetId}")
    public ResponseEntity<DatasetResponse> updateDataset(
            @PathVariable UUID agentId,
            @PathVariable UUID datasetId,
            @RequestBody UpsertDatasetRequest request) {
        assertEvalAccess();
        EvalDataset dataset =
                datasetService.updateDataset(
                        agentId,
                        datasetId,
                        request.name(),
                        request.description(),
                        request.passThreshold(),
                        request.toolExecutionEnabled());
        return ResponseEntity.ok(toDatasetResponse(dataset));
    }

    /**
     * POST /seed-golden - a new dataset for this agent pre-filled from the eval harness's golden
     * set; 409 when this agent already has one. Returns the dataset WITH its cases (same shape as
     * GET /{datasetId}) so the console can render the seeded list without a second call.
     */
    @PostMapping("/seed-golden")
    public ResponseEntity<DatasetDetailResponse> seedGoldenDataset(@PathVariable UUID agentId) {
        assertEvalAccess();
        EvalDataset dataset = datasetService.seedGoldenDataset(agentId);
        List<CaseResponse> cases =
                datasetService.listCases(dataset.getId()).stream()
                        .map(EvalDatasetController::toCaseResponse)
                        .toList();
        return ResponseEntity.ok(new DatasetDetailResponse(toDatasetResponse(dataset), cases));
    }

    /** DELETE /{datasetId} - 409 while a run is live (service enforces). */
    @DeleteMapping("/{datasetId}")
    public ResponseEntity<Void> deleteDataset(
            @PathVariable UUID agentId, @PathVariable UUID datasetId) {
        assertEvalAccess();
        datasetService.deleteDataset(agentId, datasetId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Cases
    // ------------------------------------------------------------------

    /** POST /{datasetId}/cases - add a case (position defaults to append). */
    @PostMapping("/{datasetId}/cases")
    public ResponseEntity<CaseResponse> addCase(
            @PathVariable UUID agentId,
            @PathVariable UUID datasetId,
            @RequestBody UpsertCaseRequest request) {
        TenantPlan plan = assertEvalAccess();
        assertFreeCaseCap(plan, datasetId);
        EvalDatasetCase datasetCase =
                datasetService.addCase(
                        agentId,
                        datasetId,
                        request.userMessage(),
                        request.expectedOutput(),
                        request.expectedTools(),
                        request.position());
        return ResponseEntity.ok(toCaseResponse(datasetCase));
    }

    /**
     * POST /{datasetId}/cases/import - "save this turn as an eval case": appends a case built from
     * a real conversation turn (persisted copy preferred, request body as the fallback - see {@link
     * EvalDatasetService#importTurnCase} for the resolution order and why the body is the practical
     * contract). 400 when no user message could be resolved from either source.
     */
    @PostMapping("/{datasetId}/cases/import")
    public ResponseEntity<CaseResponse> importTurnCase(
            @PathVariable UUID agentId,
            @PathVariable UUID datasetId,
            @RequestBody ImportTurnCaseRequest request) {
        TenantPlan plan = assertEvalAccess();
        assertFreeCaseCap(plan, datasetId);
        EvalDatasetCase datasetCase =
                datasetService.importTurnCase(
                        agentId,
                        datasetId,
                        request.sessionId(),
                        request.turnRef(),
                        request.userMessage(),
                        request.agentResponse(),
                        request.expectedOutput(),
                        request.expectedTools());
        return ResponseEntity.ok(toCaseResponse(datasetCase));
    }

    /**
     * The FREE-tier dataset bound (rejects rather than truncates - same style as the service's own
     * {@code MAX_CASES_PER_DATASET} check, against the tighter cap that applies to this plan).
     */
    private void assertFreeCaseCap(TenantPlan plan, UUID datasetId) {
        if (plan == TenantPlan.FREE
                && datasetService.countCases(datasetId) >= FREE_MAX_CASES_PER_DATASET) {
            throw new IllegalArgumentException(
                    "Free workspaces can keep "
                            + FREE_MAX_CASES_PER_DATASET
                            + " cases per dataset - upgrade for the full "
                            + EvalDatasetService.MAX_CASES_PER_DATASET
                            + ".");
        }
    }

    /** PUT /{datasetId}/cases/{caseId} - update a case in place. */
    @PutMapping("/{datasetId}/cases/{caseId}")
    public ResponseEntity<CaseResponse> updateCase(
            @PathVariable UUID agentId,
            @PathVariable UUID datasetId,
            @PathVariable UUID caseId,
            @RequestBody UpsertCaseRequest request) {
        assertEvalAccess();
        EvalDatasetCase datasetCase =
                datasetService.updateCase(
                        agentId,
                        datasetId,
                        caseId,
                        request.userMessage(),
                        request.expectedOutput(),
                        request.expectedTools(),
                        request.position());
        return ResponseEntity.ok(toCaseResponse(datasetCase));
    }

    /** DELETE /{datasetId}/cases/{caseId}. */
    @DeleteMapping("/{datasetId}/cases/{caseId}")
    public ResponseEntity<Void> deleteCase(
            @PathVariable UUID agentId, @PathVariable UUID datasetId, @PathVariable UUID caseId) {
        assertEvalAccess();
        datasetService.deleteCase(agentId, datasetId, caseId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Runs
    // ------------------------------------------------------------------

    /**
     * POST /{datasetId}/runs - trigger; 409 when a run is already live. The body is optional (V343
     * judge/repeats/budget options); the CI hook's {@code Idempotency-Key} header makes retries
     * safe - a repeated key returns the SAME run row instead of a 409 or a duplicate.
     */
    @PostMapping("/{datasetId}/runs")
    public ResponseEntity<RunResponse> triggerRun(
            @PathVariable UUID agentId,
            @PathVariable UUID datasetId,
            @RequestBody(required = false) TriggerRunRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = "Idempotency-Key",
                            required = false)
                    String idempotencyKey) {
        TenantPlan plan = assertEvalAccess();
        TriggerRunRequest body =
                request != null ? request : new TriggerRunRequest(null, null, null, null);
        Integer repeats = body.repeats();
        java.math.BigDecimal budgetUsd = body.budgetUsd();
        if (plan == TenantPlan.FREE) {
            // FREE-tier run bounds (reject over-cap, never silently clamp -
            // same contract as EvalRunService's own option validation).
            if (repeats != null && repeats > FREE_MAX_REPEATS) {
                throw new IllegalArgumentException(
                        "Free workspaces can repeat a run at most "
                                + FREE_MAX_REPEATS
                                + " times - upgrade for the full "
                                + EvalRunService.MAX_REPEATS
                                + ".");
            }
            if (budgetUsd != null && budgetUsd.compareTo(FREE_MAX_BUDGET_USD) > 0) {
                throw new IllegalArgumentException(
                        "Free workspaces can cap a run at $"
                                + FREE_MAX_BUDGET_USD
                                + " - upgrade for larger budgets.");
            }
            budgetUsd = FREE_MAX_BUDGET_USD;
        }
        EvalRun run =
                runService.triggerRun(
                        agentId,
                        datasetId,
                        new EvalRunService.RunOptions(
                                body.judgeModel(),
                                body.strictJudge(),
                                repeats,
                                budgetUsd,
                                idempotencyKey));
        return ResponseEntity.ok(toRunResponse(run));
    }

    /** GET /{datasetId}/runs - newest first, deltas precomputed. */
    @GetMapping("/{datasetId}/runs")
    public ResponseEntity<List<RunResponse>> listRuns(
            @PathVariable UUID agentId,
            @PathVariable UUID datasetId,
            @RequestParam(defaultValue = "25") int limit) {
        assertEvalAccess();
        datasetService.requireDataset(agentId, datasetId);
        List<RunResponse> runs =
                runService.listRuns(datasetId, limit).stream().map(this::toRunResponse).toList();
        return ResponseEntity.ok(runs);
    }

    /** GET /{datasetId}/runs/{runId} - the run plus every case result. */
    @GetMapping("/{datasetId}/runs/{runId}")
    public ResponseEntity<RunDetailResponse> getRun(
            @PathVariable UUID agentId, @PathVariable UUID datasetId, @PathVariable UUID runId) {
        assertEvalAccess();
        datasetService.requireDataset(agentId, datasetId);
        EvalRun run = runService.requireRun(datasetId, runId);
        // Indexed loop, not a stream: the position of a result in this
        // (position, createdAt)-ordered list is the replay session index the
        // worker used - see EvalDatasetService#traceIdForRunCase.
        List<EvalRunCaseResult> rows = runService.listCaseResults(runId);
        List<CaseResultResponse> results = new java.util.ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            String traceId = datasetService.traceIdForRunCase(runId, i);
            results.add(
                    toCaseResultResponse(
                            rows.get(i), traceId, datasetService.langfuseTraceUrl(traceId)));
        }
        return ResponseEntity.ok(new RunDetailResponse(toRunResponse(run), results));
    }

    /** POST /{datasetId}/runs/{runId}/cancel - cooperative, see EvalRun#requestCancel. */
    @PostMapping("/{datasetId}/runs/{runId}/cancel")
    public ResponseEntity<RunResponse> cancelRun(
            @PathVariable UUID agentId, @PathVariable UUID datasetId, @PathVariable UUID runId) {
        assertEvalAccess();
        EvalRun run = runService.cancelRun(agentId, datasetId, runId);
        return ResponseEntity.ok(toRunResponse(run));
    }

    /**
     * GET /{datasetId}/runs/{runId}/junit - the run as a JUnit XML report (V343, the C2 CI hook):
     * one {@code <testcase>} per case result, {@code <failure>} on {@code passed=false}, {@code
     * <error>} on replay errors, {@code <skipped>} on not-evaluable cases. A run whose verdict is
     * FAILED produces a report with failures - the standard "non-zero on test failure" CI contract
     * falls out of however the caller's runner interprets the XML.
     */
    @GetMapping(value = "/{datasetId}/runs/{runId}/junit", produces = "application/xml")
    public ResponseEntity<String> junitReport(
            @PathVariable UUID agentId, @PathVariable UUID datasetId, @PathVariable UUID runId) {
        assertEvalAccess();
        EvalDataset dataset = datasetService.requireDataset(agentId, datasetId);
        EvalRun run = runService.requireRun(datasetId, runId);
        List<EvalRunCaseResult> results = runService.listCaseResults(runId);
        return ResponseEntity.ok(JunitReport.of(dataset.getName(), run, results));
    }

    // ------------------------------------------------------------------
    // DTO assembly
    // ------------------------------------------------------------------

    private DatasetResponse toDatasetResponse(EvalDataset dataset) {
        return new DatasetResponse(
                dataset.getId(),
                dataset.getAgentId(),
                dataset.getName(),
                dataset.getDescription(),
                dataset.getPassThreshold(),
                dataset.isToolExecutionEnabled(),
                datasetService.countCases(dataset.getId()),
                dataset.getContentHash(),
                dataset.getCreatedBy(),
                dataset.getCreatedAt(),
                dataset.getUpdatedAt());
    }

    private static CaseResponse toCaseResponse(EvalDatasetCase datasetCase) {
        return new CaseResponse(
                datasetCase.getId(),
                datasetCase.getUserMessage(),
                datasetCase.getExpectedOutput(),
                EvalDatasetService.parseExpectedTools(datasetCase.getExpectedTools()),
                datasetCase.getPosition(),
                datasetCase.getCreatedAt(),
                datasetCase.getUpdatedAt());
    }

    private RunResponse toRunResponse(EvalRun run) {
        // Baseline = most recent earlier SUCCEEDED-and-measured run; all
        // three fields null when there is nothing to compare against
        // (honest absence, per TokenBaseline's rule - see EvalRunService).
        Optional<EvalRun> baseline =
                run.getOverallScore() != null ? runService.findBaseline(run) : Optional.empty();
        return new RunResponse(
                run.getId(),
                run.getDatasetId(),
                run.getAgentId(),
                run.getAgentVersionId(),
                run.getStatus().name(),
                run.getVerdict() != null ? run.getVerdict().name() : null,
                run.getPassThreshold(),
                run.getOverallScore(),
                run.getCaseCount(),
                run.getScoredCaseCount(),
                run.getFailedAssertionCount(),
                run.getErroredCaseCount(),
                run.getJudgeModel(),
                run.getDatasetHash(),
                run.getRequestedJudgeModel(),
                run.isStrictJudge(),
                run.getRepeats(),
                run.getBudgetUsd(),
                run.getCostUsd(),
                run.getErrorMessage(),
                run.getRequestedBy(),
                run.isCancelRequested(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getFinishedAt(),
                baseline.map(EvalRun::getId).orElse(null),
                baseline.map(EvalRun::getOverallScore).orElse(null),
                baseline.map(b -> run.getOverallScore() - b.getOverallScore()).orElse(null),
                judgeCalibrationFor(run));
    }

    /**
     * Wave E: null (and so absent from the JSON) unless calibration is enabled and the run used a
     * judge.
     */
    private JudgeCalibrationInfo judgeCalibrationFor(EvalRun run) {
        if (judgeTrust == null) {
            return null;
        }
        return judgeTrust
                .trustFor(run.getJudgeModel())
                .map(t -> new JudgeCalibrationInfo(t.status(), t.kappa(), t.n()))
                .orElse(null);
    }

    private static CaseResultResponse toCaseResultResponse(
            EvalRunCaseResult result, String traceId, String langfuseTraceUrl) {
        return new CaseResultResponse(
                result.getId(),
                result.getCaseId(),
                result.getPosition(),
                result.getUserMessage(),
                result.getExpectedOutput(),
                EvalDatasetService.parseExpectedTools(result.getExpectedTools()),
                result.getAgentResponse(),
                parseToolCalls(result.getToolCallsJson()),
                parseDimensionScores(result.getScoresJson()),
                result.getOverallScore(),
                result.getExpectedToolsSatisfied(),
                result.getPassed(),
                result.getStatus().name(),
                result.getErrorMessage(),
                result.getFinishedAt(),
                traceId,
                langfuseTraceUrl);
    }

    /**
     * Shared read-only mapper for the stored tool-calls documents - static and thread-safe, same
     * pattern as the services' MAPPER constants.
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper TOOL_CALLS_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Stored tool-calls JSON -> typed DTO list; parse-failure-safe. */
    private static List<ReplayedToolCallResponse> parseToolCalls(String toolCallsJson) {
        if (toolCallsJson == null || toolCallsJson.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    TOOL_CALLS_MAPPER.readTree(toolCallsJson);
            List<ReplayedToolCallResponse> calls = new java.util.ArrayList<>();
            if (node.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode entry : node) {
                    calls.add(
                            new ReplayedToolCallResponse(
                                    entry.path("toolName").asText(null),
                                    entry.path("input").asText(null),
                                    entry.path("output").asText(null)));
                }
            }
            return calls;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Stored scores JSON -> {@code Map<dimension, DimensionScore>}. Unparseable stored text
     * degrades to an empty map, never a 500 on the read path - same blast-radius posture as {@code
     * EvalDatasetService#parseExpectedTools}.
     */
    private static Map<String, DimensionScore> parseDimensionScores(String scoresJson) {
        Map<String, Object> raw = EvalRunService.parseScoresJson(scoresJson);
        Map<String, DimensionScore> shaped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> dimension) {
                Object score = dimension.get("score");
                shaped.put(
                        entry.getKey(),
                        new DimensionScore(
                                score instanceof Number number ? number.doubleValue() : null,
                                dimension.get("status") != null
                                        ? dimension.get("status").toString()
                                        : null,
                                dimension.get("feedback") != null
                                        ? dimension.get("feedback").toString()
                                        : null));
            }
        }
        return shaped;
    }
}
