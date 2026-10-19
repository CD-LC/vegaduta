package com.agenticai.core.workflow;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/workflows/{workflowId}/runs/{runId}/wait} - what a parked run is waiting for. This is where a
 * workflow EDITOR reads the signed resume URL of a webhook-mode Wait node (it is a bearer credential, so it is
 * not put in the run trace, which VIEWERs can read). Thin: access and 404s are decided by {@link WorkflowService}.
 */
@RestController
@RequestMapping("/api/workflows/{workflowId}/runs/{runId}/wait")
public class WorkflowWaitController {

    private final WorkflowService workflowService;
    private final WorkflowWaitService waitService;

    public WorkflowWaitController(WorkflowService workflowService, WorkflowWaitService waitService) {
        this.workflowService = workflowService;
        this.waitService = waitService;
    }

    @GetMapping
    public ResponseEntity<WorkflowWaitService.WaitView> get(@PathVariable UUID workflowId, @PathVariable UUID runId) {
        workflowService.requireEditorAccess(workflowId);
        // Binds the run to the path's workflowId (404 for a run of another workflow) - the F3 rule.
        workflowService.getRunEntity(workflowId, runId);
        return waitService.describe(runId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
