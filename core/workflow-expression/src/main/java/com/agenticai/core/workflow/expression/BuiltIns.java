package com.agenticai.core.workflow.expression;

import static com.agenticai.core.workflow.expression.BuiltInArgs.def;
import static com.agenticai.core.workflow.expression.BuiltInArgs.err;
import static com.agenticai.core.workflow.expression.BuiltInArgs.get;
import static com.agenticai.core.workflow.expression.BuiltInArgs.lazy;
import static com.agenticai.core.workflow.expression.BuiltInArgs.str;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The fixed built-in function registry (spec 3.4): a single immutable name -> definition map that
 * also drives autocomplete and the docs page. Nothing here reaches reflection, I/O or the network.
 */
public final class BuiltIns {

    private static final Map<String, BuiltIn> REGISTRY = build();

    private BuiltIns() {}

    public static Map<String, BuiltIn> registry() {
        return REGISTRY;
    }

    public static Optional<BuiltIn> find(String name) {
        return Optional.ofNullable(REGISTRY.get(name));
    }

    /**
     * Closest registered name within a small edit distance, for "did you mean" hints; null if none.
     */
    public static String suggest(String name) {
        return nearest(name, REGISTRY.keySet());
    }

    static String nearest(String name, Collection<String> candidates) {
        String best = null;
        int bestDist = Math.max(1, Math.min(3, name.length() / 3));
        for (String candidate : candidates) {
            int d = distance(name.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
            if (d <= bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        return best;
    }

    static String unknownFunctionMessage(String name) {
        String s = suggest(name);
        return "Unknown function '" + name + "'" + (s == null ? "" : ". Did you mean '" + s + "'?");
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    // ---- registry construction

    private static Map<String, BuiltIn> build() {
        Map<String, BuiltIn> m = new LinkedHashMap<>();
        StringBuiltIns.register(m);
        NumberBuiltIns.register(m);
        ArrayBuiltIns.register(m);
        ObjectBuiltIns.register(m);
        DateBuiltIns.register(m);
        types(m);
        logic(m);
        return Collections.unmodifiableMap(m);
    }

    // ---- type / JSON

    private static void types(Map<String, BuiltIn> m) {
        String c = "Type";
        def(
                m,
                "toString",
                c,
                1,
                1,
                "toString(value)",
                "Converts a value to text (null becomes empty text, objects and arrays become JSON).",
                "toString(12)",
                "\"12\"",
                (ev, a) -> ev.str(get(a, 0)));
        def(
                m,
                "toBoolean",
                c,
                1,
                1,
                "toBoolean(value)",
                "Converts to true/false: \"true\"/\"yes\"/\"1\" are true, \"false\"/\"no\"/\"0\"/empty are false; numbers are true unless 0.",
                "toBoolean('true')",
                "true",
                (ev, a) -> {
                    Object v = get(a, 0);
                    if (v instanceof String s) {
                        return switch (s.strip().toLowerCase(Locale.ROOT)) {
                            case "true", "yes", "y", "1", "on" -> true;
                            case "false", "no", "n", "0", "off", "", "null" -> false;
                            default -> true;
                        };
                    }
                    return Values.truthy(v);
                });
        def(
                m,
                "parseJson",
                c,
                1,
                1,
                "parseJson(text)",
                "Parses JSON text into a value; an error if it is not valid JSON.",
                "parseJson('{\"a\":1}')",
                "{\"a\":1}",
                (ev, a) -> {
                    try {
                        return Values.parseStrict(str(a, 0));
                    } catch (tools.jackson.core.JacksonException e) {
                        throw err("parseJson", "invalid JSON");
                    }
                });
        def(
                m,
                "toJson",
                c,
                1,
                1,
                "toJson(value)",
                "Serialises a value to compact JSON text.",
                "toJson({a: 1})",
                "\"{\\\"a\\\":1}\"",
                (ev, a) -> Values.toJson(get(a, 0), ev.limits().maxResultChars()));
        def(
                m,
                "typeOf",
                c,
                1,
                1,
                "typeOf(value)",
                "One of null, boolean, number, string, array, object.",
                "typeOf([1])",
                "\"array\"",
                (ev, a) -> Values.typeOf(get(a, 0)));
        def(
                m,
                "isEmpty",
                c,
                1,
                1,
                "isEmpty(value)",
                "True for null, empty text, empty arrays and empty objects.",
                "isEmpty('')",
                "true",
                (ev, a) -> {
                    Object v = get(a, 0);
                    return v == null
                            || (v instanceof String s && s.isEmpty())
                            || (v instanceof List<?> l && l.isEmpty())
                            || (v instanceof Map<?, ?> mp && mp.isEmpty());
                });
        def(
                m,
                "isNumber",
                c,
                1,
                1,
                "isNumber(value)",
                "True for numbers and numeric text.",
                "isNumber('12')",
                "true",
                (ev, a) -> {
                    Number n = Values.toNumber(get(a, 0));
                    return n != null && !(n instanceof Double d && (d.isNaN() || d.isInfinite()));
                });
    }

    // ---- logic

    private static void logic(Map<String, BuiltIn> m) {
        String c = "Logic";
        lazy(
                m,
                "if",
                c,
                3,
                3,
                "if(condition, then, else)",
                "Evaluates only the branch that is chosen.",
                "if(1 > 0, 'yes', 'no')",
                "\"yes\"",
                (ev, args, scope) ->
                        Values.truthy(ev.eval(args.get(0), scope))
                                ? ev.eval(args.get(1), scope)
                                : ev.eval(args.get(2), scope));
        lazy(
                m,
                "coalesce",
                c,
                1,
                -1,
                "coalesce(a, b, ...)",
                "The first argument that is not null; later ones are not evaluated.",
                "coalesce(null, 'fallback')",
                "\"fallback\"",
                (ev, args, scope) -> {
                    for (Node n : args) {
                        Object v = ev.eval(n, scope);
                        if (v != null) {
                            return v;
                        }
                    }
                    return null;
                });
    }
}
