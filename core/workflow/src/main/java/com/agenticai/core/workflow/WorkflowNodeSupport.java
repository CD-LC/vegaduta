package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;

/** Pure config/string helpers shared by {@link WorkflowNodeExecutor} and its extracted node handlers. */
final class WorkflowNodeSupport {

    private WorkflowNodeSupport() {
    }

    static String stringConfig(WorkflowNodeDto node, String key, String fallback) {
        Object raw = node.config().get(key);
        return raw == null || raw.toString().isBlank() ? fallback : raw.toString();
    }

    static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    /**
     * Literal {@code {{key}}} substitution shared by the Transform node's
     * "template" operation and the bulk_email node's per-recipient
     * personalization. Unknown placeholders are left untouched.
     */
    static String applyTemplate(String template, java.util.Map<String, String> values) {
        String result = template == null ? "" : template;
        for (java.util.Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
