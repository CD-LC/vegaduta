package com.agenticai.core.workflow.humaninput;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.workflow.WorkflowApprovalResumeException;
import com.agenticai.core.workflow.WorkflowRun;
import com.agenticai.core.workflow.WorkflowRunnerAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console surface for paused runs waiting on structured human input - the sibling of the HITL
 * approvals inbox, but the decision is "fill in a form", not "approve/reject". {@code GET
 * /api/workflows/input-requests} lists the queue (default PENDING), {@code POST .../{id}/submit}
 * resolves one and resumes the run synchronously (the legacy engine runs the rest of the traversal
 * on this request thread, so the response carries the run's resulting status).
 */
@RestController
@RequestMapping("/api/workflows/input-requests")
public class WorkflowInputRequestController {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final WorkflowInputRequestService service;
    private final WorkflowRunnerAccess runnerAccess;

    public WorkflowInputRequestController(
            WorkflowInputRequestService service, WorkflowRunnerAccess runnerAccess) {
        this.service = service;
        this.runnerAccess = runnerAccess;
    }

    public record InputRequestView(
            UUID id,
            UUID runId,
            UUID workflowId,
            String workflowName,
            String nodeId,
            String nodeLabel,
            JsonNode formSchema,
            String status,
            String requestedBy,
            Instant createdAt,
            JsonNode submittedValues,
            String submittedBy,
            Instant submittedAt) {}

    public record SubmitRequest(Map<String, Object> values) {}

    @GetMapping
    public ResponseEntity<List<InputRequestView>> list(
            @RequestParam(required = false) WorkflowInputRequestStatus status,
            @RequestParam(required = false) UUID runId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        // Default to the actionable queue - a bare GET means "what needs a human".
        WorkflowInputRequestStatus effective =
                status == null && runId == null ? WorkflowInputRequestStatus.PENDING : status;
        return ListPaging.window(
                service.list(effective, runId).stream()
                        .map(WorkflowInputRequestController::view)
                        .toList(),
                limit,
                offset,
                50,
                200);
    }

    @GetMapping("/{id}")
    public InputRequestView get(@PathVariable UUID id) {
        return view(service.get(id));
    }

    @PostMapping("/{id}/submit")
    public Map<String, Object> submit(
            @PathVariable UUID id,
            @RequestBody(required = false) SubmitRequest body,
            Authentication authentication) {
        // A submission resumes the run with the caller's values, so only someone who
        // may run this workflow may answer its forms (it was open to every member).
        runnerAccess.requireRunnerAccess(service.get(id).getWorkflowId());
        Map<String, Object> values =
                body != null && body.values() != null ? body.values() : Map.of();
        WorkflowRun run =
                service.submit(
                        id, values, authentication != null ? authentication.getName() : null);
        return Map.of(
                "runId", run.getId().toString(),
                "status", run.getStatus().name(),
                "error", run.getErrorMessage() == null ? "" : run.getErrorMessage());
    }

    private static InputRequestView view(WorkflowInputRequest request) {
        return new InputRequestView(
                request.getId(),
                request.getRunId(),
                request.getWorkflowId(),
                request.getWorkflowName(),
                request.getNodeId(),
                request.getNodeLabel(),
                readJson(request.getFormSchemaJson()),
                request.getStatus().name(),
                request.getRequestedBy(),
                request.getCreatedAt(),
                readJson(request.getSubmittedValuesJson()),
                request.getSubmittedBy(),
                request.getSubmittedAt());
    }

    private static JsonNode readJson(String json) {
        try {
            return json == null ? null : MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    // --- Controller-local exception mapping (same pattern as GuardrailRuleController) ---

    @ExceptionHandler(WorkflowInputRequestNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            WorkflowInputRequestNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(WorkflowInputRequestStateException.class)
    public ResponseEntity<Map<String, Object>> handleState(WorkflowInputRequestStateException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(WorkflowApprovalResumeException.class)
    public ResponseEntity<Map<String, Object>> handleResume(WorkflowApprovalResumeException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("status", status.value(), "error", message));
    }
}
