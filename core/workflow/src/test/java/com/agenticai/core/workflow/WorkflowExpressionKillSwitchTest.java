package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.WorkflowExpressionResolver.NodeEvalInfo;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code app.workflow.expressions.enabled=false} must put every field back on the algorithm it used
 * before expressions existed: one test per {@link ExpressionFields.Legacy} mode, plus the two modes
 * with no legacy equivalent (condition expression, which refuses; per-item bulk email, which reads
 * the recipient map).
 */
class WorkflowExpressionKillSwitchTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final NodeEvalInfo INFO = new NodeEvalInfo("wf", "wf", null, "ann");
    private static final String INPUT = "{\"a\":1,\"email\":\"e@x.test\"}";

    private final WorkflowExpressionResolver off =
            new WorkflowExpressionResolver(
                    (RunDataStore) null, (WorkflowRunRepository) null, MAPPER, false, false);

    private static WorkflowNodeDto node(String type, Map<String, Object> config) {
        return new WorkflowNodeDto("n1", type, Map.of(), Map.of("label", "N1", "config", config));
    }

    @Test
    void legacyNoneFieldsAreLeftUntouched() {
        WorkflowNodeDto n = node("output", Map.of("emailTo", "{{ $json.a }} {{a}} {{input}}"));
        assertEquals(
                "{{ $json.a }} {{a}} {{input}}",
                off.resolve(n, INPUT, INFO, null).config().get("emailTo"),
                "output fields were never templated");
        WorkflowNodeDto tool = node("tool", Map.of("argsJson", "{\"q\":\"{{input}}\"}"));
        assertEquals(
                "{\"q\":\"{{input}}\"}",
                off.resolve(tool, INPUT, INFO, null).config().get("argsJson"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyInputOnlyReplacesJustTheInputPlaceholder() {
        WorkflowNodeDto n =
                node(
                        "agent",
                        Map.of(
                                "inputMapping",
                                Map.of("query", "q={{input}} a={{a}} e={{ $json.a }}")));
        Map<String, Object> mapping =
                (Map<String, Object>)
                        off.resolve(n, INPUT, INFO, null).config().get("inputMapping");
        assertEquals("q=" + INPUT + " a={{a}} e={{ $json.a }}", mapping.get("query"));
    }

    @Test
    void legacyMapReplacesInputAndTopLevelFieldsButNeverEvaluates() {
        WorkflowNodeDto n =
                node(
                        "code",
                        Map.of(
                                "operation",
                                "template",
                                "template",
                                "{{a}}|{{email}}|{{nope}}|{{ $json.a + 1 }}"));
        assertEquals(
                "1|e@x.test|{{nope}}|{{ $json.a + 1 }}",
                off.resolve(n, INPUT, INFO, null).config().get("template"));
    }

    @Test
    void legacyMapHttpAlsoOffersInputJson() throws Exception {
        WorkflowNodeDto n =
                node(
                        "http",
                        Map.of(
                                "url",
                                "https://x.test",
                                "bodyType",
                                "json",
                                "body",
                                "{\"v\":{{input_json}},\"a\":{{a}}}"));
        assertEquals(
                "{\"v\":" + MAPPER.writeValueAsString(INPUT) + ",\"a\":1}",
                off.resolve(n, INPUT, INFO, null).config().get("body"));
    }

    @Test
    void legacyCrmRendersUnknownKeysAsEmptyText() {
        WorkflowNodeDto n =
                node(
                        "crm_lead",
                        Map.of(
                                "action",
                                "upsert",
                                "email",
                                "{{email}}|{{missing}}|{{ $json.a }}",
                                "name",
                                "{{input}}"));
        WorkflowNodeDto out = off.resolve(n, INPUT, INFO, null);
        assertEquals("e@x.test||{{ $json.a }}", out.config().get("email"));
        assertEquals(INPUT, out.config().get("name"));
    }

    @Test
    void conditionExpressionModeRefusesInsteadOfGuessing() {
        WorkflowExpressionException e =
                assertThrows(
                        WorkflowExpressionException.class,
                        () -> off.evaluateCondition("$json.a > 0", INPUT, INFO, null));
        assertTrue(e.getMessage().contains("disabled"), e.getMessage());
    }

    @Test
    void bulkEmailPerRecipientRenderingFallsBackToTheRecipientMap() {
        WorkflowExpressionResolver.RecipientRenderer r = off.recipientRenderer(INPUT, INFO, null);
        assertEquals(
                "Hi Ann {{ $json.a }} {{gone}}",
                r.render(
                        "subject", "Hi {{name}} {{ $json.a }} {{gone}}", Map.of("name", "Ann"), 0));
        assertEquals("&lt;b&gt;", r.renderHtml("body", "{{v}}", Map.of("v", "<b>"), 0));
    }

    @Test
    void sameNodeUnchangedObjectWhenNothingToResolve() {
        WorkflowNodeDto n = node("output", Map.of("emailTo", "a@b.test"));
        assertSame(n, off.resolve(n, INPUT, INFO, null));
    }

    @Test
    void theSameFieldEvaluatesWhenEnabled() {
        WorkflowExpressionResolver on =
                new WorkflowExpressionResolver(
                        (RunDataStore) null, (WorkflowRunRepository) null, MAPPER, true, false);
        WorkflowNodeDto n =
                node("code", Map.of("operation", "template", "template", "{{ $json.a + 1 }}"));
        assertEquals("2", on.resolve(n, INPUT, INFO, null).config().get("template"));
    }
}
