package com.agenticai.core.apidocs;

import java.util.Collection;
import java.util.Map;

/**
 * Tiny deterministic JSON writer for the OpenAPI document tree (Map / List / String / Number /
 * Boolean / null). Hand-rolled instead of going through Jackson's pretty printer because that one
 * uses the platform line separator (CRLF on Windows), which would make the committed
 * {@code docs/api/openapi.json} differ per machine. Map iteration order is preserved, so callers
 * choose the order (the generator uses TreeMap where order must be sorted).
 */
final class OpenApiJsonWriter {

    private OpenApiJsonWriter() {
    }

    /** Pretty-prints with two-space indent and LF line breaks, plus a trailing newline. */
    static String write(Object root) {
        StringBuilder sb = new StringBuilder(1 << 16);
        value(sb, root, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void value(StringBuilder sb, Object v, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            string(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (v instanceof Number n) {
            sb.append(n.toString());
        } else if (v instanceof Map<?, ?> m) {
            object(sb, m, depth);
        } else if (v instanceof Collection<?> c) {
            array(sb, c, depth);
        } else {
            string(sb, String.valueOf(v));
        }
    }

    private static void object(StringBuilder sb, Map<?, ?> m, int depth) {
        if (m.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            indent(sb, depth + 1);
            string(sb, String.valueOf(e.getKey()));
            sb.append(": ");
            value(sb, e.getValue(), depth + 1);
        }
        sb.append('\n');
        indent(sb, depth);
        sb.append('}');
    }

    private static void array(StringBuilder sb, Collection<?> c, int depth) {
        if (c.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        boolean first = true;
        for (Object o : c) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            indent(sb, depth + 1);
            value(sb, o, depth + 1);
        }
        sb.append('\n');
        indent(sb, depth);
        sb.append(']');
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }
}
