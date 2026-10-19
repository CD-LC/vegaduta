package com.agenticai.core.quality.calibration;

import com.agenticai.core.quality.calibration.JudgeCalibrationSetService.ItemInput;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stored judge-calibration sets and their drift history, next to {@code
 * EvalCredibilityController}'s calibration run endpoint and behind the same switch ({@code
 * app.quality.eval.calibration.enabled}, 404 when off) and the same Premium plan gate:
 *
 * <ul>
 *   <li>GET /api/agents/{agentId}/quality/judge-calibration-sets
 *   <li>POST /api/agents/{agentId}/quality/judge-calibration-sets - name + rows
 *   <li>GET /api/agents/{agentId}/quality/judge-calibration-sets/{setId} - set + items
 *   <li>PUT /api/agents/{agentId}/quality/judge-calibration-sets/{setId} - rename
 *   <li>DELETE /api/agents/{agentId}/quality/judge-calibration-sets/{setId}
 *   <li>POST /api/agents/{agentId}/quality/judge-calibration-sets/{setId}/items - add rows
 *   <li>DELETE /api/agents/{agentId}/quality/judge-calibration-sets/{setId}/items/{itemId}
 *   <li>GET /api/agents/{agentId}/quality/judge-calibration-history?setId=&amp;limit= - results,
 *       newest first
 * </ul>
 *
 * Running a set is {@code POST .../judge-calibration} with {@code calibrationSetId}.
 */
@RestController
public class JudgeCalibrationSetController {

    private final JudgeCalibrationSetService service;
    private final TenantLimits tenantLimits;

    public JudgeCalibrationSetController(
            JudgeCalibrationSetService service, TenantLimits tenantLimits) {
        this.service = service;
        this.tenantLimits = tenantLimits;
    }

    /**
     * Copied (not shared) from the sibling controllers - the same Premium gate as the calibration
     * run.
     */
    private void assertPremiumPlan() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        if (plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                    "Eval datasets and regression runs are a Premium feature - upgrade your plan to"
                            + " use them.");
        }
    }

    public record CreateSetRequest(String name, List<ItemInput> items) {}

    public record RenameSetRequest(String name) {}

    public record AddItemsRequest(List<ItemInput> items) {}

    public record SetResponse(
            UUID id,
            UUID agentId,
            String name,
            long itemCount,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {}

    public record ItemResponse(
            UUID id,
            int position,
            String input,
            String outputA,
            String outputB,
            String humanLabel,
            String rationale,
            Instant createdAt) {}

    public record SetDetailResponse(SetResponse set, List<ItemResponse> items) {}

    public record ResultResponse(
            UUID id,
            UUID setId,
            String judgeModel,
            String agentModel,
            int n,
            int usable,
            int consistent,
            Double kappa,
            Double orderSwapConsistency,
            Double rawAgreement,
            String interpretation,
            String selfPreferenceWarning,
            Instant createdAt) {}

    @GetMapping("/api/agents/{agentId}/quality/judge-calibration-sets")
    public ResponseEntity<List<SetResponse>> list(@PathVariable UUID agentId) {
        assertPremiumPlan();
        return ResponseEntity.ok(service.listSets(agentId).stream().map(this::toSet).toList());
    }

    @PostMapping("/api/agents/{agentId}/quality/judge-calibration-sets")
    public ResponseEntity<SetDetailResponse> create(
            @PathVariable UUID agentId, @RequestBody CreateSetRequest request) {
        assertPremiumPlan();
        JudgeCalibrationSet set =
                service.createSet(
                        agentId,
                        request != null ? request.name() : null,
                        request != null ? request.items() : null);
        return ResponseEntity.status(HttpStatus.CREATED).body(detail(agentId, set));
    }

    @GetMapping("/api/agents/{agentId}/quality/judge-calibration-sets/{setId}")
    public ResponseEntity<SetDetailResponse> get(
            @PathVariable UUID agentId, @PathVariable UUID setId) {
        assertPremiumPlan();
        return ResponseEntity.ok(detail(agentId, service.requireSet(agentId, setId)));
    }

    @PutMapping("/api/agents/{agentId}/quality/judge-calibration-sets/{setId}")
    public ResponseEntity<SetResponse> rename(
            @PathVariable UUID agentId,
            @PathVariable UUID setId,
            @RequestBody RenameSetRequest request) {
        assertPremiumPlan();
        return ResponseEntity.ok(
                toSet(service.renameSet(agentId, setId, request != null ? request.name() : null)));
    }

    @DeleteMapping("/api/agents/{agentId}/quality/judge-calibration-sets/{setId}")
    public ResponseEntity<Void> delete(@PathVariable UUID agentId, @PathVariable UUID setId) {
        assertPremiumPlan();
        service.deleteSet(agentId, setId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/agents/{agentId}/quality/judge-calibration-sets/{setId}/items")
    public ResponseEntity<SetDetailResponse> addItems(
            @PathVariable UUID agentId,
            @PathVariable UUID setId,
            @RequestBody AddItemsRequest request) {
        assertPremiumPlan();
        service.addItems(agentId, setId, request != null ? request.items() : null);
        return ResponseEntity.ok(detail(agentId, service.requireSet(agentId, setId)));
    }

    @DeleteMapping("/api/agents/{agentId}/quality/judge-calibration-sets/{setId}/items/{itemId}")
    public ResponseEntity<Void> deleteItem(
            @PathVariable UUID agentId, @PathVariable UUID setId, @PathVariable UUID itemId) {
        assertPremiumPlan();
        service.deleteItem(agentId, setId, itemId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/agents/{agentId}/quality/judge-calibration-history")
    public ResponseEntity<List<ResultResponse>> history(
            @PathVariable UUID agentId,
            @RequestParam(required = false) UUID setId,
            @RequestParam(required = false) Integer limit) {
        assertPremiumPlan();
        return ResponseEntity.ok(
                service.history(agentId, setId, limit).stream()
                        .map(JudgeCalibrationSetController::toResult)
                        .toList());
    }

    private SetDetailResponse detail(UUID agentId, JudgeCalibrationSet set) {
        List<ItemResponse> items =
                service.listItems(agentId, set.getId()).stream()
                        .map(JudgeCalibrationSetController::toItem)
                        .toList();
        return new SetDetailResponse(toSet(set), items);
    }

    private SetResponse toSet(JudgeCalibrationSet set) {
        return new SetResponse(
                set.getId(),
                set.getAgentId(),
                set.getName(),
                service.countItems(set.getId()),
                set.getCreatedBy(),
                set.getCreatedAt(),
                set.getUpdatedAt());
    }

    private static ItemResponse toItem(JudgeCalibrationItem i) {
        return new ItemResponse(
                i.getId(),
                i.getPosition(),
                i.getInput(),
                i.getOutputA(),
                i.getOutputB(),
                i.getHumanLabel(),
                i.getRationale(),
                i.getCreatedAt());
    }

    private static ResultResponse toResult(JudgeCalibrationResult r) {
        return new ResultResponse(
                r.getId(),
                r.getSetId(),
                r.getJudgeModel(),
                r.getAgentModel(),
                r.getN(),
                r.getUsable(),
                r.getConsistent(),
                r.getKappa(),
                r.getOrderSwapConsistency(),
                r.getRawAgreement(),
                r.getInterpretation(),
                r.getSelfPreferenceWarning(),
                r.getCreatedAt());
    }
}
