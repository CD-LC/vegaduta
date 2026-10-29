package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Dify variable references ({@code {{#nodeId.field#}}} in text, {@code [nodeId, var]} selectors in
 * structured fields) rewritten into VegaDuta expressions, with anything without an equivalent left
 * as written and reported to the {@link DifyReferences.Sink}.
 */
class DifyReferencesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A sink that records what the mapper would have put in the report. */
    private static final class RecordingSink implements DifyReferences.Sink {
        int refsSeen;
        final List<String> unconverted = new ArrayList<>();
        boolean inHttp;

        @Override
        public void refsSeen() {
            refsSeen++;
        }

        @Override
        public void unconverted(String note) {
            unconverted.add(note);
        }

        @Override
        public boolean inHttp() {
            return inHttp;
        }
    }

    private static DifyReferences refs() {
        return new DifyReferences(
                MAPPER,
                Map.of("llm1", "LLM Step", "start1", "Start"),
                Set.of("iter1"),
                Map.of("API_KEY", true, "REGION", false));
    }

    private static JsonNode json(String singleQuoted) throws Exception {
        return MAPPER.readTree(singleQuoted.replace('\'', '"'));
    }

    @Test
    void expressionForMapsTheDocumentedRoots() {
        DifyReferences r = refs();
        assertThat(r.expressionFor(List.of("sys", "query"), false)).isEqualTo("$trigger");
        assertThat(r.expressionFor(List.of("sys", "user_id"), false)).isNull();
        assertThat(r.expressionFor(List.of("env", "REGION"), false)).isEqualTo("$vars.REGION");
        assertThat(r.expressionFor(List.of("env", "API_KEY"), false)).isEqualTo("$vars.API_KEY");
        // A secret env var is a vault reference only inside an HTTP node.
        assertThat(r.expressionFor(List.of("env", "API_KEY"), true)).isEqualTo("secret:API_KEY");
        assertThat(r.expressionFor(List.of("conversation", "turns"), false)).isNull();
        assertThat(r.expressionFor(List.of("iter1", "item"), false)).isEqualTo("$item");
        assertThat(r.expressionFor(List.of("iter1", "index"), false)).isEqualTo("$index");
        assertThat(r.expressionFor(List.of("iter1", "other"), false)).isNull();
    }

    @Test
    void expressionForAddressesANodeByItsTitle() {
        DifyReferences r = refs();
        assertThat(r.expressionFor(List.of("llm1", "text"), false))
                .isEqualTo("$node[\"LLM Step\"].text");
        assertThat(r.expressionFor(List.of("start1", "deep", "field"), false))
                .isEqualTo("$node[\"Start\"].deep.field");
        assertThat(r.expressionFor(List.of("missing", "x"), false)).isNull();
    }

    @Test
    void rewriteWrapsConvertedReferencesAndLeavesTheRest() {
        RecordingSink sink = new RecordingSink();
        String out =
                refs().rewrite(sink, "Say {{#llm1.text#}} about {{#sys.query#}} / {{#mystery.x#}}");
        assertThat(out)
                .isEqualTo(
                        "Say {{ $node[\"LLM Step\"].text }} about {{ $trigger }} / {{#mystery.x#}}");
        assertThat(sink.refsSeen).isEqualTo(2);
        assertThat(sink.unconverted).hasSize(1);
        assertThat(sink.unconverted.get(0)).contains("mystery.x");
    }

    @Test
    void rewriteTurnsASecretEnvVarIntoAVaultReferenceInsideHttp() {
        RecordingSink sink = new RecordingSink();
        sink.inHttp = true;
        String out = refs().rewrite(sink, "key={{#env.API_KEY#}}");
        assertThat(out).isEqualTo("key={{secret.API_KEY}}");
        assertThat(sink.refsSeen).isEqualTo(1);
    }

    @Test
    void selectorExpressionReadsAnArraySelector() throws Exception {
        RecordingSink sink = new RecordingSink();
        JsonNode selector = json("['llm1', 'text']");
        assertThat(refs().selectorExpression(sink, selector)).isEqualTo("$node[\"LLM Step\"].text");
        assertThat(sink.refsSeen).isEqualTo(1);

        assertThat(refs().selectorExpression(new RecordingSink(), json("['only']"))).isNull();
        assertThat(refs().selectorExpression(new RecordingSink(), json("{'a': 1}"))).isNull();
        assertThat(refs().selectorExpression(new RecordingSink(), null)).isNull();
        // A non-scalar element cannot be expressed.
        assertThat(refs().selectorExpression(new RecordingSink(), json("['a', {'b': 1}]")))
                .isNull();
    }

    @Test
    void conditionProducesABareExpression() throws Exception {
        RecordingSink sink = new RecordingSink();
        JsonNode is =
                json(
                        "{'comparison_operator': 'is', 'variable_selector': ['llm1', 'text'],"
                                + " 'value': 'ok', 'varType': 'string'}");
        assertThat(refs().condition(sink, is)).isEqualTo("$node[\"LLM Step\"].text == 'ok'");

        JsonNode empty =
                json("{'comparison_operator': 'empty', 'variable_selector': ['llm1', 'text']}");
        assertThat(refs().condition(sink, empty)).isEqualTo("isEmpty($node[\"LLM Step\"].text)");

        JsonNode gt =
                json(
                        "{'comparison_operator': '>', 'variable_selector': ['start1', 'n'],"
                                + " 'value': 5, 'varType': 'number'}");
        assertThat(refs().condition(sink, gt)).isEqualTo("$node[\"Start\"].n > 5");
    }

    @Test
    void conditionResolvesAReferenceOnTheRightHandSide() throws Exception {
        RecordingSink sink = new RecordingSink();
        JsonNode cond =
                json(
                        "{'comparison_operator': 'is', 'variable_selector': ['llm1', 'text'],"
                                + " 'value': '{{#start1.answer#}}'}");
        assertThat(refs().condition(sink, cond))
                .isEqualTo("$node[\"LLM Step\"].text == $node[\"Start\"].answer");
    }

    @Test
    void conditionFailsForAnUnknownOperatorOrOperand() throws Exception {
        assertThat(
                        refs().condition(
                                        new RecordingSink(),
                                        json(
                                                "{'comparison_operator': 'about',"
                                                        + " 'variable_selector': ['llm1', 't'],"
                                                        + " 'value': 'x'}")))
                .isNull();
        // No operator at all.
        assertThat(refs().condition(new RecordingSink(), json("{'value': 'x'}"))).isNull();
        // Left-hand side that cannot be expressed.
        assertThat(
                        refs().condition(
                                        new RecordingSink(),
                                        json(
                                                "{'comparison_operator': 'is',"
                                                        + " 'variable_selector': ['gone', 'x'],"
                                                        + " 'value': 'x'}")))
                .isNull();
    }
}
