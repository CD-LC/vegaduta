package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.workflow.portability.N8nImportModel.NodeCtx;
import com.agenticai.core.workflow.portability.N8nImportModel.Src;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * An n8n IF node's comparisons, rewritten as one VegaDuta condition expression (old and new
 * parameter shapes), or refused with a reason when an operator or operand has no equivalent.
 */
class N8nConditionMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String singleQuoted) throws Exception {
        return MAPPER.readTree(singleQuoted.replace('\'', '"'));
    }

    private static NodeCtx ctx(JsonNode params) {
        return new NodeCtx(new Src(0, "id", "IF", "n8n-nodes-base.if", 1, null, params), params);
    }

    @Test
    void newShapeCombinesComparisonsWithTheCombinator() throws Exception {
        JsonNode params =
                json(
                        "{'conditions': {'combinator': 'or', 'conditions': ["
                                + " {'operator': {'type': 'string', 'operation': 'equals'},"
                                + "  'leftValue': 'a', 'rightValue': 'b'},"
                                + " {'operator': {'type': 'string', 'operation': 'contains'},"
                                + "  'leftValue': 'x', 'rightValue': 'y'}]}}");
        N8nConditionMapping.Translation t = N8nConditionMapping.translate(ctx(params));
        assertThat(t.failure()).isNull();
        assertThat(t.expression()).isEqualTo("('a' == 'b') || (contains('x', 'y'))");
    }

    @Test
    void oldShapeJoinsAllThreeOperandLists() throws Exception {
        JsonNode params =
                json(
                        "{'combineOperation': 'any', 'conditions': {"
                                + " 'string': [{'operation': 'startsWith', 'value1': 's', 'value2': 'p'}],"
                                + " 'number': [{'operation': 'larger', 'value1': 3, 'value2': 2}],"
                                + " 'boolean': [{'operation': 'notEquals', 'value1': 'yes'}]}}");
        N8nConditionMapping.Translation t = N8nConditionMapping.translate(ctx(params));
        assertThat(t.failure()).isNull();
        assertThat(t.expression())
                .isEqualTo("(startsWith('s', 'p')) || (3 > 2) || (false != null)");
    }

    @Test
    void unaryOperationsHaveNoRightHandSide() throws Exception {
        JsonNode params =
                json(
                        "{'conditions': {'conditions': ["
                                + " {'operator': {'type': 'string', 'operation': 'notEmpty'},"
                                + "  'leftValue': 'v'},"
                                + " {'operator': {'type': 'string', 'operation': 'exists'},"
                                + "  'leftValue': 'w'}]}}");
        N8nConditionMapping.Translation t = N8nConditionMapping.translate(ctx(params));
        assertThat(t.expression()).isEqualTo("(!isEmpty('v')) && ('w' != null)");
    }

    @Test
    void anExpressionOperandIsRewrittenAndTheNodeIsFlagged() throws Exception {
        JsonNode params =
                json(
                        "{'conditions': {'conditions': ["
                                + " {'operator': {'type': 'string', 'operation': 'equals'},"
                                + "  'leftValue': '={{$json.status}}', 'rightValue': 'ok'}]}}");
        NodeCtx ctx = ctx(params);
        N8nConditionMapping.Translation t = N8nConditionMapping.translate(ctx);
        assertThat(t.expression()).isEqualTo("(($json.status) == 'ok')");
        assertThat(ctx.expression).isTrue();
    }

    @Test
    void anExpressionOperandWithEmbeddedTemplatesIsRefused() throws Exception {
        JsonNode params =
                json(
                        "{'conditions': {'conditions': ["
                                + " {'operator': {'type': 'string', 'operation': 'equals'},"
                                + "  'leftValue': '=a {{$json.x}} b', 'rightValue': 'ok'}]}}");
        N8nConditionMapping.Translation t = N8nConditionMapping.translate(ctx(params));
        assertThat(t.expression()).isNull();
        assertThat(t.failure()).contains("cannot express");
    }

    @Test
    void anUnknownOperationFailsTheWholeCondition() throws Exception {
        JsonNode params =
                json(
                        "{'conditions': {'conditions': ["
                                + " {'operator': {'type': 'string', 'operation': 'regex'},"
                                + "  'leftValue': 'a', 'rightValue': 'b'}]}}");
        N8nConditionMapping.Translation t = N8nConditionMapping.translate(ctx(params));
        assertThat(t.expression()).isNull();
        assertThat(t.failure()).contains("regex");
    }

    @Test
    void dateArrayAndObjectComparisonsAreReportedUnsupported() throws Exception {
        JsonNode params =
                json(
                        "{'conditions': {"
                                + " 'string': [{'operation': 'equals', 'value1': 'a', 'value2': 'b'}],"
                                + " 'dateTime': [{'operation': 'after', 'value1': 'd'}]}}");
        N8nConditionMapping.Translation t = N8nConditionMapping.translate(ctx(params));
        assertThat(t.expression()).isNull();
        assertThat(t.failure()).isEqualTo("date/array/object comparisons");
    }

    @Test
    void aNodeWithoutConditionsFailsCleanly() throws Exception {
        N8nConditionMapping.Translation t = N8nConditionMapping.translate(ctx(json("{'x': 1}")));
        assertThat(t.expression()).isNull();
        assertThat(t.failure()).isEqualTo("no conditions");
    }

    @Test
    void numberAndBooleanTextOperandsAreTyped() throws Exception {
        JsonNode params =
                json(
                        "{'conditions': {"
                                + " 'number': [{'operation': 'equals', 'value1': ' 42 ', 'value2': 7}],"
                                + " 'boolean': [{'operation': 'true', 'value1': 'on'}]}}");
        N8nConditionMapping.Translation t = N8nConditionMapping.translate(ctx(params));
        // Only the literal text "true" is true; anything else becomes false.
        assertThat(t.expression()).isEqualTo("(42 == 7) && (false == true)");
    }
}
