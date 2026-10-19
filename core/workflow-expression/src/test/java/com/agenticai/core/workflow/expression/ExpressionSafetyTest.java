package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the review findings on resource limits, scanner cost and input-key
 * shadowing.
 */
class ExpressionSafetyTest {

    private final ExpressionEngine engine = new ExpressionEngine();

    @BeforeAll
    static void warmUp() {
        // Class loading (Jackson, parser) on a cold JVM can exceed the 50 ms evaluation budget on
        // its own;
        // spend it here, tolerating the budget error, so the tests measure steady-state behaviour.
        var engine = new ExpressionEngine();
        var warm = BasicEvalContext.ofInput("{\"a\":1}");
        for (int i = 0; i < 3; i++) {
            try {
                engine.render("{{ $json.a == 1 }} {{ lower('X') }}", warm);
            } catch (ExpressionException ignored) {
                // cold start; next iteration is warm
            }
        }
    }

    /**
     * A value with about {@code depth} real nodes but 2^depth logical nodes, built only from
     * arrow-parameter sharing.
     */
    private static String dag(int depth) {
        StringBuilder sb = new StringBuilder();
        String prev = "0";
        sb.append("first(map([[0,0]], a1 => ");
        for (int k = 1; k < depth; k++) {
            sb.append("first(map([[a")
                    .append(k)
                    .append(",a")
                    .append(k)
                    .append("]], a")
                    .append(k + 1)
                    .append(" => ");
        }
        sb.append("a").append(depth);
        for (int k = 0; k < depth; k++) {
            sb.append("))");
        }
        return sb.toString();
    }

    private static final String DAG = dag(27);

    private long millis(Runnable r) {
        long t = System.nanoTime();
        r.run();
        return (System.nanoTime() - t) / 1_000_000;
    }

    @Test
    void dagSourceIsWithinDeclaredLimits() {
        assertTrue(DAG.length() < 2000, "fixture must fit the source limit, was " + DAG.length());
    }

    @Test
    void dagStringificationFailsFastInsteadOfExhaustingMemory() {
        var ctx = BasicEvalContext.ofInput("");
        long ms =
                millis(
                        () ->
                                assertThrows(
                                        ExpressionException.class,
                                        () -> engine.render("{{ lower(" + DAG + ") }}", ctx)));
        assertTrue(ms < 2000, "took " + ms + " ms");
    }

    @Test
    void dagEqualityIsBudgeted() {
        var ctx = BasicEvalContext.ofInput("");
        long ms =
                millis(
                        () ->
                                assertThrows(
                                        ExpressionException.class,
                                        () -> engine.evaluate(DAG + " == " + DAG, ctx)));
        assertTrue(ms < 2000, "took " + ms + " ms");
    }

    @Test
    void dagUniqueAndContainsAreBounded() {
        var ctx = BasicEvalContext.ofInput("");
        assertThrows(
                ExpressionException.class,
                () -> engine.evaluate("length(unique([" + DAG + "]))", ctx));
        assertThrows(
                ExpressionException.class,
                () -> engine.evaluate("contains([" + DAG + "], " + DAG + ")", ctx));
    }

    @Test
    void splitIntoCharactersIsCapped() {
        var ctx = BasicEvalContext.ofInput("x".repeat(200_000));
        assertThrows(
                ExpressionException.class, () -> engine.evaluate("length(split($input, ''))", ctx));
    }

    @Test
    void manyUnmatchedOpenersScanInBoundedTime() {
        var ctx = BasicEvalContext.ofInput("");
        String t = "{{ '".repeat(25_000) + " }}";
        long ms = millis(() -> engine.render(t, ctx));
        assertTrue(ms < 1500, "took " + ms + " ms");
        String braces = "{".repeat(300_000) + "}}";
        ms = millis(() -> engine.render(braces, ctx));
        assertTrue(ms < 1500, "took " + ms + " ms");
    }

    @Test
    void strayOpenerBeforeLongJsonStaysLiteral() {
        var ctx = BasicEvalContext.ofInput("");
        String t = "Use {{ to open. " + "x".repeat(3000) + " {\"a\":{\"b\":1}}";
        assertEquals(t, engine.render(t, ctx));
    }

    @Test
    void inputKeyCannotShadowAnExpression() {
        var ctx =
                BasicEvalContext.ofInput("{\"role\":\"user\",\"$json.role == 'admin'\":\"true\"}");
        assertEquals("false", engine.render("{{$json.role == 'admin'}}", ctx));
        assertEquals(false, engine.renderValue("{{$json.role == 'admin'}}", ctx));
    }

    @Test
    void legacyKeysWithSpacesStillResolve() {
        var ctx = BasicEvalContext.ofInput("{\"first name\":\"Ada\"}");
        assertEquals("Ada", engine.render("{{first name}}", ctx));
    }

    // JDK case mapping is quadratic for U+00DF (upper) and U+0130 (lower); the wall cap must still
    // fire.
    @Test
    void caseMappingOfHostileInputHitsTheWallCapInsteadOfRunningForMinutes() {
        for (String fn : new String[] {"upper", "lower"}) {
            String hostile = (fn.equals("upper") ? "\u00DF" : "\u0130").repeat(200_000);
            var ctx = BasicEvalContext.ofInput(hostile);
            long ms =
                    millis(
                            () ->
                                    assertThrows(
                                            ExpressionException.class,
                                            () -> engine.render("{{ " + fn + "($input) }}", ctx)));
            assertTrue(ms < 3_000, fn + " took " + ms + " ms");
        }
    }

    @Test
    void chunkedCaseMappingMatchesTheJdkOnOrdinaryText() {
        String text =
                ("Stra\u00DFe \u039F\u0394\u03A5\u03A3\u03A3\u0395\u03A5\u03A3 hello World \u0130stanbul ")
                        .repeat(120);
        var ctx = BasicEvalContext.ofInput(text);
        assertEquals(
                text.toUpperCase(java.util.Locale.ROOT), engine.render("{{ upper($input) }}", ctx));
        assertEquals(
                text.toLowerCase(java.util.Locale.ROOT), engine.render("{{ lower($input) }}", ctx));
    }

    @Test
    void errorMessagesNeverEchoTheOffendingDataAndStayShort() {
        String secret = "sk_live_SECRET1234";
        for (String expr :
                new String[] {
                    "parseDate($input, 'yyyy-MM-dd')",
                    "formatDate($input)",
                    "addDays($input, 1)",
                    "toEpoch($input)",
                    "diffDays($input, $input)",
                    "formatDate('2026-01-01T00:00:00Z', 'yyyy', $input)"
                }) {
            var ex =
                    assertThrows(
                            ExpressionException.class,
                            () ->
                                    engine.render(
                                            "{{ " + expr + " }}", BasicEvalContext.ofInput(secret)),
                            expr);
            assertTrue(!ex.getMessage().contains(secret), expr + " leaked: " + ex.getMessage());
        }
        var big =
                assertThrows(
                        ExpressionException.class,
                        () ->
                                engine.render(
                                        "{{ formatDate($input) }}",
                                        BasicEvalContext.ofInput("x".repeat(200_000))));
        assertTrue(
                big.getMessage().length() <= ExpressionException.MAX_MESSAGE_CHARS + 3,
                "was " + big.getMessage().length());
    }
}
