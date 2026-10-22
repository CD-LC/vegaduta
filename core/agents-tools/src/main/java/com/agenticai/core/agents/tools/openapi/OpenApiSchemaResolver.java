package com.agenticai.core.agents.tools.openapi;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.json.JsonNodes;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code $ref} handling of {@link OpenApiSpecParser}: inlines internal references with a cycle
 * guard and a depth cap, folds {@code allOf}, and strips OpenAPI-only vocabulary. Pure and static;
 * split out of the parser only to keep each file reviewable.
 */
final class OpenApiSchemaResolver {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int MAX_REF_DEPTH = 12;
    // OpenAPI-only vocabulary that is not JSON Schema and confuses provider
    // schema converters (Gemini's in particular).
    private static final Set<String> DROPPED_SCHEMA_KEYS =
            Set.of("xml", "discriminator", "externalDocs", "example", "examples");

    private OpenApiSchemaResolver() {}

    // ------------------------------------------------------------------
    // $ref handling
    // ------------------------------------------------------------------

    /**
     * Follows a {@code $ref} on a non-schema object (parameter, requestBody, path item); returns
     * the node itself otherwise.
     */
    static JsonNode resolveRef(JsonNode root, JsonNode node) {
        JsonNode current = node;
        for (int hop = 0;
                hop < MAX_REF_DEPTH && current != null && current.isObject() && current.has("$ref");
                hop++) {
            String ref = current.path("$ref").asString("");
            if (!ref.startsWith("#/")) {
                return MAPPER.createObjectNode();
            }
            JsonNode target = root.at(ref.substring(1));
            if (target.isMissingNode()) {
                return MAPPER.createObjectNode();
            }
            current = target;
        }
        return current == null ? MissingNode.getInstance() : current;
    }

    /**
     * Deep-copies a schema with every internal {@code $ref} inlined. Cycles (a Node whose children
     * are Nodes) and references deeper than {@link #MAX_REF_DEPTH} degrade to a bare {@code
     * {"type":"object"}} - a tool schema is a hint to the model, not a validator, and a permissive
     * leaf is strictly better than an import failure. {@code allOf} is merged into one object since
     * provider schema converters handle it unevenly. Returns {@code null} for a missing/non-object
     * schema.
     */
    static ObjectNode resolveSchema(JsonNode root, JsonNode schema, int depth, Set<String> stack) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return null;
        }
        if (schema.isBoolean()) {
            return MAPPER.createObjectNode();
        }
        if (!schema.isObject()) {
            return null;
        }
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asString("");
            if (!ref.startsWith("#/") || stack.contains(ref) || depth > MAX_REF_DEPTH) {
                return genericObject();
            }
            JsonNode target = root.at(ref.substring(1));
            if (target.isMissingNode()) {
                return genericObject();
            }
            stack.add(ref);
            try {
                ObjectNode resolved = resolveSchema(root, target, depth + 1, stack);
                return resolved == null ? genericObject() : resolved;
            } finally {
                stack.remove(ref);
            }
        }
        if (depth > MAX_REF_DEPTH) {
            return genericObject();
        }
        ObjectNode out = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> it = JsonNodes.fields(schema);
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> field = it.next();
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (key.startsWith("x-") || DROPPED_SCHEMA_KEYS.contains(key)) {
                continue;
            }
            switch (key) {
                case "properties" -> {
                    ObjectNode props = out.putObject("properties");
                    if (value.isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> pit = JsonNodes.fields(value);
                        while (pit.hasNext()) {
                            Map.Entry<String, JsonNode> prop = pit.next();
                            ObjectNode resolved =
                                    resolveSchema(root, prop.getValue(), depth + 1, stack);
                            props.set(
                                    prop.getKey(),
                                    resolved == null ? MAPPER.createObjectNode() : resolved);
                        }
                    }
                }
                case "items", "not" -> {
                    ObjectNode resolved = resolveSchema(root, value, depth + 1, stack);
                    out.set(key, resolved == null ? MAPPER.createObjectNode() : resolved);
                }
                case "additionalProperties" -> {
                    if (value.isObject()) {
                        ObjectNode resolved = resolveSchema(root, value, depth + 1, stack);
                        out.set(key, resolved == null ? MAPPER.createObjectNode() : resolved);
                    } else {
                        out.set(key, value.deepCopy());
                    }
                }
                case "oneOf", "anyOf", "allOf" -> {
                    ArrayNode arr = MAPPER.createArrayNode();
                    if (value.isArray()) {
                        for (JsonNode alt : value) {
                            ObjectNode resolved = resolveSchema(root, alt, depth + 1, stack);
                            arr.add(resolved == null ? MAPPER.createObjectNode() : resolved);
                        }
                    }
                    out.set(key, arr);
                }
                default -> out.set(key, value.deepCopy());
            }
        }
        if (out.has("allOf")) {
            mergeAllOf(out);
        }
        return out;
    }

    /**
     * Folds {@code allOf} members' properties/required/type into the enclosing schema and drops the
     * keyword.
     */
    static void mergeAllOf(ObjectNode out) {
        JsonNode allOf = out.remove("allOf");
        ObjectNode props =
                out.has("properties") && out.get("properties").isObject()
                        ? (ObjectNode) out.get("properties")
                        : null;
        Set<String> required = new LinkedHashSet<>();
        for (JsonNode r : out.path("required")) {
            if (r.isString()) {
                required.add(r.asString());
            }
        }
        for (JsonNode member : allOf) {
            if (!member.isObject()) {
                continue;
            }
            if (member.path("properties").isObject()) {
                if (props == null) {
                    props = out.putObject("properties");
                }
                Iterator<Map.Entry<String, JsonNode>> it =
                        JsonNodes.fields(member.path("properties"));
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> prop = it.next();
                    if (!props.has(prop.getKey())) {
                        props.set(prop.getKey(), prop.getValue());
                    }
                }
            }
            for (JsonNode r : member.path("required")) {
                if (r.isString()) {
                    required.add(r.asString());
                }
            }
            if (!out.has("type") && member.has("type")) {
                out.set("type", member.get("type"));
            }
            if (!out.has("description") && member.has("description")) {
                out.set("description", member.get("description"));
            }
        }
        if (!required.isEmpty()) {
            ArrayNode arr = out.putArray("required");
            required.forEach(arr::add);
        }
        if (!out.has("type") && props != null) {
            out.put("type", "object");
        }
    }

    static ObjectNode genericObject() {
        return MAPPER.createObjectNode().put("type", "object");
    }

    static JsonNode withDescription(JsonNode schema, String description) {
        if (description == null
                || description.isBlank()
                || !schema.isObject()
                || schema.has("description")) {
            return schema;
        }
        ObjectNode copy = ((ObjectNode) schema).deepCopy();
        copy.put("description", clip(description, 1_000));
        return copy;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
