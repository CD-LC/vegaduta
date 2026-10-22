package com.agenticai.core.agents.importer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Type-tolerant accessors over untrusted JSON. A wrong type or a missing field yields null / empty
 * rather than an exception or a cast, so a hostile payload (a number where a string is expected, an
 * array where an object is expected) degrades to "field not present" and the mapper reports it.
 * Pure.
 */
public final class AgentImportJson {

    private AgentImportJson() {}

    /** Keys whose values are credentials or credential references; never read, only counted. */
    private static final Pattern SECRET_KEY =
            Pattern.compile(
                    "(?i)(api[-_ ]?key|secret|passw(or)?d|credential|bearer|authorization|auth[-_ ]?token|access[-_ ]?token"
                            + "|refresh[-_ ]?token|private[-_ ]?key|session[-_ ]?token|^token$|^key$)");

    /** Upper bound on nodes visited when scanning for credential fields. */
    private static final int SCAN_NODE_CAP = 200_000;

    /** The child as an object, or null when absent or not an object. */
    public static JsonNode obj(JsonNode node, String field) {
        JsonNode v = child(node, field);
        return v != null && v.isObject() ? v : null;
    }

    /** The child as an array, or null when absent or not an array. */
    public static JsonNode arr(JsonNode node, String field) {
        JsonNode v = child(node, field);
        return v != null && v.isArray() ? v : null;
    }

    /** The child's text, trimmed, or null when absent, not textual, or blank. */
    public static String text(JsonNode node, String field) {
        JsonNode v = child(node, field);
        return textOf(v);
    }

    /** {@code v}'s text, trimmed, or null when {@code v} is absent, not textual, or blank. */
    public static String textOf(JsonNode v) {
        if (v == null || !v.isString()) {
            return null;
        }
        String s = v.asString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * The first non-null text among {@code fields}; lets one mapper accept snake_case and camelCase
     * spellings of the same property.
     */
    public static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String t = text(node, f);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /**
     * The child as a number. Accepts a JSON number or a numeric string ("0.7" - Flowise stores some
     * numeric inputs as strings); null otherwise or when not finite.
     */
    public static Double number(JsonNode node, String field) {
        JsonNode v = child(node, field);
        if (v == null) {
            return null;
        }
        Double d = null;
        if (v.isNumber()) {
            d = v.asDouble();
        } else if (v.isString()) {
            String s = v.asString().trim();
            if (!s.isEmpty() && s.length() <= 32) {
                try {
                    d = Double.valueOf(s);
                } catch (NumberFormatException ignored) {
                    d = null;
                }
            }
        }
        return d != null && !d.isNaN() && !d.isInfinite() ? d : null;
    }

    /** The first non-null number among {@code fields}. */
    public static Double firstNumber(JsonNode node, String... fields) {
        for (String f : fields) {
            Double d = number(node, f);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /** True when the child is present and not null/blank/empty. */
    public static boolean hasValue(JsonNode node, String field) {
        JsonNode v = child(node, field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return false;
        }
        if (v.isString()) {
            return !v.asString().isBlank();
        }
        if (v.isContainerNode()) {
            return v.size() > 0;
        }
        return true;
    }

    /** Elements of an array child (empty list when absent or not an array). */
    public static List<JsonNode> elements(JsonNode node, String field) {
        JsonNode a = arr(node, field);
        List<JsonNode> out = new ArrayList<>();
        if (a != null) {
            a.forEach(out::add);
        }
        return out;
    }

    /** Field names of an object child, in document order (empty when absent or not an object). */
    public static List<String> fieldNames(JsonNode node, String field) {
        JsonNode o = obj(node, field);
        List<String> out = new ArrayList<>();
        if (o != null) {
            o.propertyNames().forEach(out::add);
        }
        return out;
    }

    /** True when a field name looks like a credential / secret / token holder. */
    public static boolean isSecretKey(String key) {
        return key != null && SECRET_KEY.matcher(key).find();
    }

    /**
     * Counts fields anywhere under {@code root} whose NAME looks like a credential and whose value
     * is non-empty. Values are never read into the result and nothing is copied - this exists only
     * so the report can say "N credential fields were found and not imported". Iterative (no
     * recursion depth risk) and bounded by {@link #SCAN_NODE_CAP}.
     */
    public static int countCredentialFields(JsonNode root) {
        if (root == null) {
            return 0;
        }
        int count = 0;
        int visited = 0;
        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty() && visited < SCAN_NODE_CAP) {
            JsonNode n = stack.pop();
            visited++;
            if (n.isObject()) {
                var it = n.fields();
                while (it.hasNext()) {
                    var e = it.next();
                    JsonNode v = e.getValue();
                    if (isSecretKey(e.getKey()) && isNonEmptyValue(v)) {
                        count++;
                    } else if (v.isContainerNode()) {
                        stack.push(v);
                    }
                }
            } else if (n.isArray()) {
                n.forEach(stack::push);
            }
        }
        return count;
    }

    private static boolean isNonEmptyValue(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return false;
        }
        if (v.isString()) {
            return !v.asString().isBlank();
        }
        if (v.isContainerNode()) {
            return v.size() > 0;
        }
        return true;
    }

    private static JsonNode child(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.isMissingNode() ? null : v;
    }
}
