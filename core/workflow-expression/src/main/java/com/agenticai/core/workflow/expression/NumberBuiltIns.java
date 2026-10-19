package com.agenticai.core.workflow.expression;

import static com.agenticai.core.workflow.expression.BuiltInArgs.arr;
import static com.agenticai.core.workflow.expression.BuiltInArgs.clampInt;
import static com.agenticai.core.workflow.expression.BuiltInArgs.def;
import static com.agenticai.core.workflow.expression.BuiltInArgs.err;
import static com.agenticai.core.workflow.expression.BuiltInArgs.fnArg;
import static com.agenticai.core.workflow.expression.BuiltInArgs.get;
import static com.agenticai.core.workflow.expression.BuiltInArgs.has;
import static com.agenticai.core.workflow.expression.BuiltInArgs.lng;
import static com.agenticai.core.workflow.expression.BuiltInArgs.num;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The Number built-ins of {@link BuiltIns}: rounding, min/max, sum/avg and formatting. */
final class NumberBuiltIns {

    private NumberBuiltIns() {}

    static void register(Map<String, BuiltIn> m) {
        String c = "Number";
        def(
                m,
                "round",
                c,
                1,
                2,
                "round(number, digits = 0)",
                "Rounds to a number of decimal places (halves round away from zero).",
                "round(2.567, 2)",
                "2.57",
                (ev, a) -> {
                    Number n = num(a, 0, "round");
                    int digits =
                            has(a, 1) ? clampInt(Math.max(0, Math.min(15, lng(a, 1, "round")))) : 0;
                    if (n instanceof Long && digits == 0) {
                        return n;
                    }
                    double r =
                            BigDecimal.valueOf(n.doubleValue())
                                    .setScale(digits, RoundingMode.HALF_UP)
                                    .doubleValue();
                    return digits == 0 ? Values.num(r) : (Object) r;
                });
        def(
                m,
                "floor",
                c,
                1,
                1,
                "floor(number)",
                "Rounds down to the nearest integer.",
                "floor(2.9)",
                "2",
                (ev, a) -> {
                    Number n = num(a, 0, "floor");
                    return n instanceof Long ? n : Values.num(Math.floor(n.doubleValue()));
                });
        def(
                m,
                "ceil",
                c,
                1,
                1,
                "ceil(number)",
                "Rounds up to the nearest integer.",
                "ceil(2.1)",
                "3",
                (ev, a) -> {
                    Number n = num(a, 0, "ceil");
                    return n instanceof Long ? n : Values.num(Math.ceil(n.doubleValue()));
                });
        def(
                m,
                "abs",
                c,
                1,
                1,
                "abs(number)",
                "Absolute value.",
                "abs(-5)",
                "5",
                (ev, a) -> {
                    Number n = num(a, 0, "abs");
                    if (n instanceof Long l) {
                        return l == Long.MIN_VALUE
                                ? (Object) Math.abs((double) l)
                                : (Object) Math.abs(l);
                    }
                    return Math.abs(n.doubleValue());
                });
        def(
                m,
                "min",
                c,
                1,
                -1,
                "min(a, b, ...) or min(array)",
                "Smallest of the given numbers or of one array.",
                "min(3, 1, 2)",
                "1",
                (ev, a) -> extreme(a, "min", -1));
        def(
                m,
                "max",
                c,
                1,
                -1,
                "max(a, b, ...) or max(array)",
                "Largest of the given numbers or of one array.",
                "max(3, 1, 2)",
                "3",
                (ev, a) -> extreme(a, "max", 1));
        def(
                m,
                "sum",
                c,
                1,
                2,
                "sum(array, fn = x => x)",
                "Adds up an array of numbers (null items are skipped); an optional function picks the number from each item.",
                "sum([1, 2, 3])",
                "6",
                (ev, a) -> {
                    Number acc = 0L;
                    for (Object v : values(ev, a, "sum")) {
                        acc = add(acc, toNum(v, "sum"));
                    }
                    return acc;
                });
        def(
                m,
                "avg",
                c,
                1,
                2,
                "avg(array, fn = x => x)",
                "Average of an array of numbers (null items are skipped); null for an empty array.",
                "avg([1, 2, 3])",
                "2",
                (ev, a) -> {
                    Number acc = 0L;
                    long count = 0;
                    for (Object v : values(ev, a, "avg")) {
                        acc = add(acc, toNum(v, "avg"));
                        count++;
                    }
                    return count == 0 ? null : Values.num(acc.doubleValue() / count);
                });
        def(
                m,
                "toNumber",
                c,
                1,
                1,
                "toNumber(value)",
                "Converts a number or numeric text to a number; null if it is not numeric.",
                "toNumber('42')",
                "42",
                (ev, a) -> Values.toNumber(get(a, 0)));
        def(
                m,
                "toFixed",
                c,
                2,
                2,
                "toFixed(number, digits)",
                "Formats a number with a fixed number of decimals, as text.",
                "toFixed(3.14159, 2)",
                "\"3.14\"",
                (ev, a) -> {
                    Number n = num(a, 0, "toFixed");
                    int digits = clampInt(Math.max(0, Math.min(20, lng(a, 1, "toFixed"))));
                    BigDecimal bd =
                            n instanceof Long l
                                    ? BigDecimal.valueOf(l)
                                    : BigDecimal.valueOf(n.doubleValue());
                    return bd.setScale(digits, RoundingMode.HALF_UP).toPlainString();
                });
    }

    private static Object extreme(List<Object> a, String fn, int sign) {
        List<Object> vals = a;
        if (a.size() == 1 && get(a, 0) instanceof List<?> l) {
            vals = new ArrayList<>(l);
        }
        Number best = null;
        for (int i = 0; i < vals.size(); i++) {
            Number n = toNum(vals.get(i), fn);
            if (best == null || Values.compare(n, best) * sign > 0) {
                best = n;
            }
        }
        return best;
    }

    private static Number toNum(Object v, String fn) {
        Number n = Values.toNumber(v);
        if (n == null) {
            throw err(fn, "expected numbers but found " + Values.typeOf(v));
        }
        return n;
    }

    /** Items of arg 0 (optionally mapped through the function in arg 1), nulls dropped. */
    private static List<Object> values(Evaluator ev, List<Object> a, String fn) {
        List<Object> l = arr(a, 0, fn);
        Object f = a.size() > 1 ? fnArg(a, 1, fn) : null;
        List<Object> out = new ArrayList<>(l.size());
        for (Object item : l) {
            Object v = f == null ? item : ev.call(f, item);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    private static Number add(Number x, Number y) {
        if (x instanceof Long p && y instanceof Long q) {
            long r = p + q;
            if (((p ^ r) & (q ^ r)) >= 0) {
                return r;
            }
        }
        return x.doubleValue() + y.doubleValue();
    }
}
