package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Grammar, operators, typing rules, roots and template rendering (spec 3.1-3.3). */
class ExpressionLanguageTest {

    private static final String JSON =
            "{\"price\":10,\"qty\":3,\"name\":\"Ada\",\"flag\":true,\"nothing\":null,"
                    + "\"items\":[{\"p\":1.5,\"n\":\"a\"},{\"p\":2,\"n\":\"b\"}],\"nested\":{\"deep\":{\"v\":7}},\"d\":1.0}";
    // Generous wall time: these tests check semantics, not the 50 ms budget (LimitsAndErrorsTest
    // covers that).
    private final ExpressionEngine engine =
            new ExpressionEngine(Limits.defaults().withMaxWallNanos(10_000_000_000L));
    private final EvalContext ctx =
            BasicEvalContext.builder()
                    .input(JSON)
                    .clock(Clock.fixed(Instant.parse("2026-09-30T10:15:30Z"), ZoneOffset.UTC))
                    .node(
                            new NodeOutput(
                                    "n1",
                                    "Fetch",
                                    "{\"rows\":[{\"id\":1},{\"id\":2}],\"total\":2}",
                                    "SUCCESS",
                                    "2 rows"))
                    .node(new NodeOutput("n2", "Plain", "hello", "SUCCESS", "s"))
                    .trigger(Map.of("body", Map.of("x", 1L)))
                    .run(
                            Map.of(
                                    "id",
                                    "r1",
                                    "startedAt",
                                    "2026-09-30T10:00:00Z",
                                    "mode",
                                    "draft",
                                    "requestedBy",
                                    "u1"))
                    .workflow(Map.of("id", "w1", "name", "Flow", "version", 4L))
                    .vars(Map.of("rate", 0.18, "region", "IN"))
                    .loop(Map.of("sku", "Z"), 2L, List.of(1L, 2L, 3L))
                    .build();

    private String ev(String e) {
        return Values.toJson(engine.evaluate(e, ctx));
    }

    @Test
    void literals() {
        assertEquals("1", ev("1"));
        assertEquals("1.5", ev("1.5"));
        assertEquals("1000", ev("1e3"));
        assertEquals("\"a b\"", ev("'a b'"));
        assertEquals("\"q\\\"\"", ev("\"q\\\"\""));
        assertEquals("\"line\\n\"", ev("'line\\n'"));
        assertEquals("true", ev("true"));
        assertEquals("false", ev("false"));
        assertEquals("null", ev("null"));
        assertEquals("[1,\"a\",[2]]", ev("[1, 'a', [2]]"));
        assertEquals("[]", ev("[]"));
        assertEquals(
                "{\"k\":1,\"s\":\"v\",\"n\":{\"z\":null}}", ev("{k: 1, \"s\": 'v', n: {z: null}}"));
        assertEquals("{}", ev("{}"));
    }

    @Test
    void integerValuedNumbersStayLong() {
        assertInstanceOf(Long.class, engine.evaluate("2 + 3", ctx));
        assertInstanceOf(Long.class, engine.evaluate("6 / 3", ctx));
        assertInstanceOf(Long.class, engine.evaluate("2 * 3", ctx));
        assertInstanceOf(Long.class, engine.evaluate("7 % 4", ctx));
        assertInstanceOf(Double.class, engine.evaluate("7 / 2", ctx));
        assertEquals("3.5", ev("7 / 2"));
        assertEquals("3", ev("1.5 * 2"));
        assertEquals("0.30000000000000004", ev("0.1 + 0.2"));
        assertEquals("5", ev("$json.price / 2"));
    }

    @Test
    void arithmeticAndPrecedence() {
        assertEquals("7", ev("1 + 2 * 3"));
        assertEquals("9", ev("(1 + 2) * 3"));
        assertEquals("-3", ev("-3"));
        assertEquals("3", ev("--3"));
        assertEquals("-5", ev("1 - 2 * 3"));
        assertEquals("1", ev("10 % 3"));
        assertEquals("2", ev("10 - 4 - 4"));
        assertEquals("2.5", ev("5 / 2"));
        assertEquals("-2", ev("-(1 + 1)"));
        assertEquals("30", ev("$json.price * $json.qty"));
        assertEquals("35.4", ev("$json.price * $json.qty * 1.18"));
    }

    @Test
    void divisionByZeroIsAnError() {
        assertThrows(ExpressionException.class, () -> engine.evaluate("1 / 0", ctx));
        assertThrows(ExpressionException.class, () -> engine.evaluate("1 % 0", ctx));
    }

    @Test
    void plusConcatenatesWhenEitherSideIsAString() {
        assertEquals("\"a1\"", ev("'a' + 1"));
        assertEquals("\"1a\"", ev("1 + 'a'"));
        assertEquals("\"x1.5\"", ev("'x' + 1.5"));
        assertEquals("\"x\"", ev("'x' + null"));
        assertEquals("\"ab\"", ev("'a' + 'b'"));
        assertEquals("\"Ada is 3\"", ev("$json.name + ' is ' + $json.qty"));
    }

    @Test
    void comparisonAndEquality() {
        assertEquals("true", ev("1 < 2"));
        assertEquals("false", ev("2 < 2"));
        assertEquals("true", ev("2 <= 2"));
        assertEquals("true", ev("3 > 2"));
        assertEquals("true", ev("3 >= 3"));
        assertEquals("true", ev("'a' < 'b'"));
        assertEquals("true", ev("1 == 1.0"));
        assertEquals("true", ev("'a' == 'a'"));
        assertEquals("false", ev("'a' == 'b'"));
        assertEquals("true", ev("'a' != 'b'"));
        assertEquals("true", ev("null == null"));
        assertEquals("true", ev("[1,{a:2}] == [1,{a:2}]"));
        assertEquals("false", ev("[1,2] == [2,1]"));
        // strict on type except number vs numeric string
        assertEquals("true", ev("1 == '1'"));
        assertEquals("true", ev("'1.0' == 1"));
        assertEquals("false", ev("true == 'true'"));
        assertEquals("false", ev("0 == false"));
        assertEquals("false", ev("null == 0"));
        assertEquals("false", ev("'' == null"));
        assertEquals("true", ev("1 != 2"));
    }

    @Test
    void logicalOperatorsAndShortCircuit() {
        assertEquals("true", ev("true && true"));
        assertEquals("false", ev("true && false"));
        assertEquals("true", ev("false || true"));
        assertEquals("false", ev("!true"));
        assertEquals("true", ev("!0"));
        assertEquals("true", ev("!''"));
        // short circuit: right side would throw
        assertEquals("false", ev("false && 1 / 0"));
        assertEquals("true", ev("true || 1 / 0"));
        assertEquals("1", ev("null ?? 1"));
        assertEquals("0", ev("0 ?? 1"));
        assertEquals("\"\"", ev("'' ?? 'x'"));
        assertEquals("2", ev("null ?? null ?? 2"));
        assertEquals("1", ev("1 ?? 1 / 0"));
    }

    @Test
    void precedenceOfLogicalAndComparison() {
        assertEquals("true", ev("1 < 2 && 2 < 3 || false"));
        assertEquals("true", ev("false || true && true"));
        assertEquals("false", ev("(false || true) && false"));
        assertEquals("true", ev("1 + 1 == 2"));
        assertEquals("true", ev("null ?? false || true"));
    }

    @Test
    void ternary() {
        assertEquals("\"y\"", ev("true ? 'y' : 'n'"));
        assertEquals("\"n\"", ev("false ? 'y' : 'n'"));
        assertEquals("1", ev("true ? 1 : 1 / 0"));
        assertEquals("\"b\"", ev("false ? 'a' : true ? 'b' : 'c'"));
        assertEquals("\"big\"", ev("$json.price * $json.qty > 20 ? 'big' : 'small'"));
    }

    @Test
    void propertyAccessIndexAndOptionalChaining() {
        assertEquals("\"Ada\"", ev("$json.name"));
        assertEquals("7", ev("$json.nested.deep.v"));
        assertEquals("7", ev("$json['nested']['deep']['v']"));
        assertEquals("1.5", ev("$json.items[0].p"));
        assertEquals("\"b\"", ev("$json.items[-1].n"));
        assertEquals("\"a\"", ev("$json.items[-2].n"));
        assertEquals("null", ev("$json.items[5]"));
        assertEquals("null", ev("$json.items[-5]"));
        assertEquals("null", ev("$json.missing"));
        assertEquals("null", ev("$json.missing.deeper.still"));
        assertEquals("null", ev("$json.missing?.deeper"));
        assertEquals("null", ev("$json.nothing?.x"));
        assertEquals("null", ev("$json.nothing.x"));
        assertEquals("2", ev("$json.items.length"));
        assertEquals("3", ev("$json.name.length"));
        assertEquals("\"d\"", ev("'abc'[1] == 'b' ? 'd' : 'x'"));
        assertEquals("\"n/a\"", ev("$json.missing ?? 'n/a'"));
        assertEquals("\"n/a\"", ev("$json.nothing?.x ?? 'n/a'"));
    }

    @Test
    void methodCallSugarAndArrowFunctions() {
        assertEquals("[1.5,2]", ev("$json.items.map(i => i.p)"));
        assertEquals("[\"b\"]", ev("$json.items.filter(i => i.p > 1.5).map(i => i.n)"));
        assertEquals("3.5", ev("sum(map($json.items, i => i.p))"));
        assertEquals("\"A\"", ev("$json.items[0].n.upper()"));
        assertEquals("[2,4]", ev("[1,2].map(x => x * 2)"));
    }

    @Test
    void arrowFunctionsOnlyAsBuiltInArguments() {
        assertThrows(ExpressionException.class, () -> engine.evaluate("x => x", ctx));
        assertThrows(ExpressionException.class, () -> engine.evaluate("[x => x]", ctx));
        assertThrows(ExpressionException.class, () -> engine.evaluate("(x => x)(1)", ctx));
        assertThrows(ExpressionException.class, () -> engine.evaluate("{f: x => x}", ctx));
        assertThrows(ExpressionException.class, () -> engine.evaluate("(a, b) => a", ctx));
    }

    @Test
    void lambdaParameterShadowsAndScopes() {
        assertEquals("[2,3]", ev("[1,2].map(item => item + 1)"));
        assertEquals("[10,10]", ev("[1,2].map(input => 10)"));
    }

    @Test
    void rootsJsonInputAndNode() {
        assertEquals("\"Ada\"", ev("$json.name"));
        assertEquals(JSON, engine.evaluate("$input", ctx));
        assertEquals("2", ev("$node[\"Fetch\"].total"));
        assertEquals("2", ev("$node.Fetch.total"));
        assertEquals("2", ev("$node.id(\"n1\").total"));
        assertEquals("2", ev("$node['Fetch'].rows[1].id"));
        assertEquals("\"hello\"", ev("$node[\"Plain\"]"));
        assertEquals("\"hello\"", ev("$node[\"Plain\"].output"));
        assertEquals("\"SUCCESS\"", ev("$node[\"Fetch\"].status"));
        assertEquals("\"2 rows\"", ev("$node[\"Fetch\"].summary"));
        assertEquals(
                "\"{\\\"rows\\\":[{\\\"id\\\":1},{\\\"id\\\":2}],\\\"total\\\":2}\"",
                ev("$node[\"Fetch\"].output"));
        assertEquals("null", ev("$node[\"Unknown\"]"));
        assertEquals("null", ev("$node[\"Unknown\"].x.y"));
        assertEquals("null", ev("$node.id(\"zzz\")"));
    }

    @Test
    void rootsTriggerRunWorkflowVarsLoopNow() {
        assertEquals("1", ev("$trigger.body.x"));
        assertEquals("\"r1\"", ev("$run.id"));
        assertEquals("\"draft\"", ev("$run.mode"));
        assertEquals("\"u1\"", ev("$run.requestedBy"));
        assertEquals("\"2026-09-30T10:00:00Z\"", ev("$run.startedAt"));
        assertEquals("\"Flow\"", ev("$workflow.name"));
        assertEquals("4", ev("$workflow.version"));
        assertEquals("\"w1\"", ev("$workflow.id"));
        assertEquals("0.18", ev("$vars.rate"));
        assertEquals("\"IN\"", ev("$vars.region"));
        assertEquals("\"Z\"", ev("$item.sku"));
        assertEquals("2", ev("$index"));
        assertEquals("[1,2,3]", ev("$items"));
        assertEquals("\"2026-09-30T10:15:30Z\"", ev("$now"));
    }

    @Test
    void rootsAreNullOutsideTheirScope() {
        EvalContext bare = BasicEvalContext.ofInput("{}");
        for (String r :
                List.of(
                        "$trigger",
                        "$item",
                        "$index",
                        "$items",
                        "$run.id",
                        "$workflow.name",
                        "$vars.x",
                        "$node[\"A\"]")) {
            assertNull(engine.evaluate(r, bare), r);
        }
    }

    @Test
    void jsonRootIsRawStringForNonJsonInput() {
        EvalContext c = BasicEvalContext.ofInput("just words");
        assertEquals("just words", engine.evaluate("$json", c));
        assertEquals("just words", engine.evaluate("$input", c));
        assertEquals("JUST WORDS", engine.evaluate("upper($json)", c));
        EvalContext arr = BasicEvalContext.ofInput("[1,2,3]");
        assertEquals("3", Values.toJson(engine.evaluate("$json.length", arr)));
        assertEquals("2", Values.toJson(engine.evaluate("$json[1]", arr)));
    }

    @Test
    void bareNamesResolveAgainstTheInput() {
        assertEquals("\"Ada\"", ev("name"));
        assertEquals("30", ev("price * qty"));
        assertEquals("null", ev("missing"));
        assertEquals("\"Ada!\"", ev("name + '!'"));
        assertEquals("true", ev("flag"));
        assertEquals("true", ev("input == $input"));
    }

    // ---- template rendering

    private String r(String t) {
        return engine.render(t, ctx);
    }

    @Test
    void renderStringifiesByType() {
        assertEquals("Ada", r("{{$json.name}}"));
        assertEquals("30", r("{{ $json.price * $json.qty }}"));
        assertEquals("", r("{{ $json.nothing }}"));
        assertEquals("", r("{{ $json.missing }}"));
        assertEquals("true", r("{{ $json.flag }}"));
        assertEquals("[1,2]", r("{{ [1, 2] }}"));
        assertEquals("{\"a\":\"x\"}", r("{{ {a: 'x'} }}"));
        assertEquals("3", r("{{ 6 / 2 }}"));
        assertEquals("3", r("{{ 3.0 }}"));
        assertEquals("1.5", r("{{ 1.5 }}"));
        assertEquals("a=30;b=Ada", r("a={{ $json.price * $json.qty }};b={{ name }}"));
        assertEquals("tail }}", r("{{ 'x'.length() == 1 ? '' : 'no' }}tail }}"));
    }

    @Test
    void renderHandlesBracesInsideStringLiterals() {
        assertEquals("}}", r("{{ '}}' }}"));
        assertEquals("{{", r("{{ '{{' }}"));
        assertEquals("a}}b", r("{{ 'a}}b' }}"));
        assertEquals("x", r("{{ \"}}\" == '}}' ? 'x' : 'y' }}"));
        assertEquals("{\"a\":1}", r("{{ {a: 1} }}"));
        assertEquals("{\"a\":{\"b\":2}}", r("{{ {a: {b: 2}} }}"));
    }

    @Test
    void renderLeavesMalformedOpenersAlone() {
        assertEquals("a {{ b", r("a {{ b"));
        assertEquals("{{}}", r("{{}}"));
        assertEquals("{{ }}", r("{{ }}"));
        assertEquals("{{", r("{{"));
        assertEquals("}}", r("}}"));
        assertEquals("{{ $json.name", r("{{ $json.name"));
    }

    @Test
    void renderValueKeepsTypeOnlyForAnExactSingleExpression() {
        assertEquals(30L, engine.renderValue("{{ $json.price * $json.qty }}", ctx));
        assertEquals(Boolean.TRUE, engine.renderValue("{{$json.flag}}", ctx));
        assertEquals("Ada", engine.renderValue("{{ name }}", ctx));
        assertInstanceOf(List.class, engine.renderValue("{{ $json.items }}", ctx));
        assertInstanceOf(Map.class, engine.renderValue("{{ $json.nested }}", ctx));
        assertNull(engine.renderValue("{{ $json.nothing }}", ctx));
        // any other character (even whitespace) means text
        assertEquals("30 ", engine.renderValue("{{ $json.price * $json.qty }} ", ctx));
        assertEquals(" 30", engine.renderValue(" {{ $json.price * $json.qty }}", ctx));
        assertEquals("a30", engine.renderValue("a{{ $json.price * $json.qty }}", ctx));
        assertEquals("3030", engine.renderValue("{{ 30 }}{{ 30 }}", ctx));
        assertEquals("plain", engine.renderValue("plain", ctx));
        assertEquals("", engine.renderValue(null, ctx));
        // unresolved legacy name stays the literal template
        assertEquals("{{nope}}", engine.renderValue("{{nope}}", ctx));
    }

    @Test
    void renderValueUsesTheOriginalJacksonNodeForLegacyFields() {
        assertEquals(Double.valueOf(1.0), engine.renderValue("{{d}}", ctx));
        assertEquals("1.0", engine.render("{{d}}", ctx));
        assertEquals("10", engine.render("{{price}}", ctx));
        assertEquals(
                "[{\"p\":1.5,\"n\":\"a\"},{\"p\":2,\"n\":\"b\"}]", engine.render("{{items}}", ctx));
    }

    @Test
    void nullTemplateRendersEmpty() {
        assertEquals("", engine.render(null, ctx));
    }
}
