package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Seeded random token soup (only ExpressionException may escape, always fast) and Java-reachability
 * probes.
 */
class ExpressionFuzzAndSecurityTest {

    private static final String[] TOKENS = {
        "1",
        "0",
        "-1",
        "2.5",
        "1e3",
        "'a'",
        "\"b\"",
        "''",
        "true",
        "false",
        "null",
        "$json",
        "$input",
        "$node",
        "$now",
        "$item",
        "$vars",
        "$run",
        "$trigger",
        "a",
        "b",
        "input",
        "name",
        "x",
        "+",
        "-",
        "*",
        "/",
        "%",
        "!",
        "==",
        "!=",
        "<",
        ">",
        "<=",
        ">=",
        "&&",
        "||",
        "??",
        "?",
        ":",
        "?.",
        ".",
        ",",
        "(",
        ")",
        "[",
        "]",
        "{",
        "}",
        "=>",
        "map",
        "filter",
        "sort",
        "join",
        "split",
        "regexReplace",
        "padStart",
        "if",
        "coalesce",
        "sum",
        "get",
        "formatDate",
        "parseJson",
        "'(a+)+$'",
        "'\\'",
        "\"\\u00",
        "{{",
        "}}",
        "`",
        "@",
        "#",
        "\\",
        " ",
        " ",
        "\n",
        "x => x",
        "i => i.p"
    };

    private static final String ALPHABET = "{}$'\" ab.()[]=>?:,0-+\\";

    private final ExpressionEngine engine = new ExpressionEngine();
    private final EvalContext ctx =
            BasicEvalContext.builder()
                    .input("{\"a\":1,\"b\":[1,2,3],\"name\":\"Ada\"}")
                    .node(new NodeOutput("n1", "A", "{\"x\":[1,2]}", "OK", "s"))
                    .loop(1L, 0L, List.of(1L, 2L))
                    .build();

    @BeforeAll
    static void warmUp() {
        ExpressionEngine e = new ExpressionEngine();
        for (int i = 0; i < 3; i++) {
            try {
                e.render(
                        "{{ map([1,2], x => x * 2) }} {{ name }}",
                        BasicEvalContext.ofInput("{\"name\":1}"));
            } catch (ExpressionException ignored) {
                // cold
            }
        }
    }

    @Test
    void randomTokenSoupOnlyEverThrowsExpressionException() {
        Random rnd = new Random(20260930L);
        long worst = 0;
        for (int i = 0; i < 2000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = 1 + rnd.nextInt(24);
            for (int k = 0; k < n; k++) {
                sb.append(TOKENS[rnd.nextInt(TOKENS.length)]);
                if (rnd.nextInt(3) == 0) {
                    sb.append(' ');
                }
            }
            String src = sb.toString();
            long t0 = System.nanoTime();
            try {
                engine.evaluate(src, ctx);
            } catch (ExpressionException expected) {
                assertTrue(expected.start() >= -1);
            } catch (Throwable t) {
                throw new AssertionError("evaluate escaped " + t + " for: " + src, t);
            }
            try {
                engine.render("pre {{" + src + "}} post", ctx);
            } catch (ExpressionException expected) {
                // fine
            } catch (Throwable t) {
                throw new AssertionError("render escaped " + t + " for: " + src, t);
            }
            try {
                engine.renderValue("{{" + src + "}}", ctx);
                engine.lint("{{" + src + "}} {{ " + src + " }}", LintContext.empty());
                engine.referencedNodeLabels("{{" + src + "}}");
            } catch (ExpressionException expected) {
                // fine
            } catch (Throwable t) {
                throw new AssertionError("renderValue/lint escaped " + t + " for: " + src, t);
            }
            worst = Math.max(worst, (System.nanoTime() - t0) / 1_000_000);
        }
        assertTrue(worst < 1000, "slowest iteration " + worst + " ms");
    }

    @Test
    void randomBytesAsTemplates() {
        Random rnd = new Random(7);
        for (int i = 0; i < 500; i++) {
            char[] cs = new char[rnd.nextInt(80)];
            for (int k = 0; k < cs.length; k++) {
                cs[k] = ALPHABET.charAt(rnd.nextInt(ALPHABET.length()));
            }
            String t = new String(cs);
            try {
                engine.render(t, ctx);
                engine.renderValue(t, ctx);
                engine.lint(t, LintContext.empty());
            } catch (ExpressionException expected) {
                // fine
            } catch (Throwable th) {
                throw new AssertionError("escaped " + th + " for template: " + t, th);
            }
        }
    }

    // ---- security

    private Object probe(String expr) {
        try {
            return engine.evaluate(expr, ctx);
        } catch (ExpressionException e) {
            return e;
        }
    }

    private void assertInert(String expr) {
        Object v = probe(expr);
        assertTrue(v == null || v instanceof ExpressionException, expr + " -> " + v);
    }

    @Test
    void javaReflectionSurfaceIsUnreachable() {
        for (String e :
                List.of(
                        "getClass",
                        "$json.getClass",
                        "$json.class",
                        "'x'.getClass()",
                        "name.getClass()",
                        "constructor",
                        "$json.constructor",
                        "'x'.constructor",
                        "$json.constructor.constructor",
                        "__proto__",
                        "$json.__proto__",
                        "$json['__proto__']",
                        "$json.prototype",
                        "this",
                        "$node.getClass",
                        "$input.getClass",
                        "$json.hashCode",
                        "$json.toString",
                        "'x'.class",
                        "[].getClass()",
                        "Runtime",
                        "System",
                        "java",
                        "Class",
                        "$json.a.getClass",
                        "$now.getClass",
                        "$item.getClass")) {
            assertInert(e);
        }
    }

    @Test
    void methodsThatAreNotBuiltInsAreErrors() {
        for (String e :
                List.of(
                        "'x'.getBytes()",
                        "'x'.length",
                        "$json.exec('ls')",
                        "name.toString()",
                        "[].size()",
                        "name.charAt(0)",
                        "name.intern()",
                        "name.getClass().forName('java.lang.Runtime')")) {
            Object v = probe(e);
            // 'x'.length and toString() are legitimate; everything else must not reach Java.
            if (!e.equals("'x'.length") && !e.equals("name.toString()")) {
                assertTrue(v instanceof ExpressionException || v == null, e + " -> " + v);
            }
        }
        assertEquals(1L, probe("'x'.length"));
    }

    @Test
    void plainMapLookupOfMagicNamesIsJustDataAccess() {
        EvalContext c =
                BasicEvalContext.ofInput(
                        "{\"constructor\":\"data\",\"__proto__\":\"p\",\"class\":\"c\"}");
        assertEquals("data", engine.evaluate("$json.constructor", c));
        assertEquals("p", engine.evaluate("$json.__proto__", c));
        assertEquals("c", engine.evaluate("$json.class", c));
        assertNull(engine.evaluate("$json.getClass", c));
    }

    @Test
    void valuesAreNeverJavaObjects() {
        Object v = probe("{a: [1, 'x', null, true, 1.5, {b: 2}]}");
        assertFalse(v instanceof ExpressionException);
        String typeName = v.getClass().getName();
        assertTrue(typeName.startsWith("java.util."), typeName);
    }

    @Test
    void unsafeRootsAndAssignmentsDoNotParse() {
        for (String e :
                List.of(
                        "$json.a = 2",
                        "a = 1",
                        "new Object()",
                        "function(){}",
                        "import('x')",
                        "eval('1')",
                        "a; b",
                        "`x`",
                        "a ${b}",
                        "$env",
                        "$secrets.key",
                        "$process",
                        "delete a")) {
            Object v = probe(e);
            assertTrue(v instanceof ExpressionException || v == null, e + " -> " + v);
        }
    }

    @Test
    void evaluationNeverMutatesTheContext() {
        Object before = probe("$json");
        probe("sort($json.b, 'desc')");
        probe("merge($json, {z: 1})");
        probe("reverse($json.b)");
        probe("$json.b.map(x => x + 1)");
        assertEquals(Values.toJson(before), Values.toJson(probe("$json")));
        assertEquals("[1,2,3]", Values.toJson(probe("$json.b")));
    }

    @Test
    void theEngineIsSafeToShareAcrossThreads() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<String>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 64; i++) {
                final int n = i;
                fs.add(
                        pool.submit(
                                () ->
                                        engine.render(
                                                "{{ $json.b.map(x => x * " + n + ").join('-') }}",
                                                ctx)));
            }
            for (int i = 0; i < 64; i++) {
                assertEquals(i + "-" + (2 * i) + "-" + (3 * i), fs.get(i).get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
