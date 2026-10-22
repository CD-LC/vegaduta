package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowNodeSupport.safeLength;
import static com.agenticai.core.workflow.WorkflowNodeSupport.stringConfig;
import static com.agenticai.core.workflow.WorkflowNodeSupport.truncate;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** "code" (Transform): a fixed library of built-in, safe string operations. */
@Component
class CodeNodeHandler implements NodeHandler {

    private final ObjectMapper objectMapper;

    CodeNodeHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("code");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return executeCodeNode(inv.node(), inv.input(), inv.unresolvedNode());
    }

    /**
     * Transform / Code node - a fixed library of built-in, safe operations, NOT arbitrary JS
     * execution (see {@link WorkflowNodeType}'s javadoc for why real sandboxed code execution was
     * explicitly ruled out for this feature). Every operation is a small, pure string transform of
     * the node's input; unknown/misconfigured operations fail this node alone (SKIPPED, input
     * passed through unchanged) rather than the whole run.
     */
    private NodeExecutionResult executeCodeNode(
            WorkflowNodeDto node, String input, WorkflowNodeDto unresolvedNode) {
        String operation = stringConfig(node, "operation", null);
        if (operation == null) {
            return NodeExecutionResult.skipped(
                    input, "No operation selected for this Transform node.");
        }
        List<String> logLines = new ArrayList<>();
        try {
            String output =
                    switch (operation) {
                        case "extract" -> extractJsonField(node, input);
                        case "regex_replace" -> regexReplace(node, input);
                        case "template" -> renderTemplate(node, input, unresolvedNode);
                        case "uppercase" ->
                                input == null ? "" : input.toUpperCase(java.util.Locale.ROOT);
                        case "lowercase" ->
                                input == null ? "" : input.toLowerCase(java.util.Locale.ROOT);
                        case "json_parse" -> jsonPrettyPrint(input);
                        case "json_stringify" ->
                                objectMapper.writeValueAsString(input == null ? "" : input);
                        default ->
                                throw new IllegalArgumentException(
                                        "Unrecognized operation '" + operation + "'");
                    };
            logLines.add(
                    "Applied '"
                            + operation
                            + "' transform ("
                            + safeLength(input)
                            + " -> "
                            + safeLength(output)
                            + " chars).");
            return NodeExecutionResult.ok(output, truncate(output, 300), null, logLines);
        } catch (Exception e) {
            logLines.add("Transform '" + operation + "' failed: " + e.getMessage());
            return NodeExecutionResult.skipped(
                    input, "Transform failed: " + e.getMessage(), logLines);
        }
    }

    /**
     * Dot-path field extract, e.g. "user.name" - blank field returns the whole (re-serialized)
     * document.
     */
    private String extractJsonField(WorkflowNodeDto node, String input)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        String field = stringConfig(node, "field", "");
        JsonNode root = objectMapper.readTree(input == null || input.isBlank() ? "{}" : input);
        JsonNode current = root;
        for (String part : field.split("\\.")) {
            if (part.isBlank()) {
                continue;
            }
            current = current.path(part);
        }
        return current.isMissingNode()
                ? ""
                : (current.isTextual() ? current.asText() : current.toString());
    }

    private String regexReplace(WorkflowNodeDto node, String input) {
        String pattern = stringConfig(node, "pattern", "");
        String replacement = stringConfig(node, "replacement", "");
        if (pattern.isBlank()) {
            return input;
        }
        return (input == null ? "" : input).replaceAll(pattern, replacement);
    }

    /**
     * The "template" operation's text arrives already resolved ({@link ExpressionFields}:
     * code.template - "{{input}}", "{{field}}" and full expressions); no template at all means
     * "emit the input", which is what the old default template {@code {{input}}} produced.
     */
    private String renderTemplate(
            WorkflowNodeDto node, String input, WorkflowNodeDto unresolvedNode) {
        // Decided on the STORED config: a template that merely renders to empty text is still a
        // template.
        if (stringConfig(unresolvedNode, "template", null) == null) {
            return input == null ? "" : input;
        }
        Object resolved = node.config().get("template");
        return resolved == null ? "" : resolved.toString();
    }

    private String jsonPrettyPrint(String input)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = objectMapper.readTree(input == null || input.isBlank() ? "null" : input);
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }
}
