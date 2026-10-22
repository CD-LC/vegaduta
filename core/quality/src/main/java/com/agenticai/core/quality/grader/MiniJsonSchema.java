package com.agenticai.core.quality.grader;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A deliberately small JSON-Schema validator for code graders (this module does not depend on a
 * schema library, and a grader's whole value is that its verdict is predictable).
 *
 * <p>Supported keywords: {@code type} (string or array of
 * string/integer/number/boolean/object/array/null), {@code enum}, {@code const}, {@code
 * properties}, {@code required}, {@code additionalProperties} (boolean only), {@code items} (single
 * schema), {@code minItems}, {@code maxItems}, {@code minLength}, {@code maxLength}, {@code
 * minimum}, {@code maximum}, {@code pattern}, {@code anyOf}. Pure annotations ({@code $schema,
 * title, description, $comment, default, examples}) are ignored.
 *
 * <p>Any OTHER keyword is rejected by {@link #assertSupported} when the grader is saved: silently
 * ignoring {@code oneOf} or {@code $ref} would turn a stricter-than-supported schema into a grader
 * that passes things it should fail.
 */
final class MiniJsonSchema {

    private static final Set<String> ANNOTATIONS =
            Set.of("$schema", "title", "description", "$comment", "default", "examples");

    private static final Set<String> KEYWORDS =
            Set.of(
                    "type",
                    "enum",
                    "const",
                    "properties",
                    "required",
                    "additionalProperties",
                    "items",
                    "minItems",
                    "maxItems",
                    "minLength",
                    "maxLength",
                    "minimum",
                    "maximum",
                    "pattern",
                    "anyOf");

    private static final int MAX_DEPTH = 20;

    private MiniJsonSchema() {}

    /**
     * @throws IllegalArgumentException on an unsupported keyword or malformed supported keyword
     */
    static void assertSupported(JsonNode schema) {
        assertSupported(schema, 0);
    }

    private static void assertSupported(JsonNode schema, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "Schema is nested too deeply (max " + MAX_DEPTH + ").");
        }
        if (!schema.isObject()) {
            throw new IllegalArgumentException("Every (sub)schema must be a JSON object.");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = schema.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if (ANNOTATIONS.contains(key)) {
                continue;
            }
            if (!KEYWORDS.contains(key)) {
                throw new IllegalArgumentException(
                        "Unsupported schema keyword '"
                                + key
                                + "'. Supported: "
                                + new java.util.TreeSet<>(KEYWORDS)
                                + ".");
            }
            JsonNode value = field.getValue();
            switch (key) {
                case "properties":
                    if (!value.isObject()) {
                        throw new IllegalArgumentException("'properties' must be an object.");
                    }
                    for (JsonNode sub : value) {
                        assertSupported(sub, depth + 1);
                    }
                    break;
                case "items":
                    assertSupported(value, depth + 1);
                    break;
                case "anyOf":
                    if (!value.isArray() || value.isEmpty()) {
                        throw new IllegalArgumentException(
                                "'anyOf' must be a non-empty array of schemas.");
                    }
                    for (JsonNode sub : value) {
                        assertSupported(sub, depth + 1);
                    }
                    break;
                case "required":
                    if (!value.isArray()) {
                        throw new IllegalArgumentException(
                                "'required' must be an array of property names.");
                    }
                    break;
                case "enum":
                    if (!value.isArray() || value.isEmpty()) {
                        throw new IllegalArgumentException("'enum' must be a non-empty array.");
                    }
                    break;
                case "additionalProperties":
                    if (!value.isBoolean()) {
                        throw new IllegalArgumentException(
                                "'additionalProperties' must be true or false.");
                    }
                    break;
                case "pattern":
                    if (value.asText().length() > CodeGrader.MAX_PATTERN_LENGTH) {
                        throw new IllegalArgumentException(
                                "Schema 'pattern' is limited to "
                                        + CodeGrader.MAX_PATTERN_LENGTH
                                        + " characters.");
                    }
                    try {
                        Pattern.compile(value.asText());
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("Invalid 'pattern' regex in schema.");
                    }
                    break;
                case "type":
                    if (!value.isTextual() && !value.isArray()) {
                        throw new IllegalArgumentException(
                                "'type' must be a string or an array of strings.");
                    }
                    break;
                case "minItems":
                case "maxItems":
                case "minLength":
                case "maxLength":
                case "minimum":
                case "maximum":
                    if (!value.isNumber()) {
                        throw new IllegalArgumentException("'" + key + "' must be a number.");
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /** Appends one message per violation to {@code errors}; empty afterwards means valid. */
    static void validate(JsonNode schema, JsonNode data, String path, List<String> errors) {
        if (errors.size() >= 50) {
            return;
        }
        if (schema.has("type") && !typeMatches(schema.get("type"), data)) {
            errors.add(
                    path + ": expected type " + schema.get("type") + " but got " + typeName(data));
            return;
        }
        if (schema.has("const") && !schema.get("const").equals(data)) {
            errors.add(path + ": value is not the required constant");
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.get("enum")) {
                if (option.equals(data)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                errors.add(path + ": value is not one of the allowed enum values");
            }
        }
        if (schema.has("anyOf")) {
            boolean any = false;
            for (JsonNode option : schema.get("anyOf")) {
                java.util.List<String> sub = new java.util.ArrayList<>();
                validate(option, data, path, sub);
                if (sub.isEmpty()) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                errors.add(path + ": value matches none of the anyOf alternatives");
            }
        }
        if (data.isTextual()) {
            String s = data.asText();
            if (schema.has("minLength") && s.length() < schema.get("minLength").asInt()) {
                errors.add(
                        path
                                + ": string shorter than minLength "
                                + schema.get("minLength").asInt());
            }
            if (schema.has("maxLength") && s.length() > schema.get("maxLength").asInt()) {
                errors.add(
                        path + ": string longer than maxLength " + schema.get("maxLength").asInt());
            }
            if (schema.has("pattern")) {
                try {
                    if (!CodeGrader.matchBounded(
                            Pattern.compile(schema.get("pattern").asText()), s, false)) {
                        errors.add(path + ": string does not match pattern");
                    }
                } catch (CodeGrader.BudgetExceeded | StackOverflowError e) {
                    errors.add(path + ": pattern aborted (too expensive on this value)");
                }
            }
        }
        if (data.isNumber()) {
            if (schema.has("minimum") && data.asDouble() < schema.get("minimum").asDouble()) {
                errors.add(path + ": number below minimum " + schema.get("minimum").asText());
            }
            if (schema.has("maximum") && data.asDouble() > schema.get("maximum").asDouble()) {
                errors.add(path + ": number above maximum " + schema.get("maximum").asText());
            }
        }
        if (data.isObject()) {
            JsonNode properties = schema.path("properties");
            if (schema.has("required")) {
                for (JsonNode name : schema.get("required")) {
                    if (!data.has(name.asText())) {
                        errors.add(path + ": missing required property '" + name.asText() + "'");
                    }
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = data.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode sub = properties.get(field.getKey());
                if (sub != null) {
                    validate(sub, field.getValue(), path + "." + field.getKey(), errors);
                } else if (schema.has("additionalProperties")
                        && !schema.get("additionalProperties").asBoolean(true)) {
                    errors.add(path + ": unexpected property '" + field.getKey() + "'");
                }
            }
        }
        if (data.isArray()) {
            if (schema.has("minItems") && data.size() < schema.get("minItems").asInt()) {
                errors.add(path + ": fewer than minItems " + schema.get("minItems").asInt());
            }
            if (schema.has("maxItems") && data.size() > schema.get("maxItems").asInt()) {
                errors.add(path + ": more than maxItems " + schema.get("maxItems").asInt());
            }
            if (schema.has("items")) {
                int i = 0;
                for (JsonNode element : data) {
                    validate(schema.get("items"), element, path + "[" + i + "]", errors);
                    i++;
                }
            }
        }
    }

    private static boolean typeMatches(JsonNode typeSpec, JsonNode data) {
        if (typeSpec.isArray()) {
            for (JsonNode t : typeSpec) {
                if (typeMatches(t, data)) {
                    return true;
                }
            }
            return false;
        }
        switch (typeSpec.asText()) {
            case "string":
                return data.isTextual();
            case "boolean":
                return data.isBoolean();
            case "object":
                return data.isObject();
            case "array":
                return data.isArray();
            case "null":
                return data.isNull();
            case "number":
                return data.isNumber();
            case "integer":
                return data.isIntegralNumber()
                        || (data.isNumber() && data.asDouble() == Math.rint(data.asDouble()));
            default:
                return false;
        }
    }

    private static String typeName(JsonNode data) {
        if (data.isTextual()) {
            return "string";
        }
        if (data.isBoolean()) {
            return "boolean";
        }
        if (data.isObject()) {
            return "object";
        }
        if (data.isArray()) {
            return "array";
        }
        if (data.isNull()) {
            return "null";
        }
        return "number";
    }
}
