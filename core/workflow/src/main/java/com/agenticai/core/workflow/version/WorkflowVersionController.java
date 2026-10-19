package com.agenticai.core.workflow.version;

import com.agenticai.core.agents.version.VersionRollbackException;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowNotFoundException;
import com.agenticai.core.workflow.WorkflowRun;
import com.agenticai.core.workflow.WorkflowRunNotFoundException;
import com.agenticai.core.workflow.WorkflowRunRepository;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workflow version history + diffs (Tier-3). Every endpoint sits under
 * the same {@code /api/workflows} umbrella {@code WorkflowController} owns,
 * and inherits the exact same SecurityConfig matchers - nothing about
 * "who may see/do this" is decided here.
 *
 * <p>Access is funnelled through {@code WorkflowService}'s own methods
 * ({@code requireAccess} VIEWER for reads, EDITOR for restore) rather than
 * re-implementing sharing/legacy-open semantics - the same "the service
 * decides, the controller just routes" convention the rest of this module
 * follows.
 */
@RestController
@RequestMapping("/api/workflows/{workflowId}")
public class WorkflowVersionController {

    private final WorkflowService workflowService;
    private final WorkflowVersionService versionService;
    private final WorkflowRunRepository runRepository;

    public WorkflowVersionController(
        WorkflowService workflowService,
        WorkflowVersionService versionService,
        WorkflowRunRepository runRepository
    ) {
        this.workflowService = workflowService;
        this.versionService = versionService;
        this.runRepository = runRepository;
    }

    @GetMapping("/versions")
    public ResponseEntity<List<WorkflowVersionResponse>> list(@PathVariable UUID workflowId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        // VIEWER-level access check via the service's own funnel - a
        // private workflow's history is as private as the workflow.
        workflowService.requireViewerAccess(workflowId);
        return ListPaging.window(versionService.list(workflowId).stream().map(WorkflowVersionResponse::from).toList(), limit, offset, 50, 200);
    }

    @GetMapping("/versions/{versionNumber}")
    public Map<String, Object> get(@PathVariable UUID workflowId, @PathVariable int versionNumber) {
        workflowService.requireViewerAccess(workflowId);
        WorkflowVersion version = versionService.get(workflowId, versionNumber);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("version", WorkflowVersionResponse.from(version));
        body.put("graphJson", version.getGraphJson());
        return body;
    }

    @PostMapping("/versions/{versionNumber}/restore")
    public ResponseEntity<?> restore(@PathVariable UUID workflowId, @PathVariable int versionNumber) {
        // EDITOR-level via WorkflowService#restoreVersion - restoring
        // overwrites the live definition's graph, exactly what an update
        // does, so it asks for the same permission, not a weaker one.
        return ResponseEntity.ok(workflowService.restoreVersion(workflowId, versionNumber));
    }

    /**
     * C1 publish - snapshot the current draft with a change note. Deduped
     * server-side: an unchanged bundle answers the existing newest row.
     * EDITOR, same bar as an update - a publish decides what can be
     * promoted.
     */
    @PostMapping("/versions")
    public Map<String, Object> publish(
        @PathVariable UUID workflowId,
        @RequestBody(required = false) Map<String, String> body
    ) {
        WorkflowDefinition definition = workflowService.requireEditorAccess(workflowId);
        WorkflowVersion before = versionService.list(workflowId).stream().findFirst().orElse(null);
        WorkflowVersion version = versionService.publish(definition, body == null ? null : body.get("changeNote"));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("version", WorkflowVersionResponse.from(version));
        result.put("createdNew", before == null || !before.getId().equals(version.getId()));
        return result;
    }

    /**
     * C1 promote - {@code {"label":"PRODUCTION"}} (or {@code STAGING}) at
     * this version. Runtime resolution follows the label on the next run;
     * the graph itself is untouched.
     */
    @PostMapping("/versions/{versionNumber}/promote")
    public WorkflowVersionResponse promote(
        @PathVariable UUID workflowId,
        @PathVariable int versionNumber,
        @RequestBody(required = false) Map<String, String> body
    ) {
        workflowService.requireEditorAccess(workflowId);
        String label = body == null ? null : body.get("label");
        if (label == null) {
            throw new IllegalArgumentException("'label' is required (STAGING or PRODUCTION).");
        }
        return WorkflowVersionResponse.from(
            versionService.promote(workflowId, versionNumber,
                com.agenticai.core.agents.version.VersionLabel.valueOf(label)));
    }

    /**
     * C1 rollback - PRODUCTION back to the previous version; a label move,
     * never a new snapshot. 409 when there is nothing earlier to point at.
     */
    @PostMapping("/versions/rollback")
    public WorkflowVersionResponse rollback(@PathVariable UUID workflowId) {
        workflowService.requireEditorAccess(workflowId);
        return WorkflowVersionResponse.from(versionService.rollback(workflowId));
    }

    @GetMapping("/versions/diff")
    public Map<String, Object> diffVersions(
        @PathVariable UUID workflowId, @RequestParam int from, @RequestParam int to
    ) {
        workflowService.requireViewerAccess(workflowId);
        return versionService.diffVersions(
            versionService.get(workflowId, from), versionService.get(workflowId, to));
    }

    /**
     * Run-to-run diff - the audit's missing surface. Both run ids must
     * belong to {@code workflowId} (a cross-workflow compare is rejected
     * rather than silently comparing apples/oranges).
     */
    @GetMapping("/runs/{runId}/diff/{otherRunId}")
    public Map<String, Object> diffRuns(
        @PathVariable UUID workflowId, @PathVariable UUID runId, @PathVariable UUID otherRunId
    ) {
        workflowService.requireViewerAccess(workflowId);
        WorkflowRun a = runRepository.findById(runId)
            .filter(run -> workflowId.equals(run.getWorkflowId()))
            .orElseThrow(() -> new WorkflowRunNotFoundException(runId));
        WorkflowRun b = runRepository.findById(otherRunId)
            .filter(run -> workflowId.equals(run.getWorkflowId()))
            .orElseThrow(() -> new WorkflowRunNotFoundException(otherRunId));
        WorkflowVersion versionA = resolveVersion(workflowId, a.getWorkflowVersion());
        WorkflowVersion versionB = resolveVersion(workflowId, b.getWorkflowVersion());
        return versionService.diffRuns(a, b, versionA, versionB);
    }

    /** Stamped version -> its snapshot row, or null (unstamped run, or a snapshot deleted with an older workflow id). */
    private WorkflowVersion resolveVersion(UUID workflowId, Integer versionNumber) {
        if (versionNumber == null) {
            return null;
        }
        return versionService.getOptional(workflowId, versionNumber).orElse(null);
    }

    @ExceptionHandler(WorkflowVersionNotFoundException.class)
    public ResponseEntity<Map<String, String>> versionNotFound(WorkflowVersionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    /** 409 - rollback requested but no earlier version exists. */
    @ExceptionHandler(VersionRollbackException.class)
    public ResponseEntity<Map<String, String>> rollbackConflict(VersionRollbackException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<Map<String, String>> workflowNotFound(WorkflowNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(WorkflowRunNotFoundException.class)
    public ResponseEntity<Map<String, String>> runNotFound(WorkflowRunNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
