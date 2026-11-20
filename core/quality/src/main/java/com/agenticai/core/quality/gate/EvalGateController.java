package com.agenticai.core.quality.gate;

import com.agenticai.core.quality.dataset.AgentHeadVersionLookup;
import com.agenticai.core.quality.gate.EvalGateDtos.GateEvaluationResponse;
import com.agenticai.core.quality.gate.EvalGateDtos.GateResponse;
import com.agenticai.core.quality.gate.EvalGateDtos.GateStatusResponse;
import com.agenticai.core.quality.gate.EvalGateDtos.UpsertGateRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the per-agent eval gate, nested under the quality URL family beside the datasets
 * it references:
 *
 * <ul>
 *   <li>GET /api/agents/{agentId}/quality/gate - config + evaluation of the live config
 *   <li>PUT /api/agents/{agentId}/quality/gate - create/update the gate
 *   <li>DELETE /api/agents/{agentId}/quality/gate - remove the gate
 *   <li>GET /api/agents/{agentId}/quality/gate/check?versionId= - dry-run report for one version
 * </ul>
 *
 * <p>Plan gating differs deliberately from {@code EvalDatasetController}'s blanket premium rule:
 * ENABLING or re-pointing a gate requires PREMIUM (the gate is meaningless without the
 * premium-gated runs it consumes), but a tenant may always READ the gate, DISABLE it, or DELETE it
 * on any plan - a downgraded tenant must never be locked out of promoting by a gate they can no
 * longer feed runs to. See {@code AgentEvalGate}'s javadoc.
 *
 * <p>No {@code SecurityConfig} change: {@code /api/**} falls through to authenticated (same
 * reasoning as {@code EvalDatasetController}).
 */
@RestController
@RequestMapping("/api/agents/{agentId}/quality/gate")
public class EvalGateController {

    private final EvalGateService gateService;
    private final AgentHeadVersionLookup headVersionLookup;
    private final TenantLimits tenantLimits;

    public EvalGateController(
            EvalGateService gateService,
            AgentHeadVersionLookup headVersionLookup,
            TenantLimits tenantLimits) {
        this.gateService = gateService;
        this.headVersionLookup = headVersionLookup;
        this.tenantLimits = tenantLimits;
    }

    private boolean isFree() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        return plan == TenantPlan.FREE;
    }

    private void assertPremiumPlan() {
        if (isFree()) {
            throw new TenantLimitExceededException(
                    "Eval gates consume premium eval runs - upgrade your plan to configure one.");
        }
    }

    /** GET / - the configured gate plus a live evaluation of the head version. */
    @GetMapping
    public ResponseEntity<GateStatusResponse> getGate(@PathVariable UUID agentId) {
        GateResponse gate = gateService.findGateResponse(agentId).orElse(null);
        UUID headVersionId = headVersionLookup.findHeadVersionId(agentId).orElse(null);
        GateEvaluationResponse headEvaluation =
                headVersionId != null
                        ? GateEvaluationResponse.of(gateService.evaluate(agentId, headVersionId))
                        : null;
        return ResponseEntity.ok(new GateStatusResponse(gate, headVersionId, headEvaluation));
    }

    /**
     * PUT / - create or replace the gate. Disabling an existing gate is allowed on any plan (see
     * class javadoc); everything else is premium.
     */
    @PutMapping
    public ResponseEntity<GateResponse> upsertGate(
            @PathVariable UUID agentId, @RequestBody UpsertGateRequest request) {
        boolean disabling =
                request.enabled() != null
                        && !request.enabled()
                        && gateService.findGate(agentId).isPresent();
        if (!disabling) {
            assertPremiumPlan();
        }
        if (request.datasetId() == null) {
            throw new IllegalArgumentException(
                    "datasetId is required - the gate needs a dataset to consult.");
        }
        GateResponse gate =
                gateService.upsertGateResponse(
                        agentId,
                        request.datasetId(),
                        request.enabled() == null || request.enabled(),
                        request.maxRunAgeHours(),
                        request.requireSimulation() != null && request.requireSimulation(),
                        request.maxSimulationAgeHours());
        return ResponseEntity.ok(gate);
    }

    /** DELETE / - remove the gate. Any plan: deleting a gate is the escape hatch. */
    @DeleteMapping
    public ResponseEntity<Void> deleteGate(@PathVariable UUID agentId) {
        gateService.deleteGate(agentId);
        return ResponseEntity.noContent().build();
    }

    /** GET /check?versionId= - the gate report for one specific version. */
    @GetMapping("/check")
    public ResponseEntity<GateEvaluationResponse> check(
            @PathVariable UUID agentId, @RequestParam UUID versionId) {
        return ResponseEntity.ok(
                GateEvaluationResponse.of(gateService.evaluate(agentId, versionId)));
    }
}
