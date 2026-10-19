package com.agenticai.core.workflow;

import com.agenticai.core.workflow.expression.ExpressionException;

/**
 * A node's expression failed at run time (spec 3.6). Unchecked on purpose: it fails the node exactly like
 * any other node failure, so the engines' retry / error-edge / skip handling applies unchanged. The
 * message is what lands in the trace summary and the run's error, so it names the expression, the config
 * field and the position.
 */
public class WorkflowExpressionException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final int MAX_SNIPPET = 120;

    public WorkflowExpressionException(String message) {
        super(message);
    }

    /**
     * {@code Expression {{ items[0].price }} in field "url": <reason> (at 14)}. {@code template} is the field
     * text the positions in {@code cause} refer to.
     */
    static WorkflowExpressionException of(String template, String field, ExpressionException cause) {
        StringBuilder sb = new StringBuilder("Expression ");
        String snippet = snippet(template, cause.start(), cause.end());
        if (snippet != null) {
            sb.append("{{ ").append(snippet).append(" }} ");
        }
        sb.append("in field \"").append(field).append("\": ").append(NodeTracePayloads.redact(cause.getMessage()));
        if (cause.start() >= 0) {
            sb.append(" (at ").append(cause.start()).append(')');
        }
        return new WorkflowExpressionException(sb.toString());
    }

    /** The {@code {{ ... }}} span that contains the failure, else the failing range itself, else null. */
    private static String snippet(String template, int start, int end) {
        if (template == null || template.isEmpty()) {
            return null;
        }
        String found = null;
        if (start >= 0 && start <= template.length()) {
            int open = template.lastIndexOf("{{", Math.min(start, template.length() - 1));
            int close = open < 0 ? -1 : template.indexOf("}}", Math.max(open + 2, Math.min(start, template.length())));
            if (open >= 0 && close > open) {
                found = template.substring(open + 2, close);
            } else if (end > start && end <= template.length()) {
                found = template.substring(start, end);
            }
        } else if (!template.contains("{{")) {
            found = template;
        }
        if (found == null) {
            return null;
        }
        found = found.strip();
        return found.length() <= MAX_SNIPPET ? found : found.substring(0, MAX_SNIPPET) + "...";
    }
}
