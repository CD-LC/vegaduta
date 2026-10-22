package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportJson;
import com.agenticai.core.agents.importer.AgentImportText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Dify variable references and conditions, rewritten into VegaDuta expressions. Authored from the
 * observed public DSL format ({@code {{#nodeId.var#}}} in text, {@code [nodeId, var]} selectors in
 * structured fields); no Dify code is used.
 *
 * <ul>
 *   <li>{@code {{#100.question#}}} becomes {@code {{ $node["Start"].question }}} (the node is
 *       addressed by its title, the documented VegaDuta way to read an earlier node's output);
 *   <li>{@code sys.query} becomes {@code $trigger}; an iteration's {@code item} / {@code index}
 *       become {@code $item} / {@code $index};
 *   <li>an environment variable becomes {@code $vars.NAME}, or - for a secret variable inside an
 *       HTTP node - a {@code {{secret.NAME}}} vault reference;
 *   <li>conversation variables, other {@code sys.*} values and references to unknown nodes have no
 *       equivalent: they are left exactly as written and reported to the {@link Sink}.
 * </ul>
 */
final class DifyReferences {

    /**
     * What the caller wants to know while text is rewritten: that references were seen, and which
     * could not be.
     */
    interface Sink {
        void refsSeen();

        void unconverted(String note);

        boolean inHttp();
    }

    private static final Pattern REF = Pattern.compile("\\{\\{#([A-Za-z0-9_.\\-]{1,200})#}}");
    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    private final ObjectMapper mapper;
    private final Map<String, String> titles;
    private final Set<String> iterationIds;
    private final Map<String, Boolean> envSecret;

    /**
     * The maps are shared with the mapper and filled in as it reads the graph; they are only read
     * here.
     */
    DifyReferences(
            ObjectMapper mapper,
            Map<String, String> titles,
            Set<String> iterationIds,
            Map<String, Boolean> envSecret) {
        this.mapper = mapper;
        this.titles = titles;
        this.iterationIds = iterationIds;
        this.envSecret = envSecret;
    }

    /**
     * Rewrites every {@code {{#a.b#}}} in {@code raw}; a reference with no equivalent is left as
     * written and noted.
     */
    String rewrite(Sink sink, String raw) {
        Matcher m = REF.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            List<String> parts = new ArrayList<>(List.of(m.group(1).split("\\.")));
            String replacement = null;
            if (parts.size() >= 2) {
                String expression = expressionFor(parts, sink.inHttp());
                if (expression != null) {
                    sink.refsSeen();
                    replacement =
                            expression.startsWith("secret:")
                                    ? "{{secret." + expression.substring("secret:".length()) + "}}"
                                    : "{{ " + expression + " }}";
                }
            }
            if (replacement == null) {
                sink.unconverted(
                        "The reference {{#"
                                + AgentImportText.inline(m.group(1), 60)
                                + "#}} has no equivalent here and was left as written.");
                replacement = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * A Dify {@code [nodeId, var, ...]} selector as a bare VegaDuta expression, or null when it
     * cannot be expressed.
     */
    String selectorExpression(Sink sink, JsonNode selector) {
        if (selector == null || !selector.isArray() || selector.size() < 2) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode p : selector) {
            if (!p.isString() && !p.isIntegralNumber()) {
                return null;
            }
            parts.add(p.asString());
        }
        sink.refsSeen();
        return expressionFor(parts, false);
    }

    /**
     * One If/Else condition as a bare expression, or null when its operator or an operand cannot be
     * expressed.
     */
    String condition(Sink sink, JsonNode cond) {
        String op = AgentImportJson.text(cond, "comparison_operator");
        if (op == null) {
            return null;
        }
        String left = selectorExpression(sink, cond.get("variable_selector"));
        if (left == null) {
            return null;
        }
        boolean unary = Set.of("empty", "not empty", "null", "not null").contains(op);
        String right = "null";
        if (!unary) {
            right = valueExpression(sink, cond.get("value"), AgentImportJson.text(cond, "varType"));
            if (right == null) {
                return null;
            }
        }
        return switch (op) {
            case "contains" -> "contains(" + left + ", " + right + ")";
            case "not contains" -> "!contains(" + left + ", " + right + ")";
            case "start with" -> "startsWith(" + left + ", " + right + ")";
            case "end with" -> "endsWith(" + left + ", " + right + ")";
            case "is", "=" -> left + " == " + right;
            case "is not", "≠" -> left + " != " + right;
            case "empty" -> "isEmpty(" + left + ")";
            case "not empty" -> "!isEmpty(" + left + ")";
            case ">" -> left + " > " + right;
            case "<" -> left + " < " + right;
            case "≥" -> left + " >= " + right;
            case "≤" -> left + " <= " + right;
            case "null" -> left + " == null";
            case "not null" -> left + " != null";
            default -> null;
        };
    }

    private String valueExpression(Sink sink, JsonNode value, String varType) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isBoolean()) {
            return value.asBoolean() ? "true" : "false";
        }
        if (value.isNumber()) {
            return value.asString();
        }
        String raw = value.asString();
        Matcher whole = REF.matcher(raw.strip());
        if (whole.matches()) {
            return selectorExpression(sink, selectorFrom(whole.group(1)));
        }
        if (REF.matcher(raw).find()) {
            return null;
        }
        if ("number".equals(varType) && NUMERIC.matcher(raw.strip()).matches()) {
            return raw.strip();
        }
        return PortableExpressions.quote(raw);
    }

    private JsonNode selectorFrom(String dotted) {
        ArrayNode array = mapper.createArrayNode();
        for (String part : dotted.split("\\.")) {
            array.add(part);
        }
        return array;
    }

    /**
     * The expression a reference resolves to, or null when it has no equivalent (conversation, most
     * sys.*).
     */
    String expressionFor(List<String> parts, boolean inHttp) {
        String first = parts.get(0);
        if ("sys".equals(first)) {
            return "query".equals(parts.get(1)) ? "$trigger" : null;
        }
        if ("env".equals(first)) {
            String name = parts.get(1);
            return Boolean.TRUE.equals(envSecret.get(name)) && inHttp
                    ? "secret:" + name
                    : PortableExpressions.path("$vars", name);
        }
        if ("conversation".equals(first)) {
            return null;
        }
        if (iterationIds.contains(first)) {
            if ("item".equals(parts.get(1))) {
                return "$item";
            }
            if ("index".equals(parts.get(1))) {
                return "$index";
            }
        }
        String title = titles.get(first);
        if (title == null) {
            return null;
        }
        String expression = PortableExpressions.nodeRef(title);
        for (int i = 1; i < parts.size(); i++) {
            expression = PortableExpressions.path(expression, parts.get(i));
        }
        return expression;
    }
}
