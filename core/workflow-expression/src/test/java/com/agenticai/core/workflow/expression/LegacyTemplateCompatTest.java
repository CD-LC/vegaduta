package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.json.JsonNodes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Backward-compatibility gate (spec 3.1). {@link #oldRender} is a verbatim copy of the
 * pre-expression algorithm (WorkflowNodeExecutor.renderTemplate feeding
 * WorkflowNodeSupport.applyTemplate); the new engine must produce byte-identical output for every
 * legacy template.
 */
class LegacyTemplateCompatTest {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private final ExpressionEngine engine = new ExpressionEngine();

    // ---- reference copy of the OLD algorithm (do not "fix" this)

    static String oldRender(String template, String input) {
        Map<String, String> values = new HashMap<>();
        values.put("input", input == null ? "" : input);
        try {
            JsonNode root = MAPPER.readTree(input == null || input.isBlank() ? "{}" : input);
            if (root.isObject()) {
                JsonNodes.fields(root)
                        .forEachRemaining(
                                e ->
                                        values.put(
                                                e.getKey(),
                                                e.getValue().isString()
                                                        ? e.getValue().asString()
                                                        : e.getValue().toString()));
            }
        } catch (Exception ignored) {
            // not JSON
        }
        return oldApplyTemplate(template, values);
    }

    static String oldApplyTemplate(String template, Map<String, String> values) {
        String result = template == null ? "" : template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result =
                    result.replace(
                            "{{" + entry.getKey() + "}}",
                            entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    private static final String OBJ =
            "{\"name\":\"Ada\",\"age\":36,\"score\":1.0,\"big\":1e3,\"ok\":true,\"gone\":null,"
                    + "\"tags\":[\"a\",\"b\"],\"addr\":{\"city\":\"Pune\",\"zip\":\"411001\"},\"text\":\"line1\\nline2\",\"uni\":\"caf\\u00e9\"}";

    /**
     * {template, input} pairs with no whitespace inside the braces (the old code never matched
     * those).
     */
    private static final List<String[]> PAIRS =
            List.of(
                    new String[] {"{{input}}", OBJ},
                    new String[] {"Hello {{name}}", OBJ},
                    new String[] {"{{name}} is {{age}}", OBJ},
                    new String[] {"{{score}}", OBJ},
                    new String[] {"{{big}}", OBJ},
                    new String[] {"{{ok}}", OBJ},
                    new String[] {"{{gone}}", OBJ},
                    new String[] {"{{tags}}", OBJ},
                    new String[] {"{{addr}}", OBJ},
                    new String[] {"{{text}}", OBJ},
                    new String[] {"{{uni}}", OBJ},
                    new String[] {"{{missing}}", OBJ},
                    new String[] {"x{{missing}}y{{name}}z", OBJ},
                    new String[] {"{{name}}{{name}}{{name}}", OBJ},
                    new String[] {"{{input}} and {{input}}", "{\"a\":1}"},
                    new String[] {"{{input}}", "plain text"},
                    new String[] {"[{{input}}]", "plain text"},
                    new String[] {"{{name}}", "plain text"},
                    new String[] {"{{input}}", ""},
                    new String[] {"{{name}}", ""},
                    new String[] {"A{{input}}B", "   "},
                    new String[] {"{{input}}", "[1,2,3]"},
                    new String[] {"{{input}}", "42"},
                    new String[] {"{{input}}", "\"just a string\""},
                    new String[] {"{{input}}", "null"},
                    new String[] {"{{input}}", "true"},
                    new String[] {"{{a}}", "{\"a\":1.0}"},
                    new String[] {"{{a}}", "{\"a\":1.50}"},
                    new String[] {"{{a}}", "{\"a\":1E400}"},
                    new String[] {"{{a}}", "{\"a\":-0}"},
                    new String[] {"{{a}}", "{\"a\":12345678901234567890}"},
                    new String[] {"{{a}}", "{\"a\":0.1}"},
                    new String[] {"{{a}}", "{\"a\":\"\"}"},
                    new String[] {"[{{a}}]", "{\"a\":[]}"},
                    new String[] {"[{{a}}]", "{\"a\":{}}"},
                    new String[] {"{{a}}", "{\"a\":\"quote\\\"d\"}"},
                    new String[] {"{{a}}", "{\"a\":[1.0,{\"b\":null}]}"},
                    new String[] {"{{first name}}", "{\"first name\":\"Ada\"}"},
                    new String[] {"{{a-b}}", "{\"a-b\":7}"},
                    new String[] {"{{a.b}}", "{\"a.b\":\"dotted key\"}"},
                    new String[] {"{{input}}", "{\"input\":\"shadow\"}"},
                    new String[] {"{{name}}", "{\"name\":\"first\",\"name\":\"second\"}"},
                    new String[] {"{{name}}", "{\"name\":\"Ada\"} trailing garbage"},
                    new String[] {"{{{name}}}", OBJ},
                    new String[] {"{{name}}}}", OBJ},
                    new String[] {"{{{{name}}}}", OBJ},
                    new String[] {"{{}}", OBJ},
                    new String[] {"{{}}{{name}}", OBJ},
                    new String[] {"open {{ never closed", OBJ},
                    new String[] {"open {{name never closed", OBJ},
                    new String[] {"closed only }} here", OBJ},
                    new String[] {"{ {name} }", OBJ},
                    new String[] {"{name}", OBJ},
                    new String[] {"", OBJ},
                    new String[] {"no placeholders at all", OBJ},
                    new String[] {"{{Name}}", OBJ},
                    new String[] {"{{NAME}}", OBJ},
                    new String[] {"{\"user\":\"{{name}}\",\"n\":{{age}}}", OBJ},
                    new String[] {"https://api.example.com/u/{{name}}?zip={{addr}}", OBJ},
                    new String[] {"{{é}}", "{\"é\":\"accent key\"}"},
                    new String[] {"{{name}}\n{{age}}\r\n{{input}}", "{\"name\":\"n\",\"age\":1}"});

    @Test
    void atLeastFortyPairs() {
        assertTrue(PAIRS.size() >= 40, "table has " + PAIRS.size());
    }

    @Test
    void renderMatchesTheOldAlgorithmOnEveryPair() {
        StringBuilder failures = new StringBuilder();
        for (String[] p : PAIRS) {
            String expected = oldRender(p[0], p[1]);
            String actual;
            try {
                actual = engine.render(p[0], BasicEvalContext.ofInput(p[1]));
            } catch (RuntimeException e) {
                actual = "THREW " + e;
            }
            if (!expected.equals(actual)) {
                failures.append("template=")
                        .append(p[0])
                        .append(" input=")
                        .append(p[1])
                        .append("\n  old=")
                        .append(expected)
                        .append("\n  new=")
                        .append(actual)
                        .append('\n');
            }
        }
        assertEquals("", failures.toString());
    }

    @Test
    void renderValueOnALegacyTemplateAgreesWithRenderWhenTheResultIsText() {
        for (String[] p : PAIRS) {
            Object typed = engine.renderValue(p[0], BasicEvalContext.ofInput(p[1]));
            if (typed instanceof String s) {
                assertEquals(engine.render(p[0], BasicEvalContext.ofInput(p[1])), s, p[0]);
            }
        }
    }

    @Test
    void numbersKeepTheirOriginalJacksonSpelling() {
        assertEquals("1.0", engine.render("{{a}}", BasicEvalContext.ofInput("{\"a\":1.0}")));
        assertEquals("1000.0", engine.render("{{a}}", BasicEvalContext.ofInput("{\"a\":1e3}")));
        assertEquals(
                "{\"x\":1.0}",
                engine.render("{{a}}", BasicEvalContext.ofInput("{\"a\":{\"x\":1.0}}")));
    }

    @Test
    void unknownBareNameStaysLiteral() {
        assertEquals(
                "a {{nope}} b",
                engine.render("a {{nope}} b", BasicEvalContext.ofInput("{\"x\":1}")));
    }

    @Test
    void literalExpressionsNowEvaluateInsteadOfStayingLiteral() {
        // OLD: "{{0}}" matched only a JSON field literally named "0", so against an array input it
        // stayed as text.
        // NEW: "0" is a number literal. Only placeholders that were dead text change; a real field
        // "0" still wins.
        assertEquals("{{0}}", oldRender("{{0}}", "[1,2,3]"));
        assertEquals("0", engine.render("{{0}}", BasicEvalContext.ofInput("[1,2,3]")));
        String withField = "{\"0\":\"zero\"}";
        assertEquals(
                oldRender("{{0}}", withField),
                engine.render("{{0}}", BasicEvalContext.ofInput(withField)));
    }

    @Test
    void whitespaceInsideBracesIsNowAccepted() {
        // OLD behaviour: "{{ input }}" was never matched and stayed literal. Spec 3.1 allows
        // whitespace, so this is a
        // deliberate, documented relaxation: only templates that previously printed the literal
        // braces change.
        assertEquals("{{ input }}", oldRender("{{ input }}", "hi"));
        assertEquals("hi", engine.render("{{ input }}", BasicEvalContext.ofInput("hi")));
        assertEquals(
                "Ada", engine.render("{{ name }}", BasicEvalContext.ofInput("{\"name\":\"Ada\"}")));
        assertEquals("{{ nope }}", engine.render("{{ nope }}", BasicEvalContext.ofInput("{}")));
    }

    // ---- the injection bug: old behaviour is wrong on purpose, new behaviour is asserted

    @Test
    void substitutedValueContainingALaterPlaceholderIsInertNow() {
        // Old code substituted sequentially over a HashMap, so a value holding "{{secret}}" could
        // be expanded by a
        // later iteration (order depends on hash order). The new single-pass renderer never
        // re-scans output.
        String input = "{\"a\":\"{{secret}}\",\"secret\":\"S3CRET\"}";
        assertEquals("{{secret}}", engine.render("{{a}}", BasicEvalContext.ofInput(input)));
        assertEquals(
                "{{secret}}|S3CRET",
                engine.render("{{a}}|{{secret}}", BasicEvalContext.ofInput(input)));
        assertEquals(
                "S3CRET|{{secret}}",
                engine.render("{{secret}}|{{a}}", BasicEvalContext.ofInput(input)));
    }

    @Test
    void oldAlgorithmReallyWasInjectableForSomeOrdering() {
        // Documents WHY the behaviour changed: for at least one of these key names the reference
        // expands the payload.
        boolean any = false;
        for (String key :
                List.of("a", "b", "c", "d", "input", "x1", "x2", "zz", "name", "k", "key", "v")) {
            String input = "{\"" + key + "\":\"{{secret}}\",\"secret\":\"S3CRET\"}";
            String old = oldRender("{{" + key + "}}", input);
            String now = engine.render("{{" + key + "}}", BasicEvalContext.ofInput(input));
            assertEquals("{{secret}}", now);
            any |= !old.equals(now);
        }
        assertTrue(
                any,
                "expected the reference implementation to show the double-expansion for some key");
    }

    @Test
    void rawInputContainingPlaceholdersIsInert() {
        String input = "{\"name\":\"{{input}}\"}";
        assertEquals("{{input}}", engine.render("{{name}}", BasicEvalContext.ofInput(input)));
        assertNotEquals(input, engine.render("{{name}}", BasicEvalContext.ofInput(input)));
    }

    @Test
    void webhookBodyWithNodeExpressionTextIsInert() {
        String input = "{\"msg\":\"{{ $node[\\\"Secret\\\"].output }}\"}";
        var ctx =
                BasicEvalContext.builder()
                        .input(input)
                        .node(new NodeOutput("n1", "Secret", "TOPSECRET", "OK", ""))
                        .build();
        assertEquals("{{ $node[\"Secret\"].output }}", engine.render("{{msg}}", ctx));
    }
}
