package com.agenticai.core.workflow.expression;

import com.agenticai.core.workflow.expression.Diagnostic.Severity;
import com.agenticai.core.workflow.expression.TemplateScanner.Match;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Facade over the expression library. Stateless apart from its {@link Limits}; every call builds
 * its own evaluator (and therefore its own step/time budget), so one instance is safely shared
 * across threads. Nothing but {@link ExpressionException} escapes.
 */
public final class ExpressionEngine {

    private static final Set<String> ROOTS =
            Set.of(
                    "$json",
                    "$input",
                    "$node",
                    "$trigger",
                    "$run",
                    "$workflow",
                    "$vars",
                    "$item",
                    "$index",
                    "$items",
                    "$now");

    private final Limits limits;

    public ExpressionEngine() {
        this(Limits.defaults());
    }

    public ExpressionEngine(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        warmUp();
    }

    private ExpressionEngine(Limits limits, boolean noWarmUp) {
        this.limits = limits;
    }

    private static volatile boolean warmedUp;

    /**
     * Loads and initialises every class a first request would otherwise load inside its own wall
     * budget (the 50 ms default flaked in a cold JVM): the built-in registry, parser, evaluator,
     * Jackson tree handling, date/regex/JSON functions and the linter. Idempotent and once per JVM;
     * called from every engine constructor and, eagerly, from the Spring beans that own an engine
     * (the resolver and the evaluate service), so it runs at startup and never on the first user
     * expression.
     */
    public static void warmUp() {
        if (warmedUp) {
            return;
        }
        synchronized (ExpressionEngine.class) {
            if (warmedUp) {
                return;
            }
            try {
                BuiltIns.registry();
                ExpressionEngine warm =
                        new ExpressionEngine(
                                Limits.defaults().withMaxWallNanos(10_000_000_000L), true);
                EvalContext ctx =
                        BasicEvalContext.builder()
                                .input("{\"a\":[1,2],\"s\":\"x\",\"n\":1.5}")
                                .node(
                                        new NodeOutput(
                                                "n1",
                                                "Fetch",
                                                "{\"items\":[{\"price\":2,\"name\":\"p\"}]}",
                                                "OK",
                                                "ok"))
                                .trigger(Values.parseLoose("{\"body\":{\"k\":1}}"))
                                .run(java.util.Map.of("id", "r", "mode", "draft"))
                                .workflow(java.util.Map.of("id", "w", "name", "warm"))
                                .loop("i", 0, java.util.List.of("i"))
                                .build();
                String[] expressions = {
                    "$json.a.map(x => x + 1).length + 1",
                    "$node[\"Fetch\"].items[0].price * 1.5 > 2 ? upper($json.s) : 'n'",
                    "$trigger?.body.k ?? 0",
                    "$item + $index + $items.length",
                    "formatDate($now, 'yyyy-MM-dd') + addDays($now, 1)",
                    "regexReplace($json.s, 'x+', 'y') + replace('a-b', '-', '+') + split('a,b', ',').join('|')",
                    "toJson({a: $json.a.filter(x => x > 1).sort().reverse(), b: keys($json)}) + base64('x') + urlEncode('a b')",
                    "round(avg([1,2,3]), 1) + sum($json.a) + toFixed(1.234, 2) + coalesce(null, 1) + typeOf($json)",
                };
                for (String e : expressions) {
                    try {
                        warm.evaluate(e, ctx);
                    } catch (RuntimeException ignored) {
                        // a function erroring still loaded its classes
                    }
                }
                warm.renderValue("{{ $json.a }}", ctx);
                warm.renderValue("{{ $json.s }}", ctx, true);
                warm.render(
                        "{{ $json.a[0] }} {{ toJson($json) }} {{input}} {{s}} {{ $node[\"Fetch\"].items[0].name }}",
                        ctx,
                        true);
                warm.lint(
                        "{{ $node[\"Fetch\"].items[0].price }} {{ nope( }} {{ s }}",
                        new LintContext(
                                java.util.Map.of("Fetch", "n1"),
                                java.util.Set.of(),
                                java.util.Set.of("a")));
                warm.referencedNodeLabels("{{ $node[\"Fetch\"].x }}");
                warmedUp = true;
            } catch (RuntimeException | LinkageError ignored) {
                // warm-up is best effort; a failure here must never stop the engine from
                // constructing
            }
        }
    }

    public Limits limits() {
        return limits;
    }

    // ---- evaluation

    /** Evaluates one bare expression (no braces). */
    public Object evaluate(String expression, EvalContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (expression == null) {
            throw new ExpressionException("Expression is empty", 0, 0);
        }
        return guarded(
                0,
                expression.length(),
                () -> {
                    Node n = Parser.parse(expression, limits);
                    Object v = new Evaluator(ctx, limits).evaluate(n);
                    checkResult(v, n);
                    return v;
                });
    }

    /**
     * If the template is exactly one {@code {{ expr }}} (no other characters at all) returns the
     * typed value; otherwise the rendered string.
     */
    public Object renderValue(String template, EvalContext ctx) {
        return renderValue(template, ctx, false);
    }

    /**
     * {@code legacyLiteral} is for fields that have always templated ({@code {{key}}} replace): a
     * span that only uses bare names (no {@code $} root, no function call) and either cannot be
     * parsed, names a key the input does not carry, or fails to evaluate is kept as literal text,
     * exactly as the old replace() left it. Such text is often not ours ({@code ${{ secrets.X }}},
     * Handlebars or Jinja snippets in an HTTP body).
     */
    public Object renderValue(String template, EvalContext ctx, boolean legacyLiteral) {
        Objects.requireNonNull(ctx, "ctx");
        if (template == null) {
            return "";
        }
        Match m = firstMatch(template);
        if (m == null || m.start() != 0 || m.end() != template.length()) {
            return render(template, ctx, legacyLiteral);
        }
        String raw = template.substring(m.exprStart(), m.exprEnd());
        Evaluator ev = new Evaluator(ctx, limits);
        Object exact = looksLikeLegacyKey(raw) ? ev.legacyValue(raw) : Evaluator.NOT_FOUND;
        if (exact != Evaluator.NOT_FOUND) {
            return exact;
        }
        Node n;
        try {
            n = Parser.parse(raw, limits);
        } catch (ExpressionException e) {
            if (raw.startsWith("{")) {
                return render(
                        template,
                        ctx,
                        legacyLiteral); // "{{{x}}}" is "{" + {{x}} + "}" as it always was
            }
            if (legacyLiteral && raw.indexOf('$') < 0) {
                return template;
            }
            throw e.shifted(m.exprStart());
        }
        if (n instanceof Node.Ident id && id.name().charAt(0) != '$') {
            Object v = ev.legacyValue(id.name());
            return v == Evaluator.NOT_FOUND ? template : v;
        }
        if (legacyLiteral && legacyMiss(n, ev)) {
            return template;
        }
        try {
            return guarded(
                    m.exprStart(),
                    m.exprStart() + raw.length(),
                    () -> {
                        Object v = ev.evaluate(n);
                        checkResult(v, n);
                        return v;
                    });
        } catch (ExpressionException e) {
            if (legacyLiteral && legacyShaped(n, new ArrayList<>())) {
                return template;
            }
            throw e;
        }
    }

    /**
     * Single pass over the template. Substituted output is never re-scanned, so data that contains
     * {@code {{...}}} stays inert. null renders as empty text; objects and arrays as compact JSON;
     * integer-valued numbers without ".0".
     */
    public String render(String template, EvalContext ctx) {
        return render(template, ctx, false);
    }

    /** See {@link #renderValue(String, EvalContext, boolean)} for {@code legacyLiteral}. */
    public String render(String template, EvalContext ctx, boolean legacyLiteral) {
        return render(template, ctx, legacyLiteral, null);
    }

    /**
     * As {@link #render(String, EvalContext, boolean)}; {@code post}, when given, is applied to
     * every substituted value (never to the literal template text), e.g. HTML escaping for an HTML
     * email body.
     */
    public String render(
            String template, EvalContext ctx, boolean legacyLiteral, UnaryOperator<String> post) {
        Objects.requireNonNull(ctx, "ctx");
        UnaryOperator<String> sub = post == null ? UnaryOperator.identity() : post;
        if (template == null) {
            return "";
        }
        Evaluator ev = new Evaluator(ctx, limits);
        StringBuilder out = new StringBuilder(template.length() + 16);
        long computedCap = 4L * limits.maxResultChars();
        long computed = 0;
        int pos = 0;
        while (true) {
            Match m = scan(template, pos);
            if (m == null) {
                break;
            }
            out.append(template, pos, m.start());
            pos = m.end();
            String raw = template.substring(m.exprStart(), m.exprEnd());
            // Exact-key match first: the old code replaced "{{<key>}}" verbatim, so keys like
            // "first name" keep working.
            String legacy = looksLikeLegacyKey(raw) ? ev.legacyString(raw) : null;
            if (legacy != null) {
                out.append(sub.apply(legacy));
                continue;
            }
            Node n;
            try {
                n = Parser.parse(raw, limits);
            } catch (ExpressionException e) {
                if (raw.startsWith("{")) {
                    out.append(
                            '{'); // "{{{x}}}": first brace is literal, exactly as the old replace()
                    // saw it
                    pos = m.start() + 1;
                    continue;
                }
                if (legacyLiteral && raw.indexOf('$') < 0) {
                    out.append(template, m.start(), m.end());
                    continue;
                }
                throw e.shifted(m.exprStart());
            }
            if (n instanceof Node.Ident id && id.name().charAt(0) != '$') {
                String v = ev.legacyString(id.name());
                out.append(v != null ? sub.apply(v) : template.substring(m.start(), m.end()));
                continue;
            }
            if (legacyLiteral && legacyMiss(n, ev)) {
                out.append(template, m.start(), m.end());
                continue;
            }
            Object v;
            try {
                v =
                        guarded(
                                m.exprStart(),
                                m.exprStart() + raw.length(),
                                () -> {
                                    Object r = ev.evaluate(n);
                                    checkResult(r, n);
                                    return r;
                                });
            } catch (ExpressionException e) {
                if (legacyLiteral && legacyShaped(n, new ArrayList<>())) {
                    out.append(template, m.start(), m.end());
                    continue;
                }
                throw e;
            }
            String s =
                    Values.toStr(
                            v,
                            -1); // pure paths are real data and exempt from the cap; others passed
            // checkResult
            if (!isPurePath(n)) {
                computed += s.length();
                if (computed > computedCap) {
                    throw new ExpressionException(
                            "Rendered expressions are too large (limit "
                                    + computedCap
                                    + " characters)",
                            m.start(),
                            m.end());
                }
            }
            out.append(sub.apply(s));
        }
        out.append(template, pos, template.length());
        return out.toString();
    }

    /**
     * True when the span uses only bare names (no {@code $} root, no call, no lambda); collects
     * those names.
     */
    private static boolean legacyShaped(Node n, List<String> names) {
        switch (n) {
            case Node.Ident id -> {
                if (id.name().charAt(0) == '$') {
                    return false;
                }
                names.add(id.name());
            }
            case Node.Call c -> {
                return false;
            }
            case Node.MethodCall mc -> {
                return false;
            }
            case Node.Lambda l -> {
                return false;
            }
            default -> {}
        }
        for (Node child : Node.children(n)) {
            if (!legacyShaped(child, names)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A legacy-shaped span naming a key the input does not carry: the old replace() left it
     * untouched.
     */
    private static boolean legacyMiss(Node n, Evaluator ev) {
        List<String> names = new ArrayList<>();
        if (!legacyShaped(n, names)) {
            return false;
        }
        for (String name : names) {
            if (ev.legacyValue(name) == Evaluator.NOT_FOUND) {
                return true;
            }
        }
        return false;
    }

    // ---- lint

    public List<Diagnostic> lint(String template, LintContext lctx) {
        List<Diagnostic> out = new ArrayList<>();
        if (template == null) {
            return out;
        }
        LintContext lc = lctx == null ? LintContext.empty() : lctx;
        int pos = 0;
        while (true) {
            Match m;
            try {
                m = TemplateScanner.next(template, pos, limits.maxSourceLength());
            } catch (ExpressionException e) {
                out.add(
                        new Diagnostic(
                                Severity.ERROR, "PARSE_ERROR", e.getMessage(), e.start(), e.end()));
                return out;
            }
            if (m == null) {
                return out;
            }
            pos = m.end();
            String raw = template.substring(m.exprStart(), m.exprEnd());
            Node n;
            try {
                n = Parser.parse(raw, limits);
            } catch (ExpressionException e) {
                if (raw.startsWith("{")) {
                    pos = m.start() + 1;
                    continue;
                }
                ExpressionException s = e.shifted(m.exprStart());
                out.add(
                        new Diagnostic(
                                Severity.ERROR,
                                "PARSE_ERROR",
                                e.getMessage(),
                                Math.max(0, s.start()),
                                Math.max(0, s.end())));
                continue;
            }
            lintNode(n, new HashSet<>(), lc, m.exprStart(), out, n instanceof Node.Ident);
        }
    }

    private void lintNode(
            Node n,
            Set<String> scope,
            LintContext lc,
            int base,
            List<Diagnostic> out,
            boolean whole) {
        switch (n) {
            case Node.Ident id -> lintIdent(id, scope, lc, base, out, whole);
            case Node.Member mem -> {
                if (isNodeRoot(mem.target(), scope)) {
                    checkLabel(mem.name(), mem, lc, base, out);
                }
            }
            case Node.Index ix -> {
                if (isNodeRoot(ix.target(), scope)
                        && ix.index() instanceof Node.Lit l
                        && l.value() instanceof String label) {
                    checkLabel(label, ix, lc, base, out);
                }
            }
            case Node.Call c -> checkFunction(c.name(), c.args().size(), c, base, out);
            case Node.MethodCall mc -> {
                if (mc.name().equals("id") && isNodeRoot(mc.target(), scope)) {
                    if (lc.labelToId() != null
                            && mc.args().size() == 1
                            && mc.args().get(0) instanceof Node.Lit l
                            && l.value() instanceof String id
                            && !lc.labelToId().containsValue(id)) {
                        out.add(
                                new Diagnostic(
                                        Severity.ERROR,
                                        "UNKNOWN_NODE",
                                        "No upstream node with id '" + id + "'",
                                        base + mc.start(),
                                        base + mc.end()));
                    }
                } else {
                    checkFunction(mc.name(), mc.args().size() + 1, mc, base, out);
                }
            }
            case Node.Lambda l -> {
                Set<String> inner = new HashSet<>(scope);
                inner.add(l.param());
                lintNode(l.body(), inner, lc, base, out, false);
                return;
            }
            default -> {}
        }
        for (Node child : Node.children(n)) {
            lintNode(child, scope, lc, base, out, false);
        }
    }

    private static boolean isNodeRoot(Node target, Set<String> scope) {
        return target instanceof Node.Ident id
                && id.name().equals("$node")
                && !scope.contains("$node");
    }

    private void lintIdent(
            Node.Ident id,
            Set<String> scope,
            LintContext lc,
            int base,
            List<Diagnostic> out,
            boolean whole) {
        String name = id.name();
        if (scope.contains(name)) {
            return;
        }
        if (name.charAt(0) == '$') {
            if (!ROOTS.contains(name)) {
                out.add(
                        new Diagnostic(
                                Severity.ERROR,
                                "UNKNOWN_ROOT",
                                "Unknown root '" + name + "'",
                                base + id.start(),
                                base + id.end()));
            }
            return;
        }
        if (name.equals("input")
                || lc.knownInputFields() == null
                || lc.knownInputFields().contains(name)) {
            return;
        }
        out.add(
                new Diagnostic(
                        Severity.WARNING,
                        "LEGACY_UNKNOWN_NAME",
                        "The input has no field '"
                                + name
                                + "'; "
                                + (whole
                                        ? "the placeholder will be left as literal text"
                                        : "it will evaluate to null"),
                        base + id.start(),
                        base + id.end()));
    }

    private void checkLabel(String label, Node at, LintContext lc, int base, List<Diagnostic> out) {
        if (lc.labelToId() != null && !lc.labelToId().containsKey(label)) {
            String near = BuiltIns.nearest(label, lc.labelToId().keySet());
            out.add(
                    new Diagnostic(
                            Severity.ERROR,
                            "UNKNOWN_NODE",
                            "No upstream node labelled '"
                                    + label
                                    + "'"
                                    + (near == null ? "" : ". Did you mean '" + near + "'?"),
                            base + at.start(),
                            base + at.end()));
        } else if (lc.ambiguousLabels() != null && lc.ambiguousLabels().contains(label)) {
            out.add(
                    new Diagnostic(
                            Severity.WARNING,
                            "AMBIGUOUS_NODE",
                            "More than one node is labelled '"
                                    + label
                                    + "'; reference it by id with $node.id(\"...\")",
                            base + at.start(),
                            base + at.end()));
        }
    }

    private void checkFunction(String name, int argCount, Node at, int base, List<Diagnostic> out) {
        BuiltIn b = BuiltIns.registry().get(name);
        if (b == null) {
            out.add(
                    new Diagnostic(
                            Severity.ERROR,
                            "UNKNOWN_FUNCTION",
                            BuiltIns.unknownFunctionMessage(name),
                            base + at.start(),
                            base + at.end()));
        } else if (argCount < b.minArgs() || (b.maxArgs() >= 0 && argCount > b.maxArgs())) {
            out.add(
                    new Diagnostic(
                            Severity.ERROR,
                            "WRONG_ARGUMENT_COUNT",
                            name + "() takes " + Evaluator.arityText(b) + ", got " + argCount,
                            base + at.start(),
                            base + at.end()));
        }
    }

    // ---- references

    /**
     * Labels used as {@code $node["Label"]} / {@code $node.Label} anywhere in the template.
     * Unparsable spans are skipped.
     */
    public Set<String> referencedNodeLabels(String template) {
        Set<String> out = new LinkedHashSet<>();
        collectReferences(template, out, null);
        return out;
    }

    /** Ids used as {@code $node.id("n7")} anywhere in the template. */
    public Set<String> referencedNodeIds(String template) {
        Set<String> out = new LinkedHashSet<>();
        collectReferences(template, null, out);
        return out;
    }

    private void collectReferences(String template, Set<String> labels, Set<String> ids) {
        if (template == null) {
            return;
        }
        int pos = 0;
        while (true) {
            Match m;
            try {
                m = TemplateScanner.next(template, pos, limits.maxSourceLength());
            } catch (ExpressionException e) {
                return;
            }
            if (m == null) {
                return;
            }
            pos = m.end();
            try {
                walkReferences(
                        Parser.parse(template.substring(m.exprStart(), m.exprEnd()), limits),
                        new HashSet<>(),
                        labels,
                        ids);
            } catch (ExpressionException e) {
                // reported by lint(); references are best-effort
            }
        }
    }

    private void walkReferences(Node n, Set<String> scope, Set<String> labels, Set<String> ids) {
        if (n instanceof Node.Member mem && labels != null && isNodeRoot(mem.target(), scope)) {
            labels.add(mem.name());
        } else if (n instanceof Node.Index ix
                && labels != null
                && isNodeRoot(ix.target(), scope)
                && ix.index() instanceof Node.Lit l
                && l.value() instanceof String s) {
            labels.add(s);
        } else if (n instanceof Node.MethodCall mc
                && ids != null
                && mc.name().equals("id")
                && isNodeRoot(mc.target(), scope)
                && mc.args().size() == 1
                && mc.args().get(0) instanceof Node.Lit l
                && l.value() instanceof String s) {
            ids.add(s);
        }
        if (n instanceof Node.Lambda lam) {
            Set<String> inner = new HashSet<>(scope);
            inner.add(lam.param());
            walkReferences(lam.body(), inner, labels, ids);
            return;
        }
        for (Node child : Node.children(n)) {
            walkReferences(child, scope, labels, ids);
        }
    }

    // ---- internals

    /**
     * The exact-key shortcut exists for legacy keys such as "first name". Text carrying real
     * expression syntax never takes it: otherwise a caller-supplied input key spelled like the
     * expression (for example a webhook body key "$json.role == 'admin'") would override the
     * computed value.
     */
    static boolean looksLikeLegacyKey(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            if ("$()[]'\"=<>!&|?:,`".indexOf(raw.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    private Match firstMatch(String template) {
        return scan(template, 0);
    }

    private Match scan(String template, int from) {
        return TemplateScanner.next(template, from, limits.maxSourceLength());
    }

    /**
     * Pure property paths hand back data that already exists, so they are exempt from the
     * result-size cap.
     */
    private static boolean isPurePath(Node n) {
        return switch (n) {
            case Node.Ident i -> true;
            case Node.Member m -> isPurePath(m.target());
            case Node.Index x ->
                    isPurePath(x.target())
                            && (x.index() instanceof Node.Lit
                                    || (x.index() instanceof Node.Unary u
                                            && u.operand() instanceof Node.Lit));
            default -> false;
        };
    }

    private void checkResult(Object v, Node n) {
        if (isPurePath(n)) {
            return;
        }
        int cap = limits.maxResultChars();
        try {
            if (v instanceof String s) {
                if (s.length() > cap) {
                    throw Values.tooLarge(cap);
                }
            } else if (v instanceof List || v instanceof java.util.Map) {
                Values.toJson(v, cap);
            }
        } catch (ExpressionException e) {
            throw new ExpressionException(e.getMessage(), n.start(), n.end());
        }
    }

    /**
     * Runs {@code body}, shifting error positions into template space and converting stray runtime
     * failures.
     */
    private <T> T guarded(int base, int end, Supplier<T> body) {
        try {
            return body.get();
        } catch (ExpressionException e) {
            throw e.shifted(base);
        } catch (StackOverflowError e) {
            throw new ExpressionException("Expression is nested too deeply", base, end);
        } catch (OutOfMemoryError e) {
            throw new ExpressionException("Expression used too much memory", base, end);
        } catch (RuntimeException e) {
            throw new ExpressionException(
                    "Internal expression error: " + e.getClass().getSimpleName(), base, end);
        }
    }
}
