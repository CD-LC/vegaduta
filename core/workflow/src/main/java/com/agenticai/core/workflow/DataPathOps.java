package com.agenticai.core.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Field-path reading and writing ({@code a.b[0].c}, {@code ["odd key"]}): {@link #get} never throws on a missing
 * step, {@link #setPath} creates intermediate objects. Extracted from {@link WorkflowDataOps}.
 */
final class DataPathOps {
    /** Reads a (dot / bracket) path; MissingNode when any step is absent. A blank path is the node itself. */
    static JsonNode get(JsonNode root, String path) {
        if (root == null) {
            return MissingNode.getInstance();
        }
        if (path == null || path.isBlank()) {
            return root;
        }
        JsonNode cur = root;
        for (Object token : tokenize(path.strip())) {
            if (cur == null || cur.isMissingNode() || cur.isNull()) {
                return MissingNode.getInstance();
            }
            if (token instanceof Integer idx) {
                if (!cur.isArray()) {
                    return MissingNode.getInstance();
                }
                int i = idx < 0 ? cur.size() + idx : idx;
                cur = i >= 0 && i < cur.size() ? cur.get(i) : MissingNode.getInstance();
            } else {
                cur = cur.isObject() ? cur.path((String) token) : MissingNode.getInstance();
            }
        }
        return cur == null ? MissingNode.getInstance() : cur;
    }

    /** Assigns {@code value} at a dotted path, creating intermediate objects. Array steps must already exist. */
    static void setPath(ObjectNode root, String path, JsonNode value) {
        List<Object> tokens = tokenize(path.strip());
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Field name is empty.");
        }
        JsonNode cur = root;
        for (int i = 0; i < tokens.size(); i++) {
            Object token = tokens.get(i);
            boolean lastToken = i == tokens.size() - 1;
            if (token instanceof Integer idx) {
                if (!cur.isArray()) {
                    throw new IllegalArgumentException("Cannot set '" + path + "': '[" + idx + "]' is not inside a list.");
                }
                ArrayNode arr = (ArrayNode) cur;
                int at = idx < 0 ? arr.size() + idx : idx;
                if (at < 0 || at >= arr.size()) {
                    throw new IllegalArgumentException("Cannot set '" + path + "': index " + idx + " does not exist.");
                }
                if (lastToken) {
                    arr.set(at, value);
                } else {
                    cur = arr.get(at);
                }
            } else {
                String key = (String) token;
                if (!cur.isObject()) {
                    throw new IllegalArgumentException("Cannot set '" + path + "': '" + key + "' is not inside an object.");
                }
                ObjectNode obj = (ObjectNode) cur;
                if (lastToken) {
                    obj.set(key, value);
                } else {
                    JsonNode child = obj.get(key);
                    if (child == null || child.isNull() || !(child.isObject() || child.isArray())) {
                        child = obj.putObject(key);
                    }
                    cur = child;
                }
            }
        }
    }

    /** {@code a.b[0]["c d"]} -> ["a", "b", 0, "c d"]. */
    static List<Object> tokenize(String path) {
        List<Object> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = path.length();
        while (i < n) {
            char c = path.charAt(i);
            if (c == '.') {
                if (sb.length() > 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
                i++;
            } else if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) {
                    sb.append(path, i, n);
                    break;
                }
                if (sb.length() > 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
                String inner = path.substring(i + 1, close).strip();
                try {
                    out.add(Integer.valueOf(inner));
                } catch (NumberFormatException e) {
                    out.add(unquote(inner));
                }
                i = close + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        if (sb.length() > 0) {
            out.add(sb.toString());
        }
        return out;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private DataPathOps() {
    }
}
