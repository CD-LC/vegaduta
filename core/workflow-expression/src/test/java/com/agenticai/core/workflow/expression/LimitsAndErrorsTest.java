package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Every limit in spec 3.5 trips with small injected limits, and errors carry source positions. */
class LimitsAndErrorsTest {

    private final EvalContext ctx = BasicEvalContext.ofInput("{\"a\":1}");

    private ExpressionException fail(Limits l, String expr) {
        return assertThrows(
                ExpressionException.class, () -> new ExpressionEngine(l).evaluate(expr, ctx), expr);
    }

    @Test
    void defaultsMatchTheSpec() {
        Limits d = Limits.defaults();
        assertEquals(2_000, d.maxSourceLength());
        assertEquals(500, d.maxNodes());
        assertEquals(20_000, d.maxSteps());
        assertEquals(50_000_000L, d.maxWallNanos());
        assertEquals(256 * 1024, d.maxResultChars());
        assertEquals(200, d.maxRegexLength());
        assertEquals(false, d.strict());
    }

    @Test
    void sourceLengthLimit() {
        ExpressionException e = fail(Limits.defaults().withMaxSourceLength(10), "1+1+1+1+1+1+1+1");
        assertTrue(e.getMessage().toLowerCase().contains("long"), e.getMessage());
        new ExpressionEngine(Limits.defaults().withMaxSourceLength(10)).evaluate("1+1", ctx);
        fail(Limits.defaults(), "1+".repeat(1500) + "1");
    }

    @Test
    void astNodeLimit() {
        ExpressionException e = fail(Limits.defaults().withMaxNodes(5), "1+2+3+4+5+6");
        assertTrue(
                e.getMessage().toLowerCase().contains("complex")
                        || e.getMessage().toLowerCase().contains("node"),
                e.getMessage());
        fail(Limits.defaults(), "[" + "1,".repeat(600) + "1]");
    }

    @Test
    void stepLimit() {
        fail(Limits.defaults().withMaxSteps(20), "1+2+3+4+5+6+7+8+9+10+11+12");
        // array callback iterations are steps too
        Limits l = Limits.defaults().withMaxSteps(200);
        String range = "[" + "1,".repeat(60) + "1]";
        ExpressionException e = fail(l, "map(" + range + ", x => x + 1 + 1 + 1 + 1)");
        assertTrue(
                e.getMessage().toLowerCase().contains("step")
                        || e.getMessage().toLowerCase().contains("complex"),
                e.getMessage());
        // and at the default limit, nested callbacks over a bounded input cannot run away
        String big = "[" + "1,".repeat(99) + "1]";
        fail(
                Limits.defaults(),
                "map("
                        + big
                        + ", a => map("
                        + big
                        + ", b => map("
                        + big
                        + ", c => map("
                        + big
                        + ", d => a+b+c+d))))");
    }

    @Test
    void wallTimeLimitUsesTheInjectedClock() {
        AtomicLong now = new AtomicLong();
        Limits l =
                Limits.defaults()
                        .withNanoTime(() -> now.addAndGet(20_000_000L)); // every read costs 20 ms
        ExpressionException e = fail(l, "1+2+3+4");
        assertTrue(
                e.getMessage().toLowerCase().contains("long")
                        || e.getMessage().toLowerCase().contains("ms"),
                e.getMessage());
        // with a frozen clock nothing ever times out
        Limits frozen = Limits.defaults().withNanoTime(() -> 0L);
        assertEquals(10L, new ExpressionEngine(frozen).evaluate("1+2+3+4", ctx));
    }

    @Test
    void resultSizeLimit() {
        Limits l = Limits.defaults().withMaxResultChars(100);
        fail(l, "'x'.padStart(1000)");
        fail(
                l,
                "join(map([1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22], x => 'abcdefgh'), ',')");
        fail(
                l,
                "toJson(map([1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30], x => 'abcdefgh'))");
        assertEquals(
                100,
                ((String) new ExpressionEngine(l).evaluate("'x'.padStart(100, 'x')", ctx))
                        .length());
    }

    @Test
    void renderedOutputIsCappedToo() {
        Limits l = Limits.defaults().withMaxResultChars(50);
        ExpressionEngine e = new ExpressionEngine(l);
        String t = "{{ 'abcdefghij'.padStart(40, 'x') }}".repeat(20);
        assertThrows(ExpressionException.class, () -> e.render(t, ctx));
    }

    @Test
    void repeatLikeFunctionsCannotBlowUpMemory() {
        ExpressionEngine e = new ExpressionEngine();
        assertThrows(
                ExpressionException.class,
                () -> e.evaluate("padStart('x', 2000000000, 'ab')", ctx));
        assertThrows(ExpressionException.class, () -> e.evaluate("padStart('x', 100000000)", ctx));
        assertThrows(
                ExpressionException.class,
                () -> e.evaluate("join([1,2,3], padStart('', 300000, 'x'))", ctx));
        assertThrows(
                ExpressionException.class,
                () ->
                        e.evaluate(
                                "replace(padStart('', 200000, 'a'), 'a', padStart('', 200000, 'b'))",
                                ctx));
        assertThrows(
                ExpressionException.class,
                () ->
                        e.evaluate(
                                "base64(padStart('', 250000, 'a') + padStart('', 250000, 'a'))",
                                ctx));
    }

    @Test
    void regexPatternLengthCap() {
        ExpressionException e =
                fail(
                        Limits.defaults().withMaxRegexLength(10),
                        "regexReplace('abc', 'a{0}b{0}c{0}d{0}e{0}', 'x')");
        assertTrue(e.getMessage().toLowerCase().contains("regex"), e.getMessage());
        fail(Limits.defaults(), "regexReplace('abc', '" + "a".repeat(201) + "', 'x')");
        assertEquals("xbc", new ExpressionEngine().evaluate("regexReplace('abc', 'a', 'x')", ctx));
    }

    @Test
    void catastrophicRegexIsInterruptedByTheDeadline() {
        Limits l = Limits.defaults().withMaxWallNanos(100_000_000L);
        ExpressionEngine e = new ExpressionEngine(l);
        // JDK 9+ memoises a plain (a+)+$, so use a back-reference pattern, which is still
        // exponential.
        String subject = "a".repeat(30) + "!";
        long t0 = System.nanoTime();
        assertThrows(
                ExpressionException.class,
                () -> e.evaluate("regexReplace('" + subject + "', '(a+)+\\\\1$', 'x')", ctx));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 3_000, "ReDoS guard took " + ms + " ms");
    }

    @Test
    void invalidRegexAndFlagsAreCleanErrors() {
        fail(Limits.defaults(), "regexReplace('a', '(', 'x')");
        fail(Limits.defaults(), "regexReplace('a', 'a', 'x', 'z')");
    }

    @Test
    void deepNestingIsRejectedNotAStackOverflow() {
        ExpressionEngine e = new ExpressionEngine();
        assertThrows(
                ExpressionException.class,
                () -> e.evaluate("(".repeat(400) + "1" + ")".repeat(400), ctx));
        assertThrows(
                ExpressionException.class,
                () -> e.evaluate("[".repeat(400) + "]".repeat(400), ctx));
        assertThrows(ExpressionException.class, () -> e.evaluate("-".repeat(1500) + "1", ctx));
        assertThrows(ExpressionException.class, () -> e.evaluate("!".repeat(1500) + "1", ctx));
    }

    @Test
    void limitBreachesInRenderAreExpressionExceptions() {
        ExpressionEngine e = new ExpressionEngine(Limits.defaults().withMaxSteps(10));
        assertThrows(ExpressionException.class, () -> e.render("{{ 1+2+3+4+5+6+7+8+9+10 }}", ctx));
    }

    // ---- positions

    @Test
    void parseErrorsCarryPositions() {
        ExpressionException e = fail(Limits.defaults(), "1 + * 2");
        assertEquals(4, e.start());
        assertTrue(e.end() >= e.start());

        e = fail(Limits.defaults(), "foo(1, 2");
        assertTrue(e.start() >= 0 && e.start() <= "foo(1, 2".length(), "start " + e.start());

        e = fail(Limits.defaults(), "1 2");
        assertEquals(2, e.start());

        e = fail(Limits.defaults(), "'unterminated");
        assertEquals(0, e.start());

        e = fail(Limits.defaults(), "a @ b");
        assertEquals(2, e.start());
        assertEquals(3, e.end());
    }

    @Test
    void runtimeErrorsCarryThePositionOfTheFailingNode() {
        ExpressionException e = fail(Limits.defaults(), "1 + 1 / 0");
        assertEquals(4, e.start());
        assertEquals(9, e.end());

        e = fail(Limits.defaults(), "upper(parseJson('{'))");
        assertTrue(e.start() >= 0, "position filled in");
        assertNotNull(e.getMessage());

        e = fail(Limits.defaults(), "  nosuch(1)");
        assertEquals(2, e.start());
        assertTrue(e.getMessage().contains("nosuch"));
    }

    @Test
    void templatePositionsAreShiftedIntoTemplateSpace() {
        ExpressionEngine e = new ExpressionEngine();
        String t = "abc {{ 1 + * 2 }} def";
        ExpressionException ex = assertThrows(ExpressionException.class, () -> e.render(t, ctx));
        assertEquals(t.indexOf('*'), ex.start());

        String t2 = "x {{ 1 }} y {{ 1 / 0 }}";
        ex = assertThrows(ExpressionException.class, () -> e.render(t2, ctx));
        assertTrue(ex.start() >= t2.indexOf("1 / 0"), "start " + ex.start());
    }

    @Test
    void unknownFunctionSuggestsANearbyName() {
        ExpressionException e = fail(Limits.defaults(), "uper('a')");
        assertTrue(e.getMessage().contains("upper"), e.getMessage());
    }

    @Test
    void strictModeTurnsMissingPathsIntoErrors() {
        Limits strict = Limits.defaults().withStrict(true);
        fail(strict, "$json.missing.deeper");
        fail(strict, "$json.a.b.c");
        assertEquals(1L, new ExpressionEngine(strict).evaluate("$json.a", ctx));
        assertEquals(null, new ExpressionEngine(strict).evaluate("$json.missing?.deeper", ctx));
        assertEquals(null, new ExpressionEngine().evaluate("$json.missing.deeper", ctx));
    }

    @Test
    void emptyOrNullExpressionIsAnError() {
        ExpressionEngine e = new ExpressionEngine();
        assertThrows(ExpressionException.class, () -> e.evaluate(null, ctx));
        assertThrows(ExpressionException.class, () -> e.evaluate("", ctx));
        assertThrows(ExpressionException.class, () -> e.evaluate("   ", ctx));
    }
}
