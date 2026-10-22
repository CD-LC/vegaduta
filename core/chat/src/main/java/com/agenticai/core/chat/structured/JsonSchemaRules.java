package com.agenticai.core.chat.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JSON Schema checks and rewrites behind {@link StructuredOutput}'s native structured-output
 * switches: parsing a caller's schema, OpenAI strict-mode compatibility, the Gemini keyword strip,
 * Anthropic's local-{@code $ref} rule and the provider-safe schema/tool name. Schema-aware walks: a
 * property NAMED like a keyword is never a keyword.
 */
final class JsonSchemaRules {

    private JsonSchemaRules() {}

    static JsonNode parseSchema(String jsonSchema) {
        if (jsonSchema == null || jsonSchema.isBlank()) {
            return null;
        }
        JsonNode node = JsonLocator.parseStrict(StructuredOutput.DEFAULT_MAPPER, jsonSchema);
        return node != null && node.isObject() ? node : null;
    }

    static String sanitizeSchemaName(String name) {
        String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9_-]", "_");
        if (n.isEmpty()) {
            n = "structured_output";
        }
        return n.length() > 64 ? n.substring(0, 64) : n;
    }

    /**
     * OpenAI strict mode: every object has additionalProperties:false and lists all its properties
     * as required.
     */
    static boolean isStrictCompatible(JsonNode node) {
        if (node == null || !node.isObject()) {
            return true;
        }
        // Schema-aware walk: "properties" is only a keyword here, and its CHILDREN are schemas
        // keyed by property name - a property that happens to be called "properties" or
        // "additionalProperties" is a name, never a keyword.
        JsonNode props = node.get("properties");
        boolean hasProps = props != null && props.isObject();
        if (hasProps || declaresObjectType(node)) {
            // Strict mode rejects ANY open object, including a bare {"type":"object"}.
            JsonNode ap = node.get("additionalProperties");
            if (ap == null || !ap.isBoolean() || ap.asBoolean()) {
                return false;
            }
        }
        if (hasProps) {
            Set<String> required = new HashSet<>();
            JsonNode req = node.get("required");
            if (req != null && req.isArray()) {
                req.forEach(r -> required.add(r.asText()));
            }
            Set<String> names = new HashSet<>();
            props.fieldNames().forEachRemaining(names::add);
            if (!required.containsAll(names)) {
                return false;
            }
            for (JsonNode child : props) {
                if (!isStrictCompatible(child)) {
                    return false;
                }
            }
        }
        for (JsonNode sub : subSchemas(node)) {
            if (!isStrictCompatible(sub)) {
                return false;
            }
        }
        return true;
    }

    private static boolean declaresObjectType(JsonNode schema) {
        JsonNode type = schema.get("type");
        if (type == null) {
            return false;
        }
        if (type.isTextual()) {
            return "object".equals(type.asText());
        }
        if (type.isArray()) {
            for (JsonNode t : type) {
                if ("object".equals(t.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Schemas nested under the applicator keywords other than "properties": items,
     * anyOf/allOf/oneOf, $defs.
     */
    static List<JsonNode> subSchemas(JsonNode schema) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode items = schema.get("items");
        if (items != null) {
            if (items.isArray()) {
                items.forEach(out::add);
            } else {
                out.add(items);
            }
        }
        for (String k : List.of("anyOf", "allOf", "oneOf")) {
            JsonNode arr = schema.get(k);
            if (arr != null && arr.isArray()) {
                arr.forEach(out::add);
            }
        }
        for (String k : List.of("$defs", "definitions")) {
            JsonNode defs = schema.get(k);
            if (defs != null && defs.isObject()) {
                defs.forEach(out::add);
            }
        }
        return out;
    }

    static boolean containsKey(JsonNode node, String key) {
        if (node.isObject() && node.has(key)) {
            return true;
        }
        for (JsonNode child : node) {
            if (containsKey(child, key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the JSON-Schema-only keywords Gemini rejects, walking schemas (not property names).
     */
    static void stripKeys(JsonNode node) {
        if (!(node instanceof ObjectNode obj)) {
            return;
        }
        obj.remove("$schema");
        obj.remove("additionalProperties");
        JsonNode props = obj.get("properties");
        if (props != null && props.isObject()) {
            props.forEach(JsonSchemaRules::stripKeys);
        }
        subSchemas(obj).forEach(JsonSchemaRules::stripKeys);
    }

    static boolean hasNonLocalRef(JsonNode node) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual() && !ref.asText().startsWith("#")) {
                return true;
            }
        }
        for (JsonNode child : node) {
            if (hasNonLocalRef(child)) {
                return true;
            }
        }
        return false;
    }
}
