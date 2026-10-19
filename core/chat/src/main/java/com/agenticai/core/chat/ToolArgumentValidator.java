package com.agenticai.core.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a tool call's arguments against the tool's own input schema <em>before</em> the tool
 * runs, so a model that filled in the wrong fields gets told exactly what the tool needs instead of
 * a tool failure (or, worse, a tool that quietly does the wrong thing with defaults).
 *
 * <p>Deliberately minimal, so that it only ever rejects what the tool's author declared impossible
 * and never changes the behaviour of a call a tool would have accepted:
 *
 * <ul>
 *   <li>the arguments must parse as a JSON object;
 *   <li>every property listed in the schema's {@code required} must be present and non-null.
 * </ul>
 *
 * Property <em>types</em> are not checked: many tools coerce ({@code "5"} for an integer) and
 * rejecting those would change today's behaviour. Keys the schema does not declare are reported
 * as a note in the result, never as a rejection, for the same reason. The incident this answers
 * (2026-10-15, a free-pool model calling {@code generate_document} with {@code {"f", "path",
 * "skill"}} - the parameters of a different tool) is caught by the {@code required} check alone.
 */
final class ToolArgumentValidator {

    /** Schema text longer than this is not echoed back to the model (prompt-budget guard). */
    static final int MAX_SCHEMA_ECHO_CHARS = 4000;

    private ToolArgumentValidator() {}

    /** The outcome of one check: {@code problems} empty means "run the tool". */
    record Result(List<String> problems, List<String> unknownKeys) {
        boolean valid() {
            return problems.isEmpty();
        }
    }

    /** True when the schema declares anything this validator can check (so wrapping is worth it). */
    static boolean hasCheckableSchema(String inputSchema, ObjectMapper objectMapper) {
        JsonNode schema = read(inputSchema, objectMapper);
        if (schema == null || !schema.isObject()) {
            return false;
        }
        JsonNode required = schema.get("required");
        JsonNode properties = schema.get("properties");
        return (required != null && required.isArray() && required.size() > 0)
            || (properties != null && properties.isObject());
    }

    static Result validate(String arguments, String inputSchema, ObjectMapper objectMapper) {
        List<String> problems = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        JsonNode schema = read(inputSchema, objectMapper);
        if (schema == null || !schema.isObject()) {
            return new Result(problems, unknown);
        }
        JsonNode args = arguments == null || arguments.isBlank() ? objectMapper.createObjectNode() : read(arguments, objectMapper);
        if (args == null) {
            problems.add("arguments are not valid JSON");
            return new Result(problems, unknown);
        }
        if (!args.isObject()) {
            problems.add("arguments must be a JSON object, got " + args.getNodeType().name().toLowerCase());
            return new Result(problems, unknown);
        }
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode name : required) {
                if (!name.isTextual()) {
                    continue;
                }
                JsonNode value = args.get(name.asText());
                if (value == null || value.isNull()) {
                    problems.add("missing required property '" + name.asText() + "'");
                }
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Set<String> declared = new LinkedHashSet<>();
            for (Iterator<String> it = properties.fieldNames(); it.hasNext(); ) {
                declared.add(it.next());
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = args.fields(); it.hasNext(); ) {
                String key = it.next().getKey();
                if (!declared.contains(key)) {
                    unknown.add(key);
                }
            }
        }
        return new Result(problems, unknown);
    }

    /**
     * The tool result handed back to the model in place of running the tool: machine-readable
     * {@code error}, the problems, the keys it sent that the tool does not know, and the schema so
     * the next attempt can be right.
     */
    static String feedback(String toolName, Result result, String inputSchema, ObjectMapper objectMapper) {
        StringBuilder hint = new StringBuilder();
        hint.append("The call to ").append(toolName).append(" was NOT executed: ")
            .append(String.join("; ", result.problems())).append(". ");
        if (!result.unknownKeys().isEmpty()) {
            hint.append("These keys are not parameters of ").append(toolName).append(" and were ignored: ")
                .append(String.join(", ", result.unknownKeys())).append(". ");
        }
        hint.append("Call ").append(toolName).append(" again with arguments that match its schema.");
        String schema = inputSchema == null ? "" : inputSchema.strip();
        if (schema.length() > MAX_SCHEMA_ECHO_CHARS) {
            schema = schema.substring(0, MAX_SCHEMA_ECHO_CHARS) + "...";
        }
        try {
            return objectMapper.writeValueAsString(Map.of(
                "error", "invalid_arguments",
                "tool", toolName,
                "problems", result.problems(),
                "unknown_keys", result.unknownKeys(),
                "hint", hint.toString(),
                "schema", schema));
        } catch (Exception e) {
            return "{\"error\":\"invalid_arguments\",\"hint\":\"" + hint.toString().replace("\"", "'") + "\"}";
        }
    }

    private static JsonNode read(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
