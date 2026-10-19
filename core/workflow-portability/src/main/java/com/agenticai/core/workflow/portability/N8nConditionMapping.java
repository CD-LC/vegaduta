package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportJson;
import com.agenticai.core.agents.importer.AgentImportText;
import com.agenticai.core.workflow.portability.N8nImportModel.NodeCtx;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites an n8n IF node's comparisons as one VegaDuta Condition expression. Part of {@link
 * N8nImportMapper}.
 */
final class N8nConditionMapping {

    private static final Pattern WHOLE_EXPRESSION =
            Pattern.compile("^\\{\\{(.*)}}$", Pattern.DOTALL);
    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    /** Either the combined expression, or (expression null) the reason it could not be written. */
    record Translation(String expression, String failure) {}

    private N8nConditionMapping() {}

    static Translation translate(NodeCtx c) {
        JsonNode conditions = c.params.path("conditions");
        List<String> parts = new ArrayList<>();
        String joiner = " && ";
        String failure = null;
        if (conditions.has("conditions") && conditions.get("conditions").isArray()) {
            joiner =
                    "or".equalsIgnoreCase(AgentImportJson.text(conditions, "combinator"))
                            ? " || "
                            : " && ";
            for (JsonNode item : conditions.get("conditions")) {
                JsonNode operator = item.path("operator");
                String expression =
                        compare(
                                c,
                                AgentImportJson.text(operator, "type"),
                                AgentImportJson.text(operator, "operation"),
                                item.get("leftValue"),
                                item.get("rightValue"));
                if (expression == null) {
                    failure =
                            "an operator or operand it cannot express ("
                                    + AgentImportText.inline(
                                            String.valueOf(
                                                    AgentImportJson.text(operator, "operation")),
                                            30)
                                    + ")";
                    break;
                }
                parts.add(expression);
            }
        } else if (conditions.isObject()) {
            joiner = "any".equalsIgnoreCase(c.text("combineOperation")) ? " || " : " && ";
            for (String type : List.of("string", "number", "boolean")) {
                JsonNode list = conditions.get(type);
                if (list == null || !list.isArray()) {
                    continue;
                }
                for (JsonNode item : list) {
                    String expression =
                            compare(
                                    c,
                                    type,
                                    AgentImportJson.text(item, "operation"),
                                    item.get("value1"),
                                    item.get("value2"));
                    if (expression == null) {
                        failure =
                                "an operation or operand it cannot express ("
                                        + AgentImportText.inline(
                                                String.valueOf(
                                                        AgentImportJson.text(item, "operation")),
                                                30)
                                        + ")";
                        break;
                    }
                    parts.add(expression);
                }
                if (failure != null) {
                    break;
                }
            }
            for (String other : List.of("dateTime", "array", "object")) {
                if (conditions.has(other) && conditions.get(other).size() > 0) {
                    failure = "date/array/object comparisons";
                }
            }
        }
        if (failure != null || parts.isEmpty()) {
            return new Translation(null, failure == null ? "no conditions" : failure);
        }
        List<String> wrapped = new ArrayList<>();
        for (String part : parts) {
            wrapped.add("(" + part + ")");
        }
        return new Translation(String.join(joiner, wrapped), null);
    }

    /**
     * One comparison as a bare VegaDuta expression, or null when the operator or an operand cannot
     * be expressed.
     */
    private static String compare(
            NodeCtx c, String type, String operation, JsonNode left, JsonNode right) {
        if (operation == null) {
            return null;
        }
        String l = operand(c, left, type);
        if (l == null) {
            return null;
        }
        boolean unary =
                Set.of(
                                "empty",
                                "isEmpty",
                                "notEmpty",
                                "isNotEmpty",
                                "exists",
                                "notExists",
                                "true",
                                "false")
                        .contains(operation);
        String r = unary ? "null" : operand(c, right, type);
        if (r == null) {
            return null;
        }
        return switch (operation) {
            case "equals", "equal" -> l + " == " + r;
            case "notEquals", "notEqual" -> l + " != " + r;
            case "contains" -> "contains(" + l + ", " + r + ")";
            case "notContains" -> "!contains(" + l + ", " + r + ")";
            case "startsWith" -> "startsWith(" + l + ", " + r + ")";
            case "endsWith" -> "endsWith(" + l + ", " + r + ")";
            case "notStartsWith" -> "!startsWith(" + l + ", " + r + ")";
            case "notEndsWith" -> "!endsWith(" + l + ", " + r + ")";
            case "empty", "isEmpty" -> "isEmpty(" + l + ")";
            case "notEmpty", "isNotEmpty" -> "!isEmpty(" + l + ")";
            case "exists" -> l + " != null";
            case "notExists" -> l + " == null";
            case "gt", "larger" -> l + " > " + r;
            case "lt", "smaller" -> l + " < " + r;
            case "gte", "largerEqual" -> l + " >= " + r;
            case "lte", "smallerEqual" -> l + " <= " + r;
            case "true" -> l + " == true";
            case "false" -> l + " == false";
            default -> null;
        };
    }

    private static String operand(NodeCtx c, JsonNode value, String type) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isBoolean()) {
            return value.asBoolean() ? "true" : "false";
        }
        if (value.isNumber()) {
            return value.asText();
        }
        String raw = value.asText();
        if (raw.startsWith("=")) {
            c.expression = true;
            Matcher whole = WHOLE_EXPRESSION.matcher(raw.substring(1).strip());
            if (whole.matches()
                    && !whole.group(1).contains("{{")
                    && !whole.group(1).contains("}}")) {
                return "(" + PortableExpressions.n8nText("=" + whole.group(1).strip()) + ")";
            }
            return null;
        }
        if ("number".equals(type) && NUMERIC.matcher(raw.strip()).matches()) {
            return raw.strip();
        }
        if ("boolean".equals(type)) {
            return "true".equalsIgnoreCase(raw.strip()) ? "true" : "false";
        }
        return PortableExpressions.quote(raw);
    }
}
