package com.agenticai.core.workflow.dto;

import java.util.List;
import java.util.Map;

/**
 * Wire shapes of the workflow expression endpoints (spec 6). The JSON field names are a binding contract with
 * the web console ({@code web/app/lib/api/workflowExpressions.ts}); a test pins every one of them, so renaming
 * a component here is a breaking change, not a refactor.
 */
public final class WorkflowExpressionApi {

    private WorkflowExpressionApi() {
    }

    /**
     * {@code POST /api/workflows/{id}/expressions/evaluate}. {@code template} is text with {@code {{ }}}
     * segments exactly as stored in a node config field; {@code dataSource} is {@code pinned}, {@code lastRun}
     * or {@code supplied}.
     */
    public record EvaluateRequest(String template, String nodeId, String dataSource, Supplied supplied) {
    }

    /** {@code input} is the current node's input; {@code nodes} maps an upstream node LABEL to its output text. */
    public record Supplied(String input, Map<String, String> nodes) {
    }

    /** {@code type} is one of string|number|boolean|object|array|null; {@code error} is null when {@code ok}. */
    public record EvaluateResponse(
        boolean ok,
        Object value,
        String type,
        String rendered,
        EvaluateError error,
        List<String> sources,
        List<String> warnings
    ) {
    }

    /** {@code start}/{@code end} are character offsets into the submitted template. */
    public record EvaluateError(String message, int start, int end) {
    }

    /** {@code POST /api/workflows/{id}/expressions/lint}. */
    public record LintRequest(List<LintItem> items) {
    }

    public record LintItem(String nodeId, String field, String template) {
    }

    public record LintResponse(List<LintDiagnostic> diagnostics) {
    }

    /** {@code severity} is {@code error} or {@code warning}; offsets are into the item's template. */
    public record LintDiagnostic(
        String nodeId, String field, String severity, String code, String message, int start, int end
    ) {
    }

    /** {@code GET /api/workflows/{id}/nodes/{nodeId}/upstream-schema}. */
    public record UpstreamSchemaResponse(List<SchemaNode> nodes) {
    }

    /** {@code source} is {@code pinned}, {@code lastRun} or {@code none} (then {@code paths} is empty). */
    public record SchemaNode(String nodeId, String label, String source, List<SchemaPath> paths) {
    }

    /** {@code sample} is a short text preview of a scalar value, null for objects, arrays and null. */
    public record SchemaPath(String path, String type, String sample) {
    }

    /** {@code GET /api/workflow-expression-functions}: one built-in function, from the same registry the evaluator uses. */
    public record FunctionDoc(String name, String category, String signature, String doc, String example) {
    }
}
