package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowPinRequest;
import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.workflow.dto.WorkflowPinResponse;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Data pinning endpoints (spec 5, 6): {@code GET /api/workflows/{id}/pins}, {@code PUT} and {@code
 * DELETE /api/workflows/{id}/nodes/{nodeId}/pin}. Thin pass-throughs: the permission ladder, limits
 * and audit trail live in {@link WorkflowPinService} and {@link WorkflowPinStore}. Same tenant auth
 * as every workflow endpoint (authenticated tenant member, then narrowed per workflow by {@code
 * WorkflowService#requireAccess}).
 */
@RestController
public class WorkflowPinController {

    private final WorkflowPinService pinService;

    public WorkflowPinController(WorkflowPinService pinService) {
        this.pinService = pinService;
    }

    /** RUNNER and above; {@code data} is null unless the caller is an EDITOR. */
    @GetMapping("/api/workflows/{workflowId}/pins")
    public ResponseEntity<List<WorkflowPinResponse>> list(@PathVariable UUID workflowId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(pinService.list(workflowId), limit, offset, 50, 200);
    }

    /**
     * EDITOR. 400 with a message when the data is empty or over a limit (256000 chars, 30 pins,
     * 2000000 total).
     */
    @PutMapping("/api/workflows/{workflowId}/nodes/{nodeId}/pin")
    public WorkflowPinResponse put(
            @PathVariable UUID workflowId,
            @PathVariable String nodeId,
            @RequestBody(required = false) WorkflowPinRequest request,
            Principal principal) {
        return pinService.put(
                workflowId, nodeId, request, principal != null ? principal.getName() : null);
    }

    /** EDITOR. Idempotent. */
    @DeleteMapping("/api/workflows/{workflowId}/nodes/{nodeId}/pin")
    public ResponseEntity<Void> delete(
            @PathVariable UUID workflowId, @PathVariable String nodeId, Principal principal) {
        pinService.delete(workflowId, nodeId, principal != null ? principal.getName() : null);
        return ResponseEntity.noContent().build();
    }
}
