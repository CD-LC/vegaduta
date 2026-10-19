package com.agenticai.core.workflow.expression;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Runtime value model and conversions. Values are plain Java: null, Boolean, Long, Double, String,
 * List&lt;Object&gt;, Map&lt;String,Object&gt;. Everything here is stateless.
 */
final class Values {

    /**
     * Marker for the {@code $node} root before it is indexed by label. Never escapes evaluation.
     */
    static final Object NODE_ROOT =
            new Object() {
                @Override
                public String toString() {
                    return "$node";
                }
            };

    /**
     * A resolved upstream node: property access reads output/status/summary, everything else reads
     * the parsed value.
     */
    record NodeRef(NodeOutput out) {}

    // Strict: trailing garbage means "not JSON" (so "12 apples" stays a string for $json / $node
    // values).
    private static final ObjectMapper STRICT = JsonMappers.strictTrailingTokens();
    // Same defaults the old renderTemplate used (plain new-style ObjectMapper.readTree), for
    // byte-identical legacy output.
    private static final ObjectMapper LEGACY = JsonMappers.plain();
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");

    private Values() {}

    // ---- JSON boundary

    static Object fromJson(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isIntegralNumber()) {
            return n.canConvertToLong() ? (Object) n.longValue() : (Object) n.doubleValue();
        }
        if (n.isNumber()) {
            return n.doubleValue();
        }
        if (n.isTextual()) {
            return n.textValue();
        }
        if (n.isArray()) {
            List<Object> out = new ArrayList<>(n.size());
            for (JsonNode e : n) {
                out.add(fromJson(e));
            }
            return out;
        }
        if (n.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                out.put(e.getKey(), fromJson(e.getValue()));
            }
            return out;
        }
        return n.toString();
    }

    /** JSON if the whole string is JSON, else the string itself. */
    static Object parseLoose(String s) {
        if (s == null || s.isBlank()) {
            return s == null ? "" : s;
        }
        try {
            JsonNode n = STRICT.readTree(s);
            return n == null || n.isMissingNode() ? s : fromJson(n);
        } catch (Exception e) {
            return s;
        }
    }

    /** The old renderTemplate parse: blank input is an empty object, unparsable input is null. */
    static JsonNode legacyTree(String input) {
        try {
            return LEGACY.readTree(input == null || input.isBlank() ? "{}" : input);
        } catch (Exception e) {
            return null;
        }
    }

    /** Strict JSON parse for parseJson(); throws on invalid input. */
    static Object parseStrict(String s) throws com.fasterxml.jackson.core.JsonProcessingException {
        return fromJson(STRICT.readTree(s));
    }

    /** Coerces caller-supplied context data into the runtime value model. */
    static Object normalize(Object o) {
        if (o == null || o instanceof Boolean || o instanceof String) {
            return o;
        }
        if (o instanceof JsonNode j) {
            return fromJson(j);
        }
        if (o instanceof Long || o instanceof Double) {
            return o;
        }
        if (o instanceof Integer || o instanceof Short || o instanceof Byte) {
            return ((Number) o).longValue();
        }
        if (o instanceof Float f) {
            return f.doubleValue();
        }
        if (o instanceof BigInteger bi) {
            return bi.bitLength() < 64 ? (Object) bi.longValue() : (Object) bi.doubleValue();
        }
        if (o instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().scale() <= 0
                            && bd.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0
                    ? (Object) bd.longValue()
                    : (Object) bd.doubleValue();
        }
        if (o instanceof Number num) {
            return num.doubleValue();
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), normalize(e.getValue()));
            }
            return out;
        }
        if (o instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object e : c) {
                out.add(normalize(e));
            }
            return out;
        }
        return o.toString();
    }

    static Object unwrap(Object v) {
        if (v instanceof NodeRef r) {
            return r.out().value();
        }
        return v == NODE_ROOT ? null : v;
    }

    // ---- numbers

    static Number parseNumber(String s) {
        if (s == null || s.length() > 40) {
            return null;
        }
        String t = s.strip();
        if (t.isEmpty() || !NUMBER.matcher(t).matches()) {
            return null;
        }
        if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                return Double.parseDouble(t);
            }
        }
        return Double.parseDouble(t);
    }

    /** Number, or numeric string coerced to one; null if the value is not numeric. */
    static Number toNumber(Object v) {
        if (v instanceof Long || v instanceof Double) {
            return (Number) v;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return v instanceof String s ? parseNumber(s) : null;
    }

    static boolean isNumber(Object v) {
        return v instanceof Long || v instanceof Double;
    }

    /**
     * Integer-valued doubles print without ".0"; other doubles print in plain (non-exponent)
     * notation.
     */
    static String numStr(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return Double.toString(d);
        }
        if (d == Math.rint(d)) {
            return Math.abs(d) < 1e18 ? Long.toString((long) d) : new BigDecimal(d).toPlainString();
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /** A double as Long when it is an exactly representable integer, else Double. */
    static Object num(double d) {
        return d == Math.rint(d) && Math.abs(d) < 9e15 ? (Object) (long) d : (Object) d;
    }

    // ---- strings / JSON output

    /**
     * Implicit stringification used by built-ins. Containers are capped: arrow-function parameters
     * can share references, so a value with few real nodes can have an astronomically large
     * serialisation.
     */
    static final int IMPLICIT_CAP = 2 * 1024 * 1024;

    static String toStr(Object v) {
        return toStr(v, IMPLICIT_CAP);
    }

    static String toStr(Object v, int cap) {
        v = unwrap(v);
        if (v == null) {
            return "";
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Boolean || v instanceof Long) {
            return v.toString();
        }
        if (v instanceof Double d) {
            return numStr(d);
        }
        return toJson(v, cap);
    }

    static String toJson(Object v) {
        return toJson(v, -1);
    }

    static String toJson(Object v, int cap) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, cap);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int cap) {
        if (cap >= 0 && sb.length() > cap) {
            throw tooLarge(cap);
        }
        v = unwrap(v);
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Double d) {
            sb.append(Double.isNaN(d) || Double.isInfinite(d) ? "null" : numStr(d));
        } else if (v instanceof Number n) {
            sb.append(n.longValue());
        } else if (v instanceof String s) {
            quote(sb, s, cap);
        } else if (v instanceof List<?> l) {
            sb.append('[');
            boolean first = true;
            for (Object e : l) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, e, cap);
            }
            sb.append(']');
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()), cap);
                sb.append(':');
                write(sb, e.getValue(), cap);
            }
            sb.append('}');
        } else {
            quote(sb, v.toString(), cap);
        }
    }

    private static void quote(StringBuilder sb, String s, int cap) {
        if (cap >= 0 && s.length() > cap) {
            throw tooLarge(cap);
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    static ExpressionException tooLarge(int cap) {
        return ExpressionException.of("Result is too large (limit " + cap + " characters)");
    }

    // ---- semantics

    static String typeOf(Object v) {
        v = unwrap(v);
        if (v == null) {
            return "null";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof String) {
            return "string";
        }
        return v instanceof List ? "array" : "object";
    }

    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Long l) {
            return l != 0;
        }
        if (v instanceof Double d) {
            return d != 0 && !Double.isNaN(d);
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        return true;
    }

    /**
     * Strict on type, except number vs numeric string and Long vs Double, which compare
     * numerically.
     */
    static boolean looseEquals(Object a, Object b) {
        return looseEquals(a, b, null);
    }

    /**
     * As above; every container visited costs one budget step so a shared-reference (DAG) value
     * cannot run away.
     */
    static boolean looseEquals(Object a, Object b, Budget budget) {
        if (a == null || b == null) {
            return a == b;
        }
        if (isNumber(a) && isNumber(b)) {
            return numEq((Number) a, (Number) b);
        }
        if (isNumber(a) && b instanceof String s) {
            Number n = parseNumber(s);
            return n != null && numEq((Number) a, n);
        }
        if (a instanceof String s && isNumber(b)) {
            Number n = parseNumber(s);
            return n != null && numEq(n, (Number) b);
        }
        if (a instanceof String x && b instanceof String y) {
            return x.equals(y);
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return x.equals(y);
        }
        if (budget != null && (a instanceof List || a instanceof Map)) {
            budget.tick();
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!looseEquals(x.get(i), y.get(i), budget)) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : x.entrySet()) {
                if (!y.containsKey(e.getKey())
                        || !looseEquals(e.getValue(), y.get(e.getKey()), budget)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean numEq(Number a, Number b) {
        if (a instanceof Long x && b instanceof Long y) {
            return x.longValue() == y.longValue();
        }
        return a.doubleValue() == b.doubleValue();
    }

    /** Ordering for < > <= >=: numbers, numeric strings, strings. Anything else is an error. */
    static int compare(Object a, Object b) {
        if (isNumber(a) && isNumber(b)) {
            return compareNum((Number) a, (Number) b);
        }
        if (a instanceof String x && b instanceof String y) {
            return x.compareTo(y);
        }
        Number x = isNumber(a) ? (Number) a : null;
        Number y = isNumber(b) ? (Number) b : null;
        if (x == null && a instanceof String s && y != null) {
            x = parseNumber(s);
        }
        if (y == null && b instanceof String s && x != null) {
            y = parseNumber(s);
        }
        if (x != null && y != null) {
            return compareNum(x, y);
        }
        throw ExpressionException.of("Cannot compare " + typeOf(a) + " with " + typeOf(b));
    }

    private static int compareNum(Number a, Number b) {
        if (a instanceof Long x && b instanceof Long y) {
            return Long.compare(x, y);
        }
        return Double.compare(a.doubleValue(), b.doubleValue());
    }

    /**
     * Total order used by sort(): nulls last, then booleans, numbers, strings, others; never
     * throws.
     */
    static int sortCompare(Object a, Object b) {
        int ra = rank(a);
        int rb = rank(b);
        if (ra != rb) {
            return Integer.compare(ra, rb);
        }
        return switch (ra) {
            case 1 -> Boolean.compare((Boolean) a, (Boolean) b);
            case 2 -> compareNum((Number) a, (Number) b);
            case 3 -> ((String) a).compareTo((String) b);
            default -> 0;
        };
    }

    private static int rank(Object v) {
        if (v == null) {
            return 5;
        }
        if (v instanceof Boolean) {
            return 1;
        }
        if (v instanceof Number) {
            return 2;
        }
        return v instanceof String ? 3 : 4;
    }
}
