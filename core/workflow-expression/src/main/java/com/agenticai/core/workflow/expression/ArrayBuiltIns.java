package com.agenticai.core.workflow.expression;

import static com.agenticai.core.workflow.expression.BuiltInArgs.arr;
import static com.agenticai.core.workflow.expression.BuiltInArgs.clampInt;
import static com.agenticai.core.workflow.expression.BuiltInArgs.def;
import static com.agenticai.core.workflow.expression.BuiltInArgs.err;
import static com.agenticai.core.workflow.expression.BuiltInArgs.fnArg;
import static com.agenticai.core.workflow.expression.BuiltInArgs.get;
import static com.agenticai.core.workflow.expression.BuiltInArgs.has;
import static com.agenticai.core.workflow.expression.BuiltInArgs.lng;
import static com.agenticai.core.workflow.expression.BuiltInArgs.str;

import com.agenticai.core.workflow.expression.Evaluator.Closure;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Array built-ins of {@link BuiltIns}: higher-order functions, slicing, sorting and flattening.
 */
final class ArrayBuiltIns {

    private ArrayBuiltIns() {}

    static void register(Map<String, BuiltIn> m) {
        String c = "Array";
        def(
                m,
                "map",
                c,
                2,
                2,
                "map(array, fn)",
                "Transforms every item with a function.",
                "map([1, 2, 3], x => x * 2)",
                "[2,4,6]",
                (ev, a) -> {
                    List<Object> l = arr(a, 0, "map");
                    Object f = fnArg(a, 1, "map");
                    List<Object> out = new ArrayList<>(l.size());
                    for (Object item : l) {
                        out.add(ev.call(f, item));
                    }
                    return out;
                });
        def(
                m,
                "filter",
                c,
                2,
                2,
                "filter(array, fn)",
                "Keeps the items for which the function is truthy.",
                "filter([1, 2, 3, 4], x => x > 2)",
                "[3,4]",
                (ev, a) -> {
                    List<Object> l = arr(a, 0, "filter");
                    Object f = fnArg(a, 1, "filter");
                    List<Object> out = new ArrayList<>();
                    for (Object item : l) {
                        if (Values.truthy(ev.call(f, item))) {
                            out.add(item);
                        }
                    }
                    return out;
                });
        def(
                m,
                "find",
                c,
                2,
                2,
                "find(array, fn)",
                "First item for which the function is truthy, or null.",
                "find([1, 2, 3], x => x > 1)",
                "2",
                (ev, a) -> {
                    List<Object> l = arr(a, 0, "find");
                    Object f = fnArg(a, 1, "find");
                    for (Object item : l) {
                        if (Values.truthy(ev.call(f, item))) {
                            return item;
                        }
                    }
                    return null;
                });
        def(
                m,
                "some",
                c,
                2,
                2,
                "some(array, fn)",
                "True if the function is truthy for at least one item.",
                "some([1, 2, 3], x => x > 2)",
                "true",
                (ev, a) -> {
                    List<Object> l = arr(a, 0, "some");
                    Object f = fnArg(a, 1, "some");
                    for (Object item : l) {
                        if (Values.truthy(ev.call(f, item))) {
                            return true;
                        }
                    }
                    return false;
                });
        def(
                m,
                "every",
                c,
                2,
                2,
                "every(array, fn)",
                "True if the function is truthy for every item (true for an empty array).",
                "every([1, 2, 3], x => x > 0)",
                "true",
                (ev, a) -> {
                    List<Object> l = arr(a, 0, "every");
                    Object f = fnArg(a, 1, "every");
                    for (Object item : l) {
                        if (!Values.truthy(ev.call(f, item))) {
                            return false;
                        }
                    }
                    return true;
                });
        def(
                m,
                "first",
                c,
                1,
                1,
                "first(array)",
                "First item, or null for an empty array.",
                "first([7, 8])",
                "7",
                (ev, a) -> {
                    List<Object> l = arr(a, 0, "first");
                    return l.isEmpty() ? null : l.get(0);
                });
        def(
                m,
                "last",
                c,
                1,
                1,
                "last(array)",
                "Last item, or null for an empty array.",
                "last([7, 8])",
                "8",
                (ev, a) -> {
                    List<Object> l = arr(a, 0, "last");
                    return l.isEmpty() ? null : l.get(l.size() - 1);
                });
        def(
                m,
                "slice",
                c,
                2,
                3,
                "slice(arrayOrText, start, end = length)",
                "Part of an array or text between two positions; negative positions count from the end.",
                "slice([1, 2, 3, 4], 1, 3)",
                "[2,3]",
                (ev, a) -> {
                    Object x = get(a, 0);
                    int len;
                    if (x instanceof String s) {
                        len = s.length();
                    } else if (x == null) {
                        return List.of();
                    } else if (x instanceof List<?> l) {
                        len = l.size();
                    } else {
                        throw err(
                                "slice",
                                "argument 1 must be an array or text, got " + Values.typeOf(x));
                    }
                    int st = bound(lng(a, 1, "slice"), len);
                    int en = has(a, 2) ? bound(lng(a, 2, "slice"), len) : len;
                    if (x instanceof String s) {
                        return en <= st ? "" : s.substring(st, en);
                    }
                    return en <= st
                            ? new ArrayList<>()
                            : new ArrayList<>(((List<?>) x).subList(st, en));
                });
        def(
                m,
                "sort",
                c,
                1,
                3,
                "sort(array, keyFn or \"asc\"/\"desc\", order = \"asc\")",
                "Sorts a copy of the array (stable). Give a function to sort by a derived key; nulls sort last.",
                "sort([3, 1, 2])",
                "[1,2,3]",
                (ev, a) -> {
                    List<Object> l = arr(a, 0, "sort");
                    Object second = a.size() > 1 ? a.get(1) : null;
                    boolean desc = false;
                    Object keyFn = null;
                    if (second instanceof Closure) {
                        keyFn = second;
                    } else if (second != null) {
                        desc = "desc".equalsIgnoreCase(Values.toStr(second));
                    }
                    if (has(a, 2)) {
                        desc = "desc".equalsIgnoreCase(str(a, 2));
                    }
                    List<Object[]> pairs = new ArrayList<>(l.size());
                    for (Object item : l) {
                        pairs.add(new Object[] {keyFn == null ? item : ev.call(keyFn, item), item});
                    }
                    Comparator<Object[]> cmp = (p, q) -> Values.sortCompare(p[0], q[0]);
                    pairs.sort(desc ? cmp.reversed() : cmp);
                    List<Object> out = new ArrayList<>(l.size());
                    for (Object[] p : pairs) {
                        out.add(p[1]);
                    }
                    return out;
                });
        def(
                m,
                "unique",
                c,
                1,
                1,
                "unique(array)",
                "Removes duplicate items, keeping the first of each.",
                "unique([1, 2, 2, 3])",
                "[1,2,3]",
                (ev, a) -> {
                    Map<String, Object> seen = new LinkedHashMap<>();
                    for (Object item : arr(a, 0, "unique")) {
                        Object key = item instanceof Number n ? Values.num(n.doubleValue()) : item;
                        ev.budget().tick();
                        seen.putIfAbsent(Values.toJson(key, ev.limits().maxResultChars()), item);
                    }
                    return new ArrayList<>(seen.values());
                });
        def(
                m,
                "flatten",
                c,
                1,
                2,
                "flatten(array, depth = 1)",
                "Flattens nested arrays by the given depth.",
                "flatten([[1], [2, [3]]])",
                "[1,2,[3]]",
                (ev, a) -> {
                    List<Object> out = new ArrayList<>();
                    flatten(
                            ev,
                            arr(a, 0, "flatten"),
                            has(a, 1) ? clampInt(Math.min(64, lng(a, 1, "flatten"))) : 1,
                            out,
                            new long[1]);
                    return out;
                });
        def(
                m,
                "reverse",
                c,
                1,
                1,
                "reverse(arrayOrText)",
                "Reverses an array or text (returns a copy).",
                "reverse([1, 2, 3])",
                "[3,2,1]",
                (ev, a) -> {
                    Object x = get(a, 0);
                    if (x instanceof String s) {
                        return new StringBuilder(s).reverse().toString();
                    }
                    List<Object> out = new ArrayList<>(arr(a, 0, "reverse"));
                    Collections.reverse(out);
                    return out;
                });
        def(
                m,
                "count",
                c,
                1,
                2,
                "count(array, fn)",
                "Number of items, or of items for which the function is truthy.",
                "count([1, 2, 3, 4], x => x % 2 == 0)",
                "2",
                (ev, a) -> {
                    List<Object> l = arr(a, 0, "count");
                    if (a.size() < 2) {
                        return (long) l.size();
                    }
                    Object f = fnArg(a, 1, "count");
                    long n = 0;
                    for (Object item : l) {
                        if (Values.truthy(ev.call(f, item))) {
                            n++;
                        }
                    }
                    return n;
                });
        def(
                m,
                "pluck",
                c,
                2,
                2,
                "pluck(array, path)",
                "Reads a property path (like \"a.b[0]\") from every item.",
                "pluck([{a: 1}, {a: 2}], 'a')",
                "[1,2]",
                (ev, a) -> {
                    List<Object> l = arr(a, 0, "pluck");
                    String path = str(a, 1);
                    List<Object> out = new ArrayList<>(l.size());
                    for (Object item : l) {
                        out.add(ObjectBuiltIns.resolvePath(item, path));
                    }
                    return out;
                });
    }

    private static int bound(long i, int len) {
        long v = i < 0 ? len + i : i;
        return (int) Math.max(0, Math.min(len, v));
    }

    /**
     * {@code visited} counts every element walked, added or not: a shared-reference (DAG) value of
     * empty arrays adds nothing yet can have 2^k paths, so only the wall clock can stop it.
     */
    private static void flatten(
            Evaluator ev, List<?> in, int depth, List<Object> out, long[] visited) {
        for (Object e : in) {
            if ((++visited[0] & 255) == 0) {
                ev.budget().checkTime();
            }
            if (e instanceof List<?> nested && depth > 0) {
                flatten(ev, nested, depth - 1, out, visited);
            } else {
                out.add(e);
                if ((out.size() & 1023) == 0) {
                    ev.checkItems(out.size());
                }
            }
        }
    }
}
