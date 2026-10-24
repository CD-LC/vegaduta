package com.agenticai.core.prompts.tool;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and rewrites the parameter descriptions of a tool's JSON input schema, and nothing else.
 *
 * <p>A parameter is a schema node under {@code properties}, at any depth; an array's {@code items}
 * level adds no segment to the path (see {@link ToolPromptKeys}). Only the text of a {@code
 * description} keyword that is already there is read or replaced; no node is added, removed or
 * retyped, which is what keeps an override from changing the shape of a tool.
 */
public final class ToolPromptSchemas {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private ToolPromptSchemas() {}

    /** One described parameter: its path from the root, and its current text. */
    public record Param(String path, String description) {}

    /** Every parameter that has a textual description, in schema order. Empty for a schema that does not parse. */
    public static List<Param> params(String schemaJson) {
        JsonNode root = parse(schemaJson);
        List<Param> out = new ArrayList<>();
        if (root != null) {
            walk(root, "", (path, node) -> out.add(new Param(path, node.get("description").asText())));
        }
        return out;
    }

    /** {@link #params} as a map by path (insertion ordered). */
    public static Map<String, String> byPath(String schemaJson) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Param p : params(schemaJson)) {
            out.putIfAbsent(p.path(), p.description());
        }
        return out;
    }

    /**
     * The schema with the description of each parameter in {@code replacements} (by raw path)
     * replaced. A path with no described parameter is ignored. Returns the input unchanged when
     * nothing applies or the schema does not parse.
     */
    public static String withDescriptions(String schemaJson, Map<String, String> replacements) {
        if (replacements.isEmpty()) {
            return schemaJson;
        }
        JsonNode root = parse(schemaJson);
        if (root == null) {
            return schemaJson;
        }
        boolean[] changed = {false};
        walk(
                root,
                "",
                (path, node) -> {
                    String text = replacements.get(path);
                    if (text != null && !text.equals(node.get("description").asText())) {
                        ((ObjectNode) node).put("description", text);
                        changed[0] = true;
                    }
                });
        if (!changed[0]) {
            return schemaJson;
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return schemaJson;
        }
    }

    /** True when the two schemas are equal once every parameter description is ignored. */
    public static boolean sameShape(String a, String b) {
        JsonNode x = parse(a);
        JsonNode y = parse(b);
        if (x == null || y == null) {
            return java.util.Objects.equals(a, b);
        }
        blank(x);
        blank(y);
        return x.equals(y);
    }

    private static void blank(JsonNode root) {
        walk(root, "", (path, node) -> ((ObjectNode) node).put("description", ""));
    }

    private static JsonNode parse(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(schemaJson);
        } catch (Exception e) {
            return null;
        }
    }

    private interface Visitor {
        void visit(String path, JsonNode paramNode);
    }

    private static void walk(JsonNode schema, String prefix, Visitor visitor) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                JsonNode child = e.getValue();
                if (child != null && child.isObject()) {
                    JsonNode description = child.get("description");
                    if (description != null && description.isTextual()) {
                        visitor.visit(path, child);
                    }
                    walk(child, path, visitor);
                }
            }
        }
        JsonNode items = schema.get("items");
        if (items != null && items.isObject()) {
            walk(items, prefix, visitor);
        }
    }
}
