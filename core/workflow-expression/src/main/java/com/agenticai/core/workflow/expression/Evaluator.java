package com.agenticai.core.workflow.expression;

import com.agenticai.core.workflow.expression.Values.NodeRef;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tree-walking interpreter. One instance per public engine call (it owns that call's step/time
 * budget and caches), so instances are single-threaded and cheap. There is deliberately no path
 * from an expression to a Java object: property access only ever reads Map keys, list slots and
 * string length, and functions come from the fixed {@link BuiltIns} registry.
 */
final class Evaluator {

    static final Object NOT_FOUND = new Object();

    /** Lexical scope for arrow-function parameters. */
    static final class Scope {
        final String name;
        final Object value;
        final Scope parent;

        Scope(String name, Object value, Scope parent) {
            this.name = name;
            this.value = value;
            this.parent = parent;
        }
    }

    /** An arrow function captured with its scope; only ever handed to built-ins. */
    record Closure(String param, Node body, Scope scope) {}

    private final EvalContext ctx;
    private final Limits limits;
    private final Budget budget;
    private final Map<String, Object> roots = new HashMap<>();
    private boolean treeLoaded;
    private JsonNode tree;

    Evaluator(EvalContext ctx, Limits limits) {
        this.ctx = ctx;
        this.limits = limits;
        this.budget = new Budget(limits);
    }

    // ---- services for built-ins

    Limits limits() {
        return limits;
    }

    Budget budget() {
        return budget;
    }

    Clock clock() {
        Clock c = ctx.clock();
        return c == null ? Clock.systemUTC() : c;
    }

    void checkLen(long len) {
        if (len > limits.maxResultChars()) {
            throw Values.tooLarge(limits.maxResultChars());
        }
    }

    /**
     * Upper bound on items a single built-in may return; bulk calls must not allocate without
     * limit.
     */
    static final int MAX_ITEMS = 100_000;

    void checkItems(long n) {
        if (n > MAX_ITEMS) {
            throw ExpressionException.of("Result is too large (limit " + MAX_ITEMS + " items)");
        }
        budget.tick((int) (n >> 6));
    }

    String str(Object v) {
        return Values.toStr(v, limits.maxResultChars());
    }

    Object call(Object fn, Object arg) {
        if (!(fn instanceof Closure c)) {
            throw ExpressionException.of("Expected a function like x => x.price");
        }
        budget.tick();
        return eval(c.body(), new Scope(c.param(), arg, c.scope()));
    }

    Pattern pattern(String regex, String flags) {
        if (regex.length() > limits.maxRegexLength()) {
            throw ExpressionException.of(
                    "Regex is too long ("
                            + regex.length()
                            + " characters, limit "
                            + limits.maxRegexLength()
                            + ")");
        }
        int f = 0;
        for (char c : flags.toCharArray()) {
            switch (c) {
                case 'i' -> f |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                case 'm' -> f |= Pattern.MULTILINE;
                case 's' -> f |= Pattern.DOTALL;
                default ->
                        throw ExpressionException.of(
                                "Unknown regex flag '" + c + "' (use i, m, s)");
            }
        }
        try {
            return Pattern.compile(regex, f);
        } catch (PatternSyntaxException e) {
            throw ExpressionException.of("Invalid regex: " + e.getDescription());
        }
    }

    CharSequence guard(String s) {
        return budget.guard(s);
    }

    // ---- entry points

    Object evaluate(Node n) {
        return eval(n, null);
    }

    Object eval(Node n, Scope s) {
        return Values.unwrap(raw(n, s));
    }

    /**
     * Like {@link #eval} but keeps {@code $node} references intact so property access can
     * special-case them.
     */
    private Object raw(Node n, Scope s) {
        budget.tick();
        try {
            return dispatch(n, s);
        } catch (ExpressionException e) {
            if (e.start() < 0) {
                throw new ExpressionException(e.getMessage(), n.start(), n.end());
            }
            throw e;
        }
    }

    private Object dispatch(Node n, Scope s) {
        return switch (n) {
            case Node.Lit l -> l.value();
            case Node.Ident id -> ident(id.name(), s);
            case Node.Member m -> member(raw(m.target(), s), m.name(), m.optional());
            case Node.Index ix -> index(raw(ix.target(), s), eval(ix.index(), s), ix.optional());
            case Node.Unary u -> unary(u, s);
            case Node.Binary b -> binary(b, s);
            case Node.Ternary t ->
                    Values.truthy(eval(t.cond(), s)) ? eval(t.then(), s) : eval(t.otherwise(), s);
            case Node.ArrayLit a -> {
                List<Object> out = new ArrayList<>(a.items().size());
                for (Node item : a.items()) {
                    out.add(eval(item, s));
                }
                yield out;
            }
            case Node.ObjectLit o -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (int i = 0; i < o.keys().size(); i++) {
                    out.put(o.keys().get(i), eval(o.values().get(i), s));
                }
                yield out;
            }
            case Node.Call c -> callBuiltin(c.name(), c.args(), s);
            case Node.MethodCall mc -> method(mc, s);
            case Node.Lambda l ->
                    throw ExpressionException.of(
                            "Function expressions (x => ...) are only allowed as arguments to built-in functions");
        };
    }

    // ---- identifiers and roots

    private Object ident(String name, Scope s) {
        for (Scope c = s; c != null; c = c.parent) {
            if (c.name.equals(name)) {
                return c.value;
            }
        }
        if (name.charAt(0) == '$') {
            return root(name);
        }
        Object v = legacyValue(name);
        return v == NOT_FOUND ? null : v;
    }

    private Object root(String name) {
        switch (name) {
            case "$node":
                return Values.NODE_ROOT;
            case "$input":
                {
                    String in = ctx.input();
                    return in == null ? "" : in;
                }
            default:
                break;
        }
        if (roots.containsKey(name)) {
            return roots.get(name);
        }
        Object v =
                switch (name) {
                    case "$json" -> Values.parseLoose(ctx.input());
                    case "$trigger" -> Values.normalize(budget.io(ctx::trigger));
                    case "$run" -> Values.normalize(budget.io(ctx::run));
                    case "$workflow" -> Values.normalize(ctx.workflow());
                    case "$vars" -> Values.normalize(ctx.vars());
                    case "$item" -> Values.normalize(ctx.item());
                    case "$index" -> Values.normalize(ctx.index());
                    case "$items" -> Values.normalize(ctx.items());
                    case "$now" -> clock().instant().toString();
                    default ->
                            throw ExpressionException.of(
                                    "Unknown root '"
                                            + name
                                            + "' (expected $json, $input, $node, $trigger, $run, $workflow, $vars, $item, $index, $items or $now)");
                };
        roots.put(name, v);
        return v;
    }

    private JsonNode tree() {
        if (!treeLoaded) {
            tree = ctx.inputTree();
            treeLoaded = true;
        }
        return tree;
    }

    /**
     * Legacy bare-name lookup, mirroring the old renderTemplate: "input" is the raw input, any
     * other name is a top-level field of the input object - and a field literally named "input"
     * wins over the raw string, because the old code put the fields into the map after "input".
     */
    Object legacyValue(String name) {
        if ("input".equals(name) && ctx.rawInputWins()) {
            String in = ctx.input();
            return in == null ? "" : in;
        }
        JsonNode t = tree();
        if (t != null && t.isObject()) {
            JsonNode f = t.get(name);
            if (f != null) {
                return Values.fromJson(f);
            }
        }
        if ("input".equals(name)) {
            String in = ctx.input();
            return in == null ? "" : in;
        }
        return NOT_FOUND;
    }

    /**
     * Same lookup as {@link #legacyValue} but stringified from the original Jackson node,
     * byte-for-byte as the old code did.
     */
    String legacyString(String name) {
        if ("input".equals(name) && ctx.rawInputWins()) {
            String in = ctx.input();
            return in == null ? "" : in;
        }
        JsonNode t = tree();
        if (t != null && t.isObject()) {
            JsonNode f = t.get(name);
            if (f != null) {
                return f.isTextual() ? f.asText() : f.toString();
            }
        }
        if ("input".equals(name)) {
            String in = ctx.input();
            return in == null ? "" : in;
        }
        return null;
    }

    // ---- property access

    private Object member(Object base, String name, boolean optional) {
        if (base == Values.NODE_ROOT) {
            return nodeRef(budget.io(() -> ctx.nodeByLabel(name)));
        }
        if (base instanceof NodeRef r) {
            NodeOutput o = r.out();
            switch (name) {
                case "output":
                    return o.output();
                case "status":
                    return o.status();
                case "summary":
                    return o.summary();
                case "id":
                    return o.id();
                case "label":
                    return o.label();
                default:
                    base = o.value();
            }
        }
        if (base == null) {
            if (limits.strict() && !optional) {
                throw ExpressionException.of("Cannot read \"" + name + "\" of null");
            }
            return null;
        }
        if (base instanceof Map<?, ?> m) {
            return m.get(name);
        }
        if ("length".equals(name)) {
            if (base instanceof List<?> l) {
                return (long) l.size();
            }
            if (base instanceof String str) {
                return (long) str.length();
            }
        }
        if (limits.strict() && !optional) {
            throw ExpressionException.of("Cannot read \"" + name + "\" of " + Values.typeOf(base));
        }
        return null;
    }

    private Object index(Object base, Object idx, boolean optional) {
        if (base == Values.NODE_ROOT) {
            return nodeRef(budget.io(() -> ctx.nodeByLabel(Values.toStr(idx))));
        }
        if (base instanceof NodeRef r) {
            if (idx instanceof String key) {
                return member(base, key, optional);
            }
            base = r.out().value();
        }
        if (base == null) {
            if (limits.strict() && !optional) {
                throw ExpressionException.of("Cannot index null");
            }
            return null;
        }
        if (base instanceof Map<?, ?> m) {
            return m.get(Values.toStr(idx));
        }
        if (base instanceof List<?> l) {
            Integer i = position(idx, l.size());
            return i == null ? null : l.get(i);
        }
        if (base instanceof String str) {
            Integer i = position(idx, str.length());
            return i == null ? null : String.valueOf(str.charAt(i));
        }
        if (limits.strict() && !optional) {
            throw ExpressionException.of("Cannot index " + Values.typeOf(base));
        }
        return null;
    }

    /** Integer index with negative-from-end; null when out of range or not an integer. */
    private static Integer position(Object idx, int size) {
        Number n = Values.toNumber(idx);
        if (n == null || n.doubleValue() != Math.rint(n.doubleValue())) {
            return null;
        }
        long i = n.longValue();
        if (i < 0) {
            i += size;
        }
        return i >= 0 && i < size ? (int) i : null;
    }

    private static Object nodeRef(NodeOutput o) {
        return o == null ? null : new NodeRef(o);
    }

    // ---- operators

    private Object unary(Node.Unary u, Scope s) {
        Object v = eval(u.operand(), s);
        if (u.op().equals("!")) {
            return !Values.truthy(v);
        }
        Number n = Values.toNumber(v);
        if (n == null) {
            throw ExpressionException.of("Cannot negate " + Values.typeOf(v));
        }
        if (n instanceof Long l) {
            return l == Long.MIN_VALUE ? (Object) (-(double) l) : (Object) (-l);
        }
        return -n.doubleValue();
    }

    private Object binary(Node.Binary b, Scope s) {
        String op = b.op();
        switch (op) {
            case "&&":
                {
                    Object l = eval(b.left(), s);
                    return Values.truthy(l) ? eval(b.right(), s) : l;
                }
            case "||":
                {
                    Object l = eval(b.left(), s);
                    return Values.truthy(l) ? l : eval(b.right(), s);
                }
            case "??":
                {
                    Object l = eval(b.left(), s);
                    return l != null ? l : eval(b.right(), s);
                }
            default:
                break;
        }
        Object l = eval(b.left(), s);
        Object r = eval(b.right(), s);
        switch (op) {
            case "==":
                return Values.looseEquals(l, r, budget);
            case "!=":
                return !Values.looseEquals(l, r, budget);
            case "<":
            case ">":
            case "<=":
            case ">=":
                {
                    if (l == null || r == null) {
                        return false;
                    }
                    int c = Values.compare(l, r);
                    return switch (op) {
                        case "<" -> c < 0;
                        case ">" -> c > 0;
                        case "<=" -> c <= 0;
                        default -> c >= 0;
                    };
                }
            default:
                return arithmetic(op, l, r);
        }
    }

    private Object arithmetic(String op, Object a, Object b) {
        if (op.equals("+") && (a instanceof String || b instanceof String)) {
            String x = Values.toStr(a);
            String y = Values.toStr(b);
            checkLen((long) x.length() + y.length());
            return x + y;
        }
        Number x = Values.toNumber(a);
        Number y = Values.toNumber(b);
        if (x == null || y == null) {
            throw ExpressionException.of(
                    "Operator '"
                            + op
                            + "' needs numbers, got "
                            + Values.typeOf(a)
                            + " and "
                            + Values.typeOf(b));
        }
        if (x instanceof Long p && y instanceof Long q) {
            try {
                switch (op) {
                    case "+":
                        return Math.addExact(p, q);
                    case "-":
                        return Math.subtractExact(p, q);
                    case "*":
                        return Math.multiplyExact(p, q);
                    case "/":
                        if (q == 0) {
                            throw ExpressionException.of("Division by zero");
                        }
                        return p % q == 0 && !(p == Long.MIN_VALUE && q == -1)
                                ? (Object) (p / q)
                                : (Object) ((double) p / q);
                    default:
                        if (q == 0) {
                            throw ExpressionException.of("Division by zero");
                        }
                        return q == -1 ? (Object) 0L : (Object) (p % q);
                }
            } catch (ArithmeticException overflow) {
                // fall through to double arithmetic
            }
        }
        double p = x.doubleValue();
        double q = y.doubleValue();
        return switch (op) {
            case "+" -> p + q;
            case "-" -> p - q;
            case "*" -> p * q;
            case "/" -> {
                if (q == 0) {
                    throw ExpressionException.of("Division by zero");
                }
                yield p / q;
            }
            default -> {
                if (q == 0) {
                    throw ExpressionException.of("Division by zero");
                }
                yield p % q;
            }
        };
    }

    // ---- calls

    private Object method(Node.MethodCall mc, Scope s) {
        if (mc.name().equals("id")
                && mc.target() instanceof Node.Ident id
                && id.name().equals("$node")
                && !inScope("$node", s)) {
            if (mc.args().size() != 1) {
                throw ExpressionException.of("$node.id() takes exactly one argument");
            }
            String nodeId = Values.toStr(eval(mc.args().get(0), s));
            return nodeRef(budget.io(() -> ctx.nodeById(nodeId)));
        }
        Node target = mc.target();
        if (mc.optional()) {
            Object t = eval(target, s);
            if (t == null) {
                return null;
            }
            target = new Node.Lit(t, target.start(), target.end());
        }
        List<Node> nodes = new ArrayList<>(mc.args().size() + 1);
        nodes.add(target);
        nodes.addAll(mc.args());
        return callBuiltin(mc.name(), nodes, s);
    }

    private static boolean inScope(String name, Scope s) {
        for (Scope c = s; c != null; c = c.parent) {
            if (c.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private Object callBuiltin(String name, List<Node> nodes, Scope s) {
        BuiltIn b = BuiltIns.registry().get(name);
        if (b == null) {
            throw ExpressionException.of(BuiltIns.unknownFunctionMessage(name));
        }
        int n = nodes.size();
        if (n < b.minArgs() || (b.maxArgs() >= 0 && n > b.maxArgs())) {
            throw ExpressionException.of(name + "() takes " + arityText(b) + ", got " + n);
        }
        try {
            if (b.lazyImpl() != null) {
                return b.lazyImpl().apply(this, nodes, s);
            }
            List<Object> args = new ArrayList<>(n);
            for (Node arg : nodes) {
                args.add(
                        arg instanceof Node.Lambda l
                                ? new Closure(l.param(), l.body(), s)
                                : eval(arg, s));
            }
            Object result = b.impl().apply(this, args);
            if (result instanceof List<?> l) {
                checkItems(l.size());
            } else if (result instanceof Map<?, ?> mp) {
                checkItems(mp.size());
            }
            return result;
        } catch (ExpressionException e) {
            throw e;
        } catch (RuntimeException e) {
            // Anything a built-in did not anticipate (bad zone id, malformed base64, ...) is still
            // a user-facing error.
            // java.time parse errors quote the input text; say what was wrong without echoing data.
            String reason =
                    e instanceof java.time.DateTimeException
                            ? "invalid date, pattern or time zone"
                            : e.getMessage() == null
                                    ? e.getClass().getSimpleName()
                                    : e.getMessage();
            throw ExpressionException.of(name + "(): " + reason);
        }
    }

    static String arityText(BuiltIn b) {
        if (b.maxArgs() < 0) {
            return "at least " + b.minArgs() + " argument" + (b.minArgs() == 1 ? "" : "s");
        }
        if (b.minArgs() == b.maxArgs()) {
            return b.minArgs() + " argument" + (b.minArgs() == 1 ? "" : "s");
        }
        return b.minArgs() + " to " + b.maxArgs() + " arguments";
    }
}
