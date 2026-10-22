package com.agenticai.core.brief;

import com.agenticai.core.json.JsonNodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * RFC 6902 JSON Patch between two manifests: {@link #diff} produces {@code add} / {@code remove} /
 * {@code replace} operations, {@link #apply} replays them (used by tests to prove a diff is
 * faithful, and available to any caller that wants to materialise a tune proposal from a patch).
 *
 * <p>Arrays are compared index by index. Every artifact list in a Brief is ordered and its members
 * carry stable {@code ref}s, so an edit shows up as a {@code replace} at the same index and an
 * insert or delete at the end as {@code add}/{@code remove}; a reorder is a few replaces. That is
 * larger than a minimal patch but always valid and always readable.
 */
public final class BriefJsonPatch {

    private BriefJsonPatch() {}

    public static List<ObjectNode> diff(JsonNode from, JsonNode to) {
        List<ObjectNode> ops = new ArrayList<>();
        walk("", from, to, ops);
        return ops;
    }

    /** The same operations as a JSON array (what the API returns). */
    public static ArrayNode diffAsArray(JsonNode from, JsonNode to) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        diff(from, to).forEach(out::add);
        return out;
    }

    private static void walk(String path, JsonNode a, JsonNode b, List<ObjectNode> ops) {
        if (a == null && b == null) {
            return;
        }
        if (a == null) {
            ops.add(op("add", path, b));
            return;
        }
        if (b == null) {
            ops.add(op("remove", path, null));
            return;
        }
        if (a.equals(b)) {
            return;
        }
        if (a.isObject() && b.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = JsonNodes.fields(a);
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                String child = path + "/" + escape(e.getKey());
                if (b.has(e.getKey())) {
                    walk(child, e.getValue(), b.get(e.getKey()), ops);
                } else {
                    ops.add(op("remove", child, null));
                }
            }
            Iterator<Map.Entry<String, JsonNode>> added = JsonNodes.fields(b);
            while (added.hasNext()) {
                Map.Entry<String, JsonNode> e = added.next();
                if (!a.has(e.getKey())) {
                    ops.add(op("add", path + "/" + escape(e.getKey()), e.getValue()));
                }
            }
            return;
        }
        if (a.isArray() && b.isArray()) {
            int common = Math.min(a.size(), b.size());
            for (int i = 0; i < common; i++) {
                walk(path + "/" + i, a.get(i), b.get(i), ops);
            }
            // Removals go from the tail so earlier indices stay valid while the patch replays.
            for (int i = a.size() - 1; i >= common; i--) {
                ops.add(op("remove", path + "/" + i, null));
            }
            for (int i = common; i < b.size(); i++) {
                ops.add(op("add", path + "/" + i, b.get(i)));
            }
            return;
        }
        ops.add(op("replace", path, b));
    }

    private static ObjectNode op(String name, String path, JsonNode value) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("op", name);
        node.put("path", path);
        if (value != null) {
            node.set("value", value.deepCopy());
        }
        return node;
    }

    // ------------------------------------------------------------------ apply

    /**
     * Replays {@code ops} onto a deep copy of {@code doc}; throws IllegalArgumentException on a bad
     * path.
     */
    public static JsonNode apply(JsonNode doc, List<? extends JsonNode> ops) {
        JsonNode current = doc.deepCopy();
        for (JsonNode op : ops) {
            String name = op.path("op").asText();
            String path = op.path("path").asText();
            JsonNode value = op.get("value");
            if (path.isEmpty()) {
                if (!"replace".equals(name) && !"add".equals(name)) {
                    throw new IllegalArgumentException("Cannot " + name + " the document root.");
                }
                current = value.deepCopy();
                continue;
            }
            int cut = path.lastIndexOf('/');
            JsonNode parent = resolve(current, path.substring(0, cut));
            String key = unescape(path.substring(cut + 1));
            switch (name) {
                case "add" -> put(parent, key, value.deepCopy(), true);
                case "replace" -> put(parent, key, value.deepCopy(), false);
                case "remove" -> remove(parent, key);
                default ->
                        throw new IllegalArgumentException("Unsupported patch op '" + name + "'.");
            }
        }
        return current;
    }

    private static JsonNode resolve(JsonNode root, String pointer) {
        if (pointer.isEmpty()) {
            return root;
        }
        JsonNode node = root;
        for (String part : pointer.substring(1).split("/", -1)) {
            String key = unescape(part);
            node = node.isArray() ? node.get(Integer.parseInt(key)) : node.get(key);
            if (node == null) {
                throw new IllegalArgumentException("Patch path '" + pointer + "' does not exist.");
            }
        }
        return node;
    }

    private static void put(JsonNode parent, String key, JsonNode value, boolean insert) {
        if (parent instanceof ObjectNode obj) {
            obj.set(key, value);
        } else if (parent instanceof ArrayNode arr) {
            int index = Integer.parseInt(key);
            if (insert) {
                arr.insert(index, value);
            } else {
                arr.set(index, value);
            }
        } else {
            throw new IllegalArgumentException("Patch target is not a container.");
        }
    }

    private static void remove(JsonNode parent, String key) {
        if (parent instanceof ObjectNode obj) {
            obj.remove(key);
        } else if (parent instanceof ArrayNode arr) {
            arr.remove(Integer.parseInt(key));
        } else {
            throw new IllegalArgumentException("Patch target is not a container.");
        }
    }

    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    private static String unescape(String key) {
        return key.replace("~1", "/").replace("~0", "~");
    }
}
