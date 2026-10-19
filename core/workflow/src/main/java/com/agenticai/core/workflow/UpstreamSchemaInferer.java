package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowExpressionApi.SchemaPath;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Infers the addressable paths of a node's output from one real value (a pin or the last run), for editor
 * autocomplete and the data picker. A path is exactly what an expression would write after the node reference:
 * {@code items[0].price}, {@code customer["e-mail"]}.
 *
 * <p>Bounds (contract): at most {@value #MAX_PATHS} paths per node, {@value #MAX_DEPTH} segments deep, and only
 * the first {@value #MAX_ARRAY_ELEMENTS} elements of any array (later elements have the same shape in practice,
 * and walking them would blow the cap on one long list). Containers are listed before their children so a tree
 * can be built from the list in order. Output that is not a JSON object or array (plain text, a bare number)
 * has no sub-paths and yields an empty list; the node itself is still pickable.
 */
final class UpstreamSchemaInferer {

    static final int MAX_PATHS = 200;
    static final int MAX_DEPTH = 6;
    static final int MAX_ARRAY_ELEMENTS = 3;
    static final int MAX_SAMPLE_CHARS = 80;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final ObjectMapper mapper;

    UpstreamSchemaInferer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    List<SchemaPath> infer(String text) {
        List<SchemaPath> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (Exception e) {
            return out;
        }
        if (root == null || !(root.isObject() || root.isArray())) {
            return out;
        }
        walk(root, "", 0, out);
        return out;
    }

    private void walk(JsonNode node, String prefix, int depth, List<SchemaPath> out) {
        if (depth >= MAX_DEPTH) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext() && out.size() < MAX_PATHS) {
                Map.Entry<String, JsonNode> f = fields.next();
                String path = prefix.isEmpty() ? keyRoot(f.getKey()) : prefix + keySuffix(f.getKey());
                add(path, f.getValue(), out);
                walk(f.getValue(), path, depth + 1, out);
            }
        } else if (node.isArray()) {
            int n = Math.min(node.size(), MAX_ARRAY_ELEMENTS);
            for (int i = 0; i < n && out.size() < MAX_PATHS; i++) {
                String path = prefix + "[" + i + "]";
                add(path, node.get(i), out);
                walk(node.get(i), path, depth + 1, out);
            }
        }
    }

    private void add(String path, JsonNode value, List<SchemaPath> out) {
        if (out.size() < MAX_PATHS) {
            out.add(new SchemaPath(path, typeOf(value), sampleOf(value)));
        }
    }

    /** A key at the top of the output: bare when it is an identifier, else {@code ["key"]}. */
    private static String keyRoot(String key) {
        return IDENTIFIER.matcher(key).matches() ? key : "[\"" + escape(key) + "\"]";
    }

    /** A key below a prefix: {@code .key} when it is an identifier, else {@code ["key"]}. */
    private static String keySuffix(String key) {
        return IDENTIFIER.matcher(key).matches() ? "." + key : "[\"" + escape(key) + "\"]";
    }

    private static String escape(String key) {
        return key.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String typeOf(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "null";
        }
        if (v.isTextual()) {
            return "string";
        }
        if (v.isNumber()) {
            return "number";
        }
        if (v.isBoolean()) {
            return "boolean";
        }
        return v.isArray() ? "array" : "object";
    }

    private static String sampleOf(JsonNode v) {
        if (v == null || v.isNull() || v.isContainerNode() || v.isMissingNode()) {
            return null;
        }
        String s = v.asText();
        return s.length() > MAX_SAMPLE_CHARS ? s.substring(0, MAX_SAMPLE_CHARS) + "..." : s;
    }
}
