package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowExpressionApi.EvaluateRequest;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.EvaluateResponse;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.FunctionDoc;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.LintRequest;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.LintResponse;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.UpstreamSchemaResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expression tooling for the canvas editor (spec 6). Read-only: nothing here runs a node or changes a
 * workflow. Permissions, rate limiting and the kill switch are in {@link WorkflowExpressionService}.
 *
 * <p>The function registry is deliberately NOT under {@code /api/workflows/}: {@code GET /api/workflows/{id}}
 * would match a path like {@code /api/workflows/expression-functions} and answer 400 (not a UUID).
 */
@RestController
@RequestMapping("/api")
public class WorkflowExpressionController {

    private final WorkflowExpressionService expressionService;

    public WorkflowExpressionController(WorkflowExpressionService expressionService) {
        this.expressionService = expressionService;
    }

    /** EDITOR. Always 200 for an expression that fails to evaluate ({@code ok:false} with a positioned error). */
    @PostMapping("/workflows/{workflowId}/expressions/evaluate")
    public EvaluateResponse evaluate(@PathVariable UUID workflowId, @RequestBody EvaluateRequest request) {
        return expressionService.evaluate(workflowId, request);
    }

    /** VIEWER. Checks the submitted templates against the SAVED graph. */
    @PostMapping("/workflows/{workflowId}/expressions/lint")
    public LintResponse lint(@PathVariable UUID workflowId, @RequestBody LintRequest request) {
        return expressionService.lint(workflowId, request);
    }

    /** EDITOR. Paths and types of every upstream node's output, from its pin, else its last run, else none. */
    @GetMapping("/workflows/{workflowId}/nodes/{nodeId}/upstream-schema")
    public UpstreamSchemaResponse upstreamSchema(@PathVariable UUID workflowId, @PathVariable String nodeId) {
        return expressionService.upstreamSchema(workflowId, nodeId);
    }

    /** Any authenticated user: static documentation of the built-in functions. */
    @GetMapping("/workflow-expression-functions")
    public List<FunctionDoc> functions() {
        return expressionService.functions();
    }
}
