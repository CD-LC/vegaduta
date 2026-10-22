package com.agenticai.core.workflow;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The kill-switch fallbacks: the placeholder algorithms fields used before expressions existed
 * ({@code app.workflow.expressions.enabled=false}). Extracted unchanged from {@link
 * WorkflowExpressionResolver}.
 */
final class WorkflowLegacyTemplates {

    static final Pattern CRM_PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private final ObjectMapper objectMapper;

    WorkflowLegacyTemplates(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Map<String, String> legacyValues(String input, boolean withInputJson) {
        Map<String, String> values = new HashMap<>();
        String raw = input == null ? "" : input;
        values.put("input", raw);
        if (withInputJson) {
            try {
                values.put("input_json", objectMapper.writeValueAsString(raw));
            } catch (Exception ignored) {
                values.put("input_json", "\"\"");
            }
        }
        try {
            JsonNode root = objectMapper.readTree(raw.isBlank() ? "{}" : raw);
            if (root.isObject()) {
                root.properties()
                        .forEach(
                                e ->
                                        values.put(
                                                e.getKey(),
                                                e.getValue().isString()
                                                        ? e.getValue().asString()
                                                        : e.getValue().toString()));
            }
        } catch (Exception ignored) {
            // Not JSON: only "input" resolves.
        }
        return values;
    }

    String legacyCrm(String template, String input) {
        JsonNode json = null;
        if (input != null && input.trim().startsWith("{")) {
            try {
                json = objectMapper.readTree(input);
            } catch (Exception ignored) {
                json = null;
            }
        }
        Matcher m = CRM_PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement;
            if ("input".equals(key)) {
                replacement = input == null ? "" : input;
            } else if (json != null && json.has(key) && !json.get(key).isNull()) {
                JsonNode v = json.get(key);
                replacement = v.isValueNode() ? v.asString() : v.toString();
            } else {
                replacement = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
