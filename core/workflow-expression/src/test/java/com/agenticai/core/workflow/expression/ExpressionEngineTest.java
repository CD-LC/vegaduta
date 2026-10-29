package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.expression.Diagnostic.Severity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * {@link ExpressionEngine} facade details the language/built-in suites do not exercise: the
 * render {@code post} hook, {@code renderValue} edge cases, the result-size exemption for pure
 * paths, node-reference collection and the error conversion wrapper.
 */
class ExpressionEngineTest {

    private static final String JSON = "{\"name\":\"Ada\",\"x\":5,\"items\":[1,2]}";

    private final ExpressionEngine engine =
            new ExpressionEngine(Limits.defaults().withMaxWallNanos(10_000_000_000L));
    private final EvalContext ctx = BasicEvalContext.ofInput(JSON);

    @Test
    void limitsReturnsTheConfiguredLimits() {
        Limits limits = Limits.defaults().withMaxSteps(7);
        assertSame(limits, new ExpressionEngine(limits).limits());
    }

    @Test
    void warmUpIsIdempotent() {
        ExpressionEngine.warmUp();
        ExpressionEngine.warmUp();
    }

    @Test
    void evaluateRequiresAContext() {
        assertThrows(NullPointerException.class, () -> engine.evaluate("1", null));
        assertThrows(NullPointerException.class, () -> engine.render("{{ 1 }}", null));
        assertThrows(NullPointerException.class, () -> engine.renderValue("{{ 1 }}", null));
    }

    @Test
    void strayRuntimeFailuresBecomeExpressionExceptions() {
        EvalContext broken =
                () -> {
                    throw new IllegalStateException("store is down");
                };
        ExpressionException e =
                assertThrows(ExpressionException.class, () -> engine.evaluate("$json", broken));
        assertTrue(e.getMessage().contains("Internal expression error"));
    }

    @Test
    void renderPostProcessesSubstitutionsButNeverLiteralText() {
        assertEquals("ADA", engine.render("{{ $json.name }}", ctx, false, String::toUpperCase));
        assertEquals(
                "hi ADA!",
                engine.render("hi {{ $json.name }}!", ctx, false, String::toUpperCase),
                "literal text is not passed through the post operator");
        assertEquals("plain", engine.render("plain", ctx, false, String::toUpperCase));
        assertEquals("", engine.render(null, ctx, false, String::toUpperCase));
    }

    @Test
    void renderValueOfAnExactSingleExpressionKeepsItsType() {
        assertEquals(5L, engine.renderValue("{{ $json.x }}", ctx));
        assertEquals("Ada", engine.renderValue("{{ $json.name }}", ctx));
        assertInstanceOf(List.class, engine.renderValue("{{ $json.items }}", ctx));
        assertEquals("", engine.renderValue(null, ctx), "null renders as an empty string");
        assertNull(engine.renderValue("{{ $json.missing }}", ctx));
    }

    @Test
    void renderValueTreatsAnythingAroundTheSpanAsText() {
        assertEquals(" 5", engine.renderValue(" {{ $json.x }}", ctx));
        assertEquals("5 ", engine.renderValue("{{ $json.x }} ", ctx));
        assertEquals("a5", engine.renderValue("a{{ $json.x }}", ctx));
        assertEquals("55", engine.renderValue("{{ $json.x }}{{ $json.x }}", ctx));
        assertEquals("plain", engine.renderValue("plain", ctx));
    }

    @Test
    void tripleBracesKeepTheirHistoricReading() {
        // "{{{x}}}" is "{" + {{x}} + "}" exactly as the legacy replacer saw it.
        assertEquals("{5}", engine.renderValue("{{{x}}}", ctx));
        assertEquals("{5}", engine.render("{{{x}}}", ctx));
    }

    @Test
    void legacyLiteralKeepsBrokenNonExpressionSpansUntouched() {
        // With legacyLiteral a bare-name span that cannot parse stays literal text.
        assertEquals("{{ a + }}", engine.renderValue("{{ a + }}", ctx, true));
        assertEquals("{{ a + }}", engine.render("{{ a + }}", ctx, true));
        // ...but a broken span that uses a $ root is a real error, not legacy text.
        assertThrows(
                ExpressionException.class, () -> engine.renderValue("{{ $json + }}", ctx, true));
    }

    @Test
    void purePathsAreExemptFromTheResultSizeCap() {
        String bigInput = "{\"blob\":\"" + "x".repeat(500) + "\"}";
        ExpressionEngine tiny =
                new ExpressionEngine(
                        Limits.defaults()
                                .withMaxResultChars(16)
                                .withMaxWallNanos(10_000_000_000L));
        EvalContext big = BasicEvalContext.ofInput(bigInput);
        Object v = tiny.evaluate("$json.blob", big);
        assertEquals(500, ((String) v).length(), "a pure property path is exempt from the cap");
        assertThrows(
                ExpressionException.class,
                () -> tiny.evaluate("$json.blob + '!'", big),
                "a computed string is not");
    }

    @Test
    void looksLikeLegacyKeyRejectsExpressionSyntax() {
        assertTrue(ExpressionEngine.looksLikeLegacyKey("first name"));
        assertTrue(ExpressionEngine.looksLikeLegacyKey("name.with-dots"));
        for (String c : List.of("$", "(", ")", "[", "]", "'", "\"", "=", "<", ">", "!", "&", "|", "?", ":", ",", "`")) {
            assertFalse(ExpressionEngine.looksLikeLegacyKey("a" + c + "b"), c);
        }
    }

    @Test
    void referencedNodeLabelsFindsEverySpellingInsideLambdasToo() {
        Set<String> labels =
                engine.referencedNodeLabels(
                        "{{ $node[\"Fetch\"].x }} {{ $node.Lookup.y }} {{ $json.a.map(i => $node.Inner.z) }}");
        assertEquals(Set.of("Fetch", "Lookup", "Inner"), labels);
        assertTrue(engine.referencedNodeLabels("plain text").isEmpty());
    }

    @Test
    void referencedNodeIdsFindsIdCallsAndSkipsBrokenSpans() {
        assertEquals(
                Set.of("n7", "n8"),
                engine.referencedNodeIds("{{ $node.id(\"n7\").x }} {{ $node.id('n8') }} {{ 1 + }}"));
        assertTrue(engine.referencedNodeIds(null).isEmpty());
        assertTrue(engine.referencedNodeIds("{{ $node[\"L\"].x }}").isEmpty());
    }

    @Test
    void lintReturnsRecordDiagnostics() {
        List<Diagnostic> found =
                engine.lint("{{ $jsn }}", new LintContext(Map.of(), Set.of(), Set.of()));
        assertEquals(1, found.size());
        Diagnostic d = found.get(0);
        assertEquals(Severity.ERROR, d.severity());
        assertEquals("UNKNOWN_ROOT", d.code());
        assertTrue(d.message().contains("$jsn"));
        assertTrue(d.start() >= 0 && d.end() > d.start());
    }

    @Test
    void lintShiftsParseErrorPositionsIntoTemplateSpace() {
        List<Diagnostic> found = engine.lint("ok {{ 1 + }} tail", LintContext.empty());
        assertEquals(1, found.size());
        Diagnostic d = found.get(0);
        assertEquals("PARSE_ERROR", d.code());
        assertEquals(Severity.ERROR, d.severity());
        assertTrue(d.start() >= 3 && d.end() <= 11, "positions are inside the template");
    }

    @Test
    void diagnosticAndLintContextArePlainRecords() {
        Diagnostic d = new Diagnostic(Severity.WARNING, "CODE", "msg", 1, 4);
        assertEquals(Severity.WARNING, d.severity());
        assertEquals("CODE", d.code());
        assertEquals("msg", d.message());
        assertEquals(1, d.start());
        assertEquals(4, d.end());
        LintContext empty = LintContext.empty();
        assertNull(empty.labelToId());
        assertNull(empty.ambiguousLabels());
        assertNull(empty.knownInputFields());
    }

    @Test
    void evaluationHonoursTheInjectedWallClock() {
        AtomicLong nanos = new AtomicLong(0);
        ExpressionEngine dying =
                new ExpressionEngine(
                        Limits.defaults()
                                .withNanoTime(nanos::getAndIncrement)
                                .withMaxWallNanos(0));
        assertThrows(ExpressionException.class, () -> dying.evaluate("1 + 1", ctx));
    }
}
