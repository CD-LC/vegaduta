package com.agenticai.core.chat.structured;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * A small, forgiving JSON Schema check for the prompt-and-validate fallback ({@code
 * OllamaStructuredFallbackChatModel}): is a model's reply the shape the caller's schema describes?
 * Supports {@code type} (one name or a list), {@code enum}, {@code const}, {@code required}, {@code
 * properties}, {@code items}, {@code anyOf}/{@code oneOf}/{@code allOf} and {@code
 * additionalProperties: false}. Anything else (formats, patterns, numeric bounds, remote or local
 * {@code $ref}) is ignored, so a schema feature this does not know can only make the check more
 * lenient, never reject a reply the real schema would accept.
 *
 * <p>Not a general validator: callers that need exact validation parse into their own type, which
 * is why a failed check here costs one repair turn at most and never an exception.
 */
public final class JsonSchemaCheck {

    private static final int MAX_PROBLEMS = 5;
    private static final int MAX_DEPTH = 24;

    private JsonSchemaCheck() {}

    /** Human-readable problems of {@code value} against {@code schema}; empty means it fits. */
    public static List<String> problems(JsonNode schema, JsonNode value) {
        List<String> out = new ArrayList<>();
        if (schema != null && schema.isObject()) {
            check(schema, value, "$", out, 0);
        }
        return out;
    }

    private static void check(
            JsonNode schema, JsonNode value, String path, List<String> out, int depth) {
        if (out.size() >= MAX_PROBLEMS
                || depth > MAX_DEPTH
                || schema == null
                || !schema.isObject()) {
            return;
        }
        if (schema.has("anyOf") || schema.has("oneOf")) {
            JsonNode alternatives = schema.has("anyOf") ? schema.get("anyOf") : schema.get("oneOf");
            if (alternatives.isArray() && !alternatives.isEmpty()) {
                boolean anyFits = false;
                for (JsonNode alternative : alternatives) {
                    List<String> trial = new ArrayList<>();
                    check(alternative, value, path, trial, depth + 1);
                    if (trial.isEmpty()) {
                        anyFits = true;
                        break;
                    }
                }
                if (!anyFits) {
                    out.add(path + " matches none of the allowed shapes");
                    return;
                }
            }
        }
        if (schema.has("allOf") && schema.get("allOf").isArray()) {
            for (JsonNode part : schema.get("allOf")) {
                check(part, value, path, out, depth + 1);
            }
        }
        if (!typeFits(schema.get("type"), value)) {
            out.add(path + " must be " + describe(schema.get("type")) + " but is " + kind(value));
            return;
        }
        if (schema.has("enum")
                && schema.get("enum").isArray()
                && !contains(schema.get("enum"), value)) {
            out.add(path + " must be one of " + schema.get("enum"));
            return;
        }
        if (schema.has("const") && !schema.get("const").equals(value)) {
            out.add(path + " must be " + schema.get("const"));
            return;
        }
        if (value.isObject()) {
            checkObject(schema, value, path, out, depth);
        } else if (value.isArray() && schema.has("items") && schema.get("items").isObject()) {
            for (int i = 0; i < value.size() && out.size() < MAX_PROBLEMS; i++) {
                check(schema.get("items"), value.get(i), path + "[" + i + "]", out, depth + 1);
            }
        }
    }

    private static void checkObject(
            JsonNode schema, JsonNode value, String path, List<String> out, int depth) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode name : required) {
                if (!value.has(name.asString())) {
                    out.add(path + "." + name.asString() + " is required");
                }
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.properties().iterator();
            while (fields.hasNext() && out.size() < MAX_PROBLEMS) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (value.has(field.getKey())) {
                    check(
                            field.getValue(),
                            value.get(field.getKey()),
                            path + "." + field.getKey(),
                            out,
                            depth + 1);
                }
            }
            JsonNode additional = schema.get("additionalProperties");
            if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
                Iterator<String> names = value.propertyNames().iterator();
                while (names.hasNext()) {
                    String name = names.next();
                    if (!properties.has(name) && out.size() < MAX_PROBLEMS) {
                        out.add(path + "." + name + " is not an allowed property");
                    }
                }
            }
        }
    }

    private static boolean typeFits(JsonNode type, JsonNode value) {
        if (type == null || type.isNull()) {
            return true;
        }
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (typeFits(t, value)) {
                    return true;
                }
            }
            return false;
        }
        return switch (type.asString()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            case "integer" ->
                    value.isIntegralNumber()
                            || (value.isNumber()
                                    && value.asDouble() == Math.rint(value.asDouble()));
            case "number" -> value.isNumber();
            default -> true;
        };
    }

    private static boolean contains(JsonNode array, JsonNode value) {
        for (JsonNode candidate : array) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(JsonNode type) {
        return type.isArray() ? "one of " + type : type.asString();
    }

    private static String kind(JsonNode value) {
        if (value.isObject()) {
            return "an object";
        }
        if (value.isArray()) {
            return "an array";
        }
        if (value.isString()) {
            return "a string";
        }
        if (value.isBoolean()) {
            return "a boolean";
        }
        if (value.isNumber()) {
            return "a number";
        }
        return "null";
    }
}
