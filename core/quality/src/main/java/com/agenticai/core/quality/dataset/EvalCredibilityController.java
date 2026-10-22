package com.agenticai.core.quality.dataset;

import com.agenticai.core.quality.calibration.JudgeCalibration.LabeledPair;
import com.agenticai.core.quality.calibration.JudgeCalibrationService;
import com.agenticai.core.quality.calibration.JudgeCalibrationSetService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Eval credibility + reproducibility surface (V375), kept apart from {@link EvalDatasetController}
 * so that file stays the dataset/run CRUD it was:
 *
 * <ul>
 *   <li>PUT /api/agents/{agentId}/quality/datasets/{datasetId}/cases/{caseId}/grader - set (body
 *       {@code {"grader": {...}}}) or clear ({@code {"grader": null}}) a case's code grader
 *   <li>GET /api/agents/{agentId}/quality/datasets/{datasetId}/runs/{runId}/bundle - the run as a
 *       JSON bundle with a SHA-256 over its canonical content
 *   <li>GET /api/agents/{agentId}/quality/datasets/{datasetId}/runs/{runId}/export.jsonl - the run
 *       as NDJSON (the offline-optimization export format)
 *   <li>GET /api/agents/{agentId}/quality/datasets/{datasetId}/export.jsonl - the dataset's live
 *       cases as NDJSON (an optimizer's training set)
 *   <li>POST /api/agents/{agentId}/quality/datasets/seed-research - new dataset from the bundled
 *       research-task starter set
 *   <li>POST /api/agents/{agentId}/quality/judge-calibration - kappa / order-swap consistency /
 *       self-preference of the tenant's judge against human labels (404 unless {@code
 *       app.quality.eval.calibration.enabled})
 * </ul>
 *
 * Same Premium plan gate as the rest of the eval surface (copied, not shared - see the note on
 * EvalDatasetController's own assertPremiumPlan).
 */
@RestController
public class EvalCredibilityController {

    private final EvalDatasetService datasetService;
    private final EvalRunService runService;
    private final ResearchSeedService researchSeedService;
    private final JudgeCalibrationService calibrationService;
    private final JudgeCalibrationSetService calibrationSetService;
    private final TenantLimits tenantLimits;

    public EvalCredibilityController(
            EvalDatasetService datasetService,
            EvalRunService runService,
            ResearchSeedService researchSeedService,
            JudgeCalibrationService calibrationService,
            JudgeCalibrationSetService calibrationSetService,
            TenantLimits tenantLimits) {
        this.datasetService = datasetService;
        this.runService = runService;
        this.researchSeedService = researchSeedService;
        this.calibrationService = calibrationService;
        this.calibrationSetService = calibrationSetService;
        this.tenantLimits = tenantLimits;
    }

    private void assertPremiumPlan() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        if (plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                    "Eval datasets and regression runs are a Premium feature - upgrade your plan to"
                            + " use them.");
        }
    }

    /**
     * {@code grader} is the spec object (regex / exact / json_schema); null or absent clears it.
     */
    public record SetGraderRequest(JsonNode grader) {}

    public record GraderResponse(UUID caseId, JsonNode grader, String datasetContentHash) {}

    public record SeedResponse(UUID datasetId, String name, long caseCount, String contentHash) {}

    /**
     * Exactly one of {@code examples} (inline labels, not stored) or {@code calibrationSetId} (a
     * stored set, result recorded for drift tracking) per request.
     */
    public record CalibrationRequest(List<LabeledPair> examples, UUID calibrationSetId) {}

    @PutMapping("/api/agents/{agentId}/quality/datasets/{datasetId}/cases/{caseId}/grader")
    public ResponseEntity<GraderResponse> setGrader(
            @PathVariable UUID agentId,
            @PathVariable UUID datasetId,
            @PathVariable UUID caseId,
            @RequestBody SetGraderRequest request) {
        assertPremiumPlan();
        JsonNode grader = request != null ? request.grader() : null;
        String spec = grader == null || grader.isNull() ? null : grader.toString();
        EvalDatasetCase saved = datasetService.setCaseGrader(agentId, datasetId, caseId, spec);
        EvalDataset dataset = datasetService.requireDataset(agentId, datasetId);
        JsonNode echoed = null;
        if (saved.getGraderSpec() != null) {
            try {
                echoed =
                        com.agenticai.core.json.JsonMappers.plain().readTree(saved.getGraderSpec());
            } catch (Exception ignored) {
                echoed = null;
            }
        }
        return ResponseEntity.ok(
                new GraderResponse(saved.getId(), echoed, dataset.getContentHash()));
    }

    @GetMapping("/api/agents/{agentId}/quality/datasets/{datasetId}/runs/{runId}/bundle")
    public ResponseEntity<EvalRunBundle.Bundle> runBundle(
            @PathVariable UUID agentId, @PathVariable UUID datasetId, @PathVariable UUID runId) {
        assertPremiumPlan();
        EvalDataset dataset = datasetService.requireDataset(agentId, datasetId);
        EvalRun run = runService.requireRun(datasetId, runId);
        // Wave E: whether the run's LLM judge was ever calibrated (empty - and the field absent -
        // unless
        // app.quality.eval.calibration.enabled). Goes in the unhashed 'live' section.
        JudgeCalibrationSetService.JudgeTrust trust =
                calibrationSetService != null
                        ? calibrationSetService.trustFor(run.getJudgeModel()).orElse(null)
                        : null;
        return ResponseEntity.ok(
                EvalRunBundle.build(
                        dataset,
                        datasetService.listCases(datasetId),
                        run,
                        runService.listCaseResults(runId),
                        trust));
    }

    /**
     * GET .../runs/{runId}/export.jsonl - the run as NDJSON (header line + one line per case
     * result) for offline optimization tooling. Same plan gate as {@link #runBundle} - it exports
     * the same content minus the tamper digest.
     */
    @GetMapping(
            value = "/api/agents/{agentId}/quality/datasets/{datasetId}/runs/{runId}/export.jsonl",
            produces = "application/x-ndjson")
    public ResponseEntity<String> runExportJsonl(
            @PathVariable UUID agentId, @PathVariable UUID datasetId, @PathVariable UUID runId) {
        assertPremiumPlan();
        EvalDataset dataset = datasetService.requireDataset(agentId, datasetId);
        EvalRun run = runService.requireRun(datasetId, runId);
        String body = EvalJsonlExport.run(dataset, run, runService.listCaseResults(runId));
        return ResponseEntity.ok()
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"eval-run-" + runId + ".jsonl\"")
                .body(body);
    }

    /**
     * GET .../datasets/{datasetId}/export.jsonl - the dataset's live cases as NDJSON (the
     * optimizer's candidate training set). The header line carries the current content hash so an
     * optimizer can prove which dataset snapshot it trained against.
     */
    @GetMapping(
            value = "/api/agents/{agentId}/quality/datasets/{datasetId}/export.jsonl",
            produces = "application/x-ndjson")
    public ResponseEntity<String> datasetExportJsonl(
            @PathVariable UUID agentId, @PathVariable UUID datasetId) {
        assertPremiumPlan();
        EvalDataset dataset = datasetService.requireDataset(agentId, datasetId);
        String body = EvalJsonlExport.dataset(dataset, datasetService.listCases(datasetId));
        return ResponseEntity.ok()
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"eval-dataset-" + datasetId + ".jsonl\"")
                .body(body);
    }

    @PostMapping("/api/agents/{agentId}/quality/datasets/seed-research")
    public ResponseEntity<SeedResponse> seedResearch(@PathVariable UUID agentId) {
        assertPremiumPlan();
        EvalDataset dataset = researchSeedService.seed(agentId);
        return ResponseEntity.ok(
                new SeedResponse(
                        dataset.getId(),
                        dataset.getName(),
                        datasetService.countCases(dataset.getId()),
                        dataset.getContentHash()));
    }

    @PostMapping("/api/agents/{agentId}/quality/judge-calibration")
    public ResponseEntity<JudgeCalibrationService.CalibrationResponse> calibrate(
            @PathVariable UUID agentId, @RequestBody CalibrationRequest request) {
        assertPremiumPlan();
        UUID setId = request != null ? request.calibrationSetId() : null;
        if (setId == null) {
            // Inline-labels mode, exactly as before: nothing stored.
            return ResponseEntity.ok(
                    calibrationService.calibrate(
                            agentId, request != null ? request.examples() : null));
        }
        if (request.examples() != null && !request.examples().isEmpty()) {
            throw new IllegalArgumentException(
                    "Send either inline 'examples' or a 'calibrationSetId', not both.");
        }
        return ResponseEntity.ok(calibrationSetService.calibrateSet(agentId, setId));
    }
}
