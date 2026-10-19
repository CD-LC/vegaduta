package com.agenticai.core.workflow.expression;

import com.agenticai.core.workflow.expression.Evaluator.Closure;
import java.util.List;
import java.util.Map;

/**
 * Registration and argument-coercion helpers shared by the built-in function categories of {@link
 * BuiltIns}.
 */
final class BuiltInArgs {

    private BuiltInArgs() {}

    static void def(
            Map<String, BuiltIn> m,
            String name,
            String cat,
            int min,
            int max,
            String sig,
            String doc,
            String example,
            String result,
            BuiltIn.Impl impl) {
        m.put(name, new BuiltIn(name, cat, min, max, sig, doc, example, result, impl, null));
    }

    static void lazy(
            Map<String, BuiltIn> m,
            String name,
            String cat,
            int min,
            int max,
            String sig,
            String doc,
            String example,
            String result,
            BuiltIn.LazyImpl impl) {
        m.put(name, new BuiltIn(name, cat, min, max, sig, doc, example, result, null, impl));
    }

    // ---- argument helpers

    static ExpressionException err(String fn, String msg) {
        return ExpressionException.of(fn + "(): " + msg);
    }

    static Object get(List<Object> a, int i) {
        Object v = a.get(i);
        if (v instanceof Closure) {
            throw ExpressionException.of("A function argument (x => ...) is not allowed here");
        }
        return v;
    }

    static String str(List<Object> a, int i) {
        return Values.toStr(get(a, i));
    }

    static boolean has(List<Object> a, int i) {
        return a.size() > i && a.get(i) != null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(List<Object> a, int i, String fn) {
        Object v = get(a, i);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw err(fn, "argument " + (i + 1) + " must be an array, got " + Values.typeOf(v));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(List<Object> a, int i, String fn) {
        Object v = get(a, i);
        if (v == null) {
            return Map.of();
        }
        if (v instanceof Map<?, ?> mp) {
            return (Map<String, Object>) mp;
        }
        throw err(fn, "argument " + (i + 1) + " must be an object, got " + Values.typeOf(v));
    }

    static Number num(List<Object> a, int i, String fn) {
        Object v = get(a, i);
        Number n = Values.toNumber(v);
        if (n == null || (n instanceof Double d && (d.isNaN() || d.isInfinite()))) {
            throw err(fn, "argument " + (i + 1) + " must be a number, got " + Values.typeOf(v));
        }
        return n;
    }

    static long lng(List<Object> a, int i, String fn) {
        return (long) num(a, i, fn).doubleValue();
    }

    static int clampInt(long v) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    static Object fnArg(List<Object> a, int i, String fn) {
        Object f = a.get(i);
        if (!(f instanceof Closure)) {
            throw err(fn, "argument " + (i + 1) + " must be a function like x => x.price");
        }
        return f;
    }
}
