package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the security/compat review round 2 (flatten DAG, split cost, budget vs I/O).
 */
class ReviewFixesTest {

    private static String flattenDag(int k) {
        StringBuilder sb = new StringBuilder("flatten([[]]");
        for (int i = 0; i < k; i++) {
            sb.append(".map(a=>[a,a])");
        }
        return sb.append(", 64)").toString();
    }

    @Test
    void flattenOverSharedReferenceDagFailsFastInsteadOfSpinning() {
        ExpressionEngine e = new ExpressionEngine();
        String expr = flattenDag(40);
        assertTrue(expr.length() < 2000, "fixture must fit the source limit");
        long t = System.nanoTime();
        assertThrows(
                ExpressionException.class, () -> e.evaluate(expr, BasicEvalContext.ofInput("")));
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertTrue(
                ms < 1_000, "flatten over a DAG must hit the wall cap quickly, took " + ms + " ms");
    }

    @Test
    void splitOfAWideCsvLineIsNotChargedQuadratically() {
        ExpressionEngine e = new ExpressionEngine();
        String csv = String.join(",", java.util.Collections.nCopies(3000, "x"));
        Object n = e.evaluate("length(split($input, ','))", BasicEvalContext.ofInput(csv));
        assertEquals(3000.0, ((Number) n).doubleValue());
    }

    @Test
    void slowContextLookupsAreNotBilledToTheWallBudget() {
        AtomicLong now = new AtomicLong();
        Limits l = Limits.defaults().withNanoTime(now::get);
        ExpressionEngine e = new ExpressionEngine(l);
        EvalContext slow =
                new EvalContext() {
                    @Override
                    public String input() {
                        return "";
                    }

                    @Override
                    public NodeOutput nodeByLabel(String label) {
                        now.addAndGet(5_000_000_000L); // a 5 s cold query
                        return new NodeOutput("n1", label, "{\"id\":7}", "SUCCESS", null);
                    }
                };
        assertEquals("n1", e.evaluate("$node[\"Create\"].id", slow));
        // but time spent evaluating still counts
        EvalContext ticking =
                () -> {
                    now.addAndGet(100_000_000L);
                    return "{}";
                };
        assertThrows(
                ExpressionException.class,
                () -> e.evaluate("$json.a + 1 + $json.b + $input", ticking));
    }

    private static Object eval(String expr) {
        return new ExpressionEngine().evaluate(expr, BasicEvalContext.ofInput(""));
    }

    @Test
    void operatorPrecedenceAndAssociativity() {
        assertEquals(true, eval("true || false && false"), "&& binds tighter than ||");
        assertEquals(false, eval("(true || false) && false"));
        assertEquals(
                0.0,
                ((Number) eval("0 ?? 1 || 2")).doubleValue(),
                "?? binds looser than ||: 0 ?? (1 || 2)");
        assertEquals("y", eval("1 + 2 == 3 && 4 > 3 ? 'y' : 'n'"));
        assertEquals(2.0, ((Number) eval("true ? false ? 1 : 2 : 3")).doubleValue());
        assertEquals(
                3.0,
                ((Number) eval("false ? 1 : false ? 2 : 3")).doubleValue(),
                "ternary is right-associative");
        assertEquals(-6.0, ((Number) eval("-2 * 3")).doubleValue());
        assertEquals(7.0, ((Number) eval("1 + 2 * 3")).doubleValue());
    }

    @Test
    void strictModeRejectsReadsThroughNullAndScalarsButOptionalChainingStillWorks() {
        ExpressionEngine strict = new ExpressionEngine(Limits.defaults().withStrict(true));
        ExpressionEngine lenient = new ExpressionEngine();
        EvalContext ctx = BasicEvalContext.ofInput("{\"n\":5,\"s\":\"x\"}");

        for (String expr :
                new String[] {
                    "$json.missing.b",
                    "$json.missing[0]",
                    "$json.n.b",
                    "$json.n[0]",
                    "$json.missing[\"k\"]"
                }) {
            assertThrows(ExpressionException.class, () -> strict.evaluate(expr, ctx), expr);
            assertEquals(
                    null, lenient.evaluate(expr, ctx), "lenient mode reads " + expr + " as null");
        }
        assertEquals(null, strict.evaluate("$json.missing?.b", ctx));
        assertEquals(null, strict.evaluate("$json.missing?.[0]", ctx));
        assertEquals("x", strict.evaluate("$json.s", ctx));
    }

    @Test
    void concatenationBeyondTheResultCapFails() {
        ExpressionEngine small = new ExpressionEngine(Limits.defaults().withMaxResultChars(50));
        EvalContext ctx =
                BasicEvalContext.ofInput(
                        "{\"a\":\"" + "x".repeat(30) + "\",\"b\":\"" + "y".repeat(30) + "\"}");
        assertThrows(ExpressionException.class, () -> small.evaluate("$json.a + $json.b", ctx));
    }

    @Test
    void firstEvaluationOnAFreshEngineDoesNotPayClassInitInItsBudget() {
        ExpressionEngine e = new ExpressionEngine();
        assertEquals(
                2.0,
                ((Number) e.evaluate("$json.a + 1", BasicEvalContext.ofInput("{\"a\":1}")))
                        .doubleValue());
    }
}
