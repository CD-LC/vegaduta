package com.agenticai.core.workflow.portability;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expression plumbing for the foreign-format mappers. The mapping rules were authored from the
 * observed public file formats (n8n's {@code ={{ ... }}} strings, Dify's {@code {{#node.var#}}}
 * references), not copied from either project.
 *
 * <p>VegaDuta's expression language is deliberately a restricted one (see {@code expression/});
 * n8n's are JavaScript. So these rewrites only translate the data accessors that have a direct
 * VegaDuta spelling and the caller marks every node that carried an expression as "needs review" -
 * nothing is claimed to be equivalent beyond what the engine's own lint accepts.
 */
final class PortableExpressions {

    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** {@code $('Node').item.json} / {@code .first().json} / {@code .last().json}. */
    private static final Pattern N8N_NODE_CALL =
            Pattern.compile(
                    "\\$\\(\\s*['\"]([^'\"]{1,200})['\"]\\s*\\)\\.(?:item|first\\(\\)|last\\(\\))\\.json\\b");

    /** {@code $node["Node"].json}. */
    private static final Pattern N8N_NODE_INDEX =
            Pattern.compile("\\$node\\[\\s*['\"]([^'\"]{1,200})['\"]\\s*]\\.json\\b");

    /** {@code $input.item.json} / {@code .first().json}. */
    private static final Pattern N8N_INPUT_ITEM =
            Pattern.compile("\\$input\\.(?:item|first\\(\\)|last\\(\\))\\.json\\b");

    private PortableExpressions() {}

    /** A single-quoted VegaDuta string literal. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder("'");
        String v = s == null ? "" : s;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('\'').toString();
    }

    /**
     * {@code $node["Label"]} - the documented way to read an earlier node's output by its label.
     */
    static String nodeRef(String label) {
        String escaped = (label == null ? "" : label).replace("\\", "\\\\").replace("\"", "\\\"");
        return "$node[\"" + escaped + "\"]";
    }

    /** {@code base.field}, or {@code base["field"]} when the field is not a plain identifier. */
    static String path(String base, String field) {
        if (field == null || field.isEmpty()) {
            return base;
        }
        if (IDENT.matcher(field).matches()) {
            return base + "." + field;
        }
        String escaped = field.replace("\\", "\\\\").replace("\"", "\\\"");
        return base + "[\"" + escaped + "\"]";
    }

    /**
     * An n8n string value as VegaDuta template text: a leading {@code =} (n8n's "this is an
     * expression" marker) is dropped and the data accessors that have a direct spelling are
     * rewritten. A string without the marker is literal text and is returned untouched.
     */
    static String n8nText(String raw) {
        if (raw == null) {
            return null;
        }
        if (!raw.startsWith("=")) {
            return raw;
        }
        String s = raw.substring(1);
        s = rewriteNodeRefs(N8N_NODE_CALL, s);
        s = rewriteNodeRefs(N8N_NODE_INDEX, s);
        s = N8N_INPUT_ITEM.matcher(s).replaceAll(Matcher.quoteReplacement("$json"));
        return s;
    }

    /**
     * True when the value is an n8n expression string (the {@code =} marker) that contains a
     * template span.
     */
    static boolean isN8nExpression(String raw) {
        return raw != null && raw.startsWith("=") && raw.contains("{{");
    }

    private static String rewriteNodeRefs(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(nodeRef(m.group(1))));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
