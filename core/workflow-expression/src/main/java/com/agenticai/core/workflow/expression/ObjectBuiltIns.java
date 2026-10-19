package com.agenticai.core.workflow.expression;

import static com.agenticai.core.workflow.expression.BuiltInArgs.def;
import static com.agenticai.core.workflow.expression.BuiltInArgs.get;
import static com.agenticai.core.workflow.expression.BuiltInArgs.obj;
import static com.agenticai.core.workflow.expression.BuiltInArgs.str;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Object built-ins of {@link BuiltIns}, and the property-path resolver they share with {@code
 * pluck}.
 */
final class ObjectBuiltIns {

    private ObjectBuiltIns() {}

    static void register(Map<String, BuiltIn> m) {
        String c = "Object";
        def(
                m,
                "keys",
                c,
                1,
                1,
                "keys(object)",
                "The object's keys as an array.",
                "keys({a: 1, b: 2})",
                "[\"a\",\"b\"]",
                (ev, a) -> new ArrayList<Object>(obj(a, 0, "keys").keySet()));
        def(
                m,
                "values",
                c,
                1,
                1,
                "values(object)",
                "The object's values as an array.",
                "values({a: 1, b: 2})",
                "[1,2]",
                (ev, a) -> new ArrayList<>(obj(a, 0, "values").values()));
        def(
                m,
                "entries",
                c,
                1,
                1,
                "entries(object)",
                "The object as an array of [key, value] pairs.",
                "entries({a: 1})",
                "[[\"a\",1]]",
                (ev, a) -> {
                    List<Object> out = new ArrayList<>();
                    for (Map.Entry<String, Object> e : obj(a, 0, "entries").entrySet()) {
                        List<Object> pair = new ArrayList<>(2);
                        pair.add(e.getKey());
                        pair.add(e.getValue());
                        out.add(pair);
                    }
                    return out;
                });
        def(
                m,
                "merge",
                c,
                1,
                -1,
                "merge(a, b, ...)",
                "Shallow-merges objects left to right; later keys win.",
                "merge({a: 1}, {b: 2})",
                "{\"a\":1,\"b\":2}",
                (ev, a) -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    for (int i = 0; i < a.size(); i++) {
                        out.putAll(obj(a, i, "merge"));
                    }
                    return out;
                });
        def(
                m,
                "pick",
                c,
                2,
                -1,
                "pick(object, key, ...)",
                "A copy of the object with only the listed keys (keys may also be one array).",
                "pick({a: 1, b: 2}, 'a')",
                "{\"a\":1}",
                (ev, a) -> {
                    Map<String, Object> src = obj(a, 0, "pick");
                    Map<String, Object> out = new LinkedHashMap<>();
                    for (String k : keyNames(a)) {
                        if (src.containsKey(k)) {
                            out.put(k, src.get(k));
                        }
                    }
                    return out;
                });
        def(
                m,
                "omit",
                c,
                2,
                -1,
                "omit(object, key, ...)",
                "A copy of the object without the listed keys (keys may also be one array).",
                "omit({a: 1, b: 2}, 'a')",
                "{\"b\":2}",
                (ev, a) -> {
                    Map<String, Object> out = new LinkedHashMap<>(obj(a, 0, "omit"));
                    for (String k : keyNames(a)) {
                        out.remove(k);
                    }
                    return out;
                });
        def(
                m,
                "get",
                c,
                2,
                3,
                "get(value, path, default = null)",
                "Reads a property path like \"a.b[0].c\" (negative indexes count from the end); returns the default when missing.",
                "get({a: [{b: 5}]}, 'a[0].b', 0)",
                "5",
                (ev, a) -> {
                    Object v = resolvePath(get(a, 0), str(a, 1));
                    return v != null ? v : (a.size() > 2 ? get(a, 2) : null);
                });
    }

    private static List<String> keyNames(List<Object> a) {
        List<String> names = new ArrayList<>();
        for (int i = 1; i < a.size(); i++) {
            Object k = get(a, i);
            if (k instanceof List<?> l) {
                for (Object e : l) {
                    names.add(Values.toStr(e));
                }
            } else {
                names.add(Values.toStr(k));
            }
        }
        return names;
    }

    /** Resolves "a.b[0]['c'][-1]" against plain values; a missing step yields null. */
    static Object resolvePath(Object base, String path) {
        Object cur = Values.unwrap(base);
        int i = 0;
        int n = path.length();
        while (i < n && cur != null) {
            char ch = path.charAt(i);
            if (ch == '.') {
                i++;
            } else if (ch == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) {
                    throw ExpressionException.of("Invalid path \"" + path + "\": missing ]");
                }
                String inner = path.substring(i + 1, close).trim();
                if (inner.length() >= 2
                        && (inner.charAt(0) == '"' || inner.charAt(0) == '\'')
                        && inner.charAt(inner.length() - 1) == inner.charAt(0)) {
                    cur = child(cur, inner.substring(1, inner.length() - 1));
                } else {
                    cur = child(cur, inner);
                }
                i = close + 1;
            } else {
                int j = i;
                while (j < n && path.charAt(j) != '.' && path.charAt(j) != '[') {
                    j++;
                }
                cur = child(cur, path.substring(i, j));
                i = j;
            }
        }
        return cur;
    }

    private static Object child(Object cur, String key) {
        if (cur instanceof Map<?, ?> mp) {
            return mp.get(key);
        }
        if (cur instanceof List<?> l) {
            Number idx = Values.parseNumber(key);
            if (idx instanceof Long li) {
                long k = li < 0 ? li + l.size() : li;
                return k >= 0 && k < l.size() ? l.get((int) k) : null;
            }
        }
        return null;
    }
}
