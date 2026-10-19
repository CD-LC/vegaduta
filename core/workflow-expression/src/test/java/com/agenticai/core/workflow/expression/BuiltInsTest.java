package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Registry completeness (spec 3.4: docs cannot drift from code) plus per-function behaviour tables.
 */
class BuiltInsTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-30T10:15:30Z"), ZoneOffset.UTC);
    // Generous wall time: these tests check semantics, not the 50 ms budget (LimitsAndErrorsTest
    // covers that).
    private final ExpressionEngine engine =
            new ExpressionEngine(Limits.defaults().withMaxWallNanos(10_000_000_000L));

    /** The registry documents now() against this instant. */
    private final EvalContext exampleCtx =
            BasicEvalContext.builder()
                    .input("")
                    .clock(Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC))
                    .build();

    private final EvalContext ctx = BasicEvalContext.builder().input("").clock(FIXED).build();

    private String ev(String expr) {
        return Values.toJson(engine.evaluate(expr, ctx));
    }

    @Test
    void everySpecFunctionIsRegistered() {
        Set<String> expected =
                Set.of(
                        "lower",
                        "upper",
                        "trim",
                        "split",
                        "join",
                        "replace",
                        "regexReplace",
                        "substring",
                        "contains",
                        "startsWith",
                        "endsWith",
                        "length",
                        "padStart",
                        "urlEncode",
                        "base64",
                        "base64Decode",
                        "round",
                        "floor",
                        "ceil",
                        "abs",
                        "min",
                        "max",
                        "sum",
                        "avg",
                        "toNumber",
                        "toFixed",
                        "map",
                        "filter",
                        "find",
                        "some",
                        "every",
                        "first",
                        "last",
                        "slice",
                        "sort",
                        "unique",
                        "flatten",
                        "reverse",
                        "count",
                        "pluck",
                        "keys",
                        "values",
                        "entries",
                        "merge",
                        "pick",
                        "omit",
                        "get",
                        "now",
                        "formatDate",
                        "parseDate",
                        "addDays",
                        "addHours",
                        "diffDays",
                        "toEpoch",
                        "toString",
                        "toBoolean",
                        "parseJson",
                        "toJson",
                        "typeOf",
                        "isEmpty",
                        "isNumber",
                        "if",
                        "coalesce");
        assertEquals(
                Set.of(),
                difference(expected, BuiltIns.registry().keySet()),
                "missing from registry");
        assertTrue(BuiltIns.registry().size() >= 60, "registry has " + BuiltIns.registry().size());
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        var out = new java.util.TreeSet<>(a);
        out.removeAll(b);
        return out;
    }

    @Test
    void everyBuiltInHasCompleteMetadata() {
        for (BuiltIn b : BuiltIns.registry().values()) {
            String n = b.name();
            assertFalse(n == null || n.isBlank(), "name");
            assertEquals(n, BuiltIns.registry().get(n).name());
            assertFalse(b.category() == null || b.category().isBlank(), n + " category");
            assertFalse(b.signature() == null || b.signature().isBlank(), n + " signature");
            assertTrue(
                    b.signature().startsWith(n) || b.signature().contains(n + "("),
                    n + " signature names the function: " + b.signature());
            assertFalse(b.doc() == null || b.doc().isBlank(), n + " doc");
            assertFalse(b.example() == null || b.example().isBlank(), n + " example");
            assertTrue(
                    b.minArgs() >= 0 && (b.maxArgs() < 0 || b.maxArgs() >= b.minArgs()),
                    n + " arity");
        }
    }

    @Test
    void everyExampleEvaluatesToItsDeclaredNonNullResult() {
        for (BuiltIn b : BuiltIns.registry().values()) {
            Object v;
            try {
                v = engine.evaluate(b.example(), exampleCtx);
            } catch (ExpressionException e) {
                throw new AssertionError(
                        b.name() + " example failed: " + b.example() + " -> " + e.getMessage(), e);
            }
            assertNotNull(v, b.name() + " example returned null: " + b.example());
            assertFalse(
                    b.exampleResult() == null || b.exampleResult().isBlank(),
                    b.name() + " exampleResult");
            assertEquals(b.exampleResult(), Values.toJson(v), b.name() + " example " + b.example());
        }
    }

    @Test
    void lazyFunctionsAreIfAndCoalesceOnly() {
        for (BuiltIn b : BuiltIns.registry().values()) {
            assertEquals(Set.of("if", "coalesce").contains(b.name()), b.lazy(), b.name());
        }
    }

    @Test
    void ifIsLazy() {
        // The untaken branch would throw if evaluated.
        assertEquals("1", ev("if(true, 1, parseJson('{'))"));
        assertEquals("2", ev("if(false, parseJson('{'), 2)"));
        assertEquals("3", ev("coalesce(null, 3, parseJson('{'))"));
    }

    @Test
    void stringFunctions() {
        assertEquals("\"abc\"", ev("lower('ABC')"));
        assertEquals("\"ABC\"", ev("'abc'.upper()"));
        assertEquals("\"x\"", ev("trim('  x \\n')"));
        assertEquals("[\"a\",\"b\",\"c\"]", ev("split('a,b,c', ',')"));
        assertEquals("\"a-b\"", ev("join(['a','b'], '-')"));
        assertEquals("\"a,b\"", ev("join(['a','b'])"));
        assertEquals("\"1,2\"", ev("join([1,2])"));
        assertEquals("\"b-b\"", ev("replace('a-a', 'a', 'b')"));
        assertEquals("\"a_b_\"", ev("regexReplace('a1b2', '[0-9]', '_')"));
        assertEquals("\"X-X\"", ev("regexReplace('a-A', 'a', 'X', 'i')"));
        assertEquals("\"ell\"", ev("substring('hello', 1, 4)"));
        assertEquals("\"llo\"", ev("substring('hello', 2)"));
        assertEquals("true", ev("contains('hello', 'ell')"));
        assertEquals("true", ev("contains([1,2], 2)"));
        assertEquals("false", ev("contains({a: 1}, 'b')"));
        assertEquals("true", ev("contains({a: 1}, 'a')"));
        assertEquals("true", ev("startsWith('hello', 'he')"));
        assertEquals("false", ev("endsWith('hello', 'he')"));
        assertEquals("5", ev("length('hello')"));
        assertEquals("2", ev("length([1,2])"));
        assertEquals("1", ev("length({a: 1})"));
        assertEquals("\"007\"", ev("padStart('7', 3, '0')"));
        assertEquals("\"a%20b%26c\"", ev("urlEncode('a b&c')"));
        assertEquals("\"aGk=\"", ev("base64('hi')"));
        assertEquals("\"hi\"", ev("base64Decode('aGk=')"));
    }

    @Test
    void numberFunctions() {
        assertEquals("3", ev("round(2.5)"));
        assertEquals("2.35", ev("round(2.345, 2)"));
        assertEquals("2", ev("floor(2.9)"));
        assertEquals("3", ev("ceil(2.1)"));
        assertEquals("5", ev("abs(-5)"));
        assertEquals("1", ev("min(3, 1, 2)"));
        assertEquals("1", ev("min([3, 1, 2])"));
        assertEquals("3", ev("max(3, 1, 2)"));
        assertEquals("6", ev("sum([1, 2, 3])"));
        assertEquals("6", ev("sum([{p: 1}, {p: 5}], x => x.p)"));
        assertEquals("2", ev("avg([1, 2, 3])"));
        assertEquals("12", ev("toNumber('12')"));
        assertEquals("1.5", ev("toNumber('1.5')"));
        assertEquals("null", ev("toNumber('abc')"));
        assertEquals("\"1.50\"", ev("toFixed(1.5, 2)"));
        assertEquals("\"3\"", ev("toFixed(2.6, 0)"));
    }

    @Test
    void arrayFunctions() {
        assertEquals("[2,4]", ev("map([1,2], x => x * 2)"));
        assertEquals("[2,3]", ev("filter([1,2,3], x => x > 1)"));
        assertEquals("2", ev("find([1,2,3], x => x > 1)"));
        assertEquals("null", ev("find([1], x => x > 1)"));
        assertEquals("true", ev("some([1,2], x => x > 1)"));
        assertEquals("false", ev("every([1,2], x => x > 1)"));
        assertEquals("true", ev("every([], x => false)"));
        assertEquals("1", ev("first([1,2])"));
        assertEquals("null", ev("first([])"));
        assertEquals("2", ev("last([1,2])"));
        assertEquals("[2,3]", ev("slice([1,2,3,4], 1, 3)"));
        assertEquals("[3,4]", ev("slice([1,2,3,4], -2)"));
        assertEquals("[1,2,3]", ev("sort([3,1,2])"));
        assertEquals("[3,2,1]", ev("sort([3,1,2], 'desc')"));
        assertEquals("[{\"n\":1},{\"n\":2}]", ev("sort([{n: 2}, {n: 1}], x => x.n)"));
        assertEquals("[1,2]", ev("unique([1,2,1,2])"));
        assertEquals("[1,2,3]", ev("flatten([[1],[2,[3]]], 2)"));
        assertEquals("[1,[2]]", ev("flatten([[1],[[2]]])"));
        assertEquals("[2,1]", ev("reverse([1,2])"));
        assertEquals("\"cba\"", ev("reverse('abc')"));
        assertEquals("3", ev("count([1,2,3])"));
        assertEquals("2", ev("count([1,2,3], x => x > 1)"));
        assertEquals("[1,2]", ev("pluck([{a: 1}, {a: 2}], 'a')"));
        assertEquals("[5]", ev("pluck([{a: {b: [5]}}], 'a.b[0]')"));
    }

    @Test
    void objectFunctions() {
        assertEquals("[\"a\",\"b\"]", ev("keys({a: 1, b: 2})"));
        assertEquals("[1,2]", ev("values({a: 1, b: 2})"));
        assertEquals("[[\"a\",1]]", ev("entries({a: 1})"));
        assertEquals("{\"a\":1,\"b\":3}", ev("merge({a: 1, b: 2}, {b: 3})"));
        assertEquals("{\"a\":1}", ev("pick({a: 1, b: 2}, 'a')"));
        assertEquals("{\"b\":2}", ev("omit({a: 1, b: 2}, 'a')"));
        assertEquals("{\"b\":2}", ev("omit({a: 1, b: 2}, ['a'])"));
        assertEquals("5", ev("get({a: {b: [4, 5]}}, 'a.b[1]')"));
        assertEquals("\"d\"", ev("get({a: 1}, 'x.y', 'd')"));
        assertEquals("null", ev("get({a: 1}, 'x.y')"));
    }

    @Test
    void dateFunctions() {
        assertEquals("\"2026-09-30T10:15:30Z\"", ev("now()"));
        assertEquals("\"2026-09-30T10:15:30Z\"", ev("$now"));
        assertEquals("\"2026-09-30\"", ev("formatDate('2026-09-30T10:00:00Z', 'yyyy-MM-dd')"));
        assertEquals(
                "\"2026-09-30 15:30\"",
                ev("formatDate('2026-09-30T10:00:00Z', 'yyyy-MM-dd HH:mm', 'Asia/Kolkata')"));
        assertEquals("\"2026-10-07T10:00:00Z\"", ev("addDays('2026-09-30T10:00:00Z', 7)"));
        assertEquals("\"2026-09-29T10:00:00Z\"", ev("addDays('2026-09-30T10:00:00Z', -1)"));
        assertEquals("\"2026-09-30T13:00:00Z\"", ev("addHours('2026-09-30T10:00:00Z', 3)"));
        assertEquals("10", ev("diffDays('2026-09-01T00:00:00Z', '2026-09-11T00:00:00Z')"));
        assertEquals("-1", ev("diffDays('2026-09-02', '2026-09-01')"));
        assertEquals("1000", ev("toEpoch('1970-01-01T00:00:01Z')"));
        assertEquals("\"2026-09-30T00:00:00Z\"", ev("parseDate('2026-09-30')"));
        assertEquals("\"2026-09-30T00:00:00Z\"", ev("parseDate('30/09/2026', 'dd/MM/yyyy')"));
    }

    @Test
    void typeAndJsonFunctions() {
        assertEquals("\"1\"", ev("toString(1)"));
        assertEquals("\"\"", ev("toString(null)"));
        assertEquals("\"{\\\"a\\\":1}\"", ev("toString({a: 1})"));
        assertEquals("true", ev("toBoolean('x')"));
        assertEquals("false", ev("toBoolean('')"));
        assertEquals("false", ev("toBoolean(0)"));
        assertEquals("{\"a\":[1,2]}", ev("parseJson('{\"a\":[1,2]}')"));
        assertEquals("\"{\\\"a\\\":1}\"", ev("toJson({a: 1})"));
        assertEquals("\"array\"", ev("typeOf([])"));
        assertEquals("\"null\"", ev("typeOf(null)"));
        assertEquals("\"number\"", ev("typeOf(1.5)"));
        assertEquals("\"object\"", ev("typeOf({})"));
        assertEquals("\"string\"", ev("typeOf('s')"));
        assertEquals("\"boolean\"", ev("typeOf(true)"));
        assertEquals("true", ev("isEmpty('')"));
        assertEquals("true", ev("isEmpty(null)"));
        assertEquals("false", ev("isEmpty([0])"));
        assertEquals("true", ev("isNumber('12')"));
        assertEquals("false", ev("isNumber('x')"));
        assertEquals("true", ev("isNumber(3)"));
    }

    @Test
    void wrongTypesAndArityAreExpressionExceptions() {
        assertThrows(ExpressionException.class, () -> engine.evaluate("lower()", ctx));
        assertThrows(ExpressionException.class, () -> engine.evaluate("lower(1, 2)", ctx));
        assertThrows(ExpressionException.class, () -> engine.evaluate("map(5, x => x)", ctx));
        assertThrows(ExpressionException.class, () -> engine.evaluate("map([1], 5)", ctx));
        assertThrows(ExpressionException.class, () -> engine.evaluate("nope(1)", ctx));
        assertThrows(ExpressionException.class, () -> engine.evaluate("parseJson('{')", ctx));
        assertThrows(
                ExpressionException.class, () -> engine.evaluate("formatDate('not a date')", ctx));
        assertThrows(
                ExpressionException.class,
                () -> engine.evaluate("formatDate('2026-09-30', 'yyyy', 'Mars/Base')", ctx));
        assertThrows(ExpressionException.class, () -> engine.evaluate("lower(x => x)", ctx));
    }

    @Test
    void methodSugarEqualsFunctionForm() {
        assertEquals(ev("upper(trim('  a '))"), ev("'  a '.trim().upper()"));
        assertEquals(ev("map([1,2], x => x + 1)"), ev("[1,2].map(x => x + 1)"));
    }
}
