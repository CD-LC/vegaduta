package com.agenticai.core.quality.trajectory;

import com.agenticai.core.quality.trajectory.TrajectoryEvalDtos.ActualTrajectory;
import com.agenticai.core.quality.trajectory.TrajectoryEvalDtos.CaseTrajectory;
import com.agenticai.core.quality.trajectory.TrajectoryEvalDtos.Config;
import com.agenticai.core.quality.trajectory.TrajectoryEvalDtos.RunTrajectories;
import com.agenticai.core.quality.trajectory.TrajectoryEvalDtos.SetCaseRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trajectory evals - the expected tool-call trajectory of a dataset case and the expected-vs-actual view of a
 * run (see {@link TrajectoryEvalService}):
 *
 * <ul>
 *   <li>GET  .../trajectory-eval/config - whether the feature is on, the modes and the size caps</li>
 *   <li>GET  .../trajectory-eval/datasets/{datasetId}/cases/{caseId} - the case's expected trajectory</li>
 *   <li>PUT  .../trajectory-eval/datasets/{datasetId}/cases/{caseId} - set it (or clear it with null)</li>
 *   <li>GET  .../trajectory-eval/datasets/{datasetId}/runs/{runId} - the run's trajectory-graded cases, with diffs</li>
 *   <li>GET  .../trajectory-eval/datasets/{datasetId}/runs/{runId}/results/{resultId}/actual - what one case
 *       result actually called, plus a spec of its tool names to start an expectation from</li>
 * </ul>
 *
 * Thin on purpose: validation, the feature flag and status codes are decided in {@link TrajectoryEvalService}.
 * The same Premium plan gate as the rest of the eval surface (copied, as {@code EvalCredibilityController} does).
 */
@RestController
@RequestMapping("/api/agents/{agentId}/quality/trajectory-eval")
public class TrajectoryEvalController {

    private final TrajectoryEvalService service;
    private final TenantLimits tenantLimits;

    public TrajectoryEvalController(TrajectoryEvalService service, TenantLimits tenantLimits) {
        this.service = service;
        this.tenantLimits = tenantLimits;
    }

    private void assertPremiumPlan() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        if (plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                "Eval datasets and regression runs are a Premium feature - upgrade your plan to use them.");
        }
    }

    @GetMapping("/config")
    public Config config(@PathVariable UUID agentId) {
        assertPremiumPlan();
        return service.config();
    }

    @GetMapping("/datasets/{datasetId}/cases/{caseId}")
    public CaseTrajectory getCase(
        @PathVariable UUID agentId, @PathVariable UUID datasetId, @PathVariable UUID caseId
    ) {
        assertPremiumPlan();
        return service.getCase(agentId, datasetId, caseId);
    }

    @PutMapping("/datasets/{datasetId}/cases/{caseId}")
    public CaseTrajectory setCase(
        @PathVariable UUID agentId, @PathVariable UUID datasetId, @PathVariable UUID caseId,
        @RequestBody SetCaseRequest request
    ) {
        assertPremiumPlan();
        return service.setCase(agentId, datasetId, caseId, request != null ? request.trajectory() : null);
    }

    @GetMapping("/datasets/{datasetId}/runs/{runId}")
    public RunTrajectories runTrajectories(
        @PathVariable UUID agentId, @PathVariable UUID datasetId, @PathVariable UUID runId
    ) {
        assertPremiumPlan();
        return service.runTrajectories(agentId, datasetId, runId);
    }

    @GetMapping("/datasets/{datasetId}/runs/{runId}/results/{resultId}/actual")
    public ActualTrajectory actual(
        @PathVariable UUID agentId, @PathVariable UUID datasetId, @PathVariable UUID runId,
        @PathVariable UUID resultId
    ) {
        assertPremiumPlan();
        return service.actual(agentId, datasetId, runId, resultId);
    }
}
