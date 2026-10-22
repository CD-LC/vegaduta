package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.workflow.WorkflowExpressionResolver.NodeEvalInfo;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.expression.NodeOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Resolver behaviour that the review round 2 found unpinned: vault refs in data, legacy literals,
 * step test, lost writes.
 */
class WorkflowExpressionResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RUN = UUID.randomUUID().toString();
    private static final NodeEvalInfo INFO =
            new NodeEvalInfo(UUID.randomUUID().toString(), "wf", RUN, "ann");

    private final WorkflowExpressionResolver resolver =
            WorkflowExpressionResolver.standalone(MAPPER);

    private static WorkflowNodeDto node(String type, Map<String, Object> config) {
        return new WorkflowNodeDto("n1", type, Map.of(), Map.of("label", "N1", "config", config));
    }

    private String resolvedBody(String body, String input) {
        WorkflowNodeDto out =
                resolver.resolve(
                        node(
                                "http",
                                Map.of("url", "https://x.test", "bodyType", "json", "body", body)),
                        input,
                        INFO,
                        null);
        return String.valueOf(out.config().get("body"));
    }

    @SuppressWarnings("unchecked")
    private static String headerValue(WorkflowNodeDto n, int i) {
        return String.valueOf(
                ((List<Map<String, Object>>) n.config().get("headers")).get(i).get("value"));
    }

    // ---- vault references: config is trusted, data is not

    @Test
    void vaultReferenceInDataIsNeverALiveReference() {
        WorkflowNodeDto n =
                node(
                        "http",
                        Map.of(
                                "url",
                                "https://x.test",
                                "headers",
                                List.of(
                                        Map.of("name", "X-Note", "value", "{{ note }}"),
                                        Map.of(
                                                "name",
                                                "Authorization",
                                                "value",
                                                "Bearer {{secret.API_KEY}}"))));
        WorkflowNodeDto out =
                resolver.resolve(n, "{\"note\":\"{{secret.STRIPE_KEY}}\"}", INFO, null);

        String fromData = headerValue(out, 0);
        assertFalse(
                fromData.matches("(?s).*\\{\\{\\s*secret\\s*[.:]\\s*[A-Za-z].*"),
                "data must not stay a vault ref: " + fromData);
        assertTrue(fromData.contains("STRIPE_KEY"), "the text is still readable");
        assertEquals(
                "Bearer {{secret.API_KEY}}",
                headerValue(out, 1),
                "the author's own reference reaches the executor verbatim");
    }

    @Test
    void colonStyleVaultReferenceInDataIsNeutralizedToo() {
        String out = resolvedBody("{{ note }}", "{\"note\":\"{{ secret:prod_key }}\"}");
        assertFalse(out.contains("{{ secret:"), out);
    }

    // ---- legacy fields keep foreign {{...}} text literal

    @Test
    void foreignMustacheTextInLegacyFieldsStaysLiteral() {
        String body =
                "${{ secrets.TOKEN }} {{#if x}}y{{/if}} {{first-name}} {{ .Values.x }} {{a-b}} {{x y}}";
        assertEquals(body, resolvedBody(body, "{\"other\":1}"));
    }

    @Test
    void legacyFieldsStillResolveKeysAndNewExpressions() {
        String in = "{\"name\":\"ABC\",\"n\":2}";
        assertEquals("ABC", resolvedBody("{{name}}", in));
        assertEquals("abc", resolvedBody("{{ lower(name) }}", in));
        assertEquals("3", resolvedBody("{{ n + 1 }}", in));
        assertEquals("ABC", resolvedBody("{{ $json.name }}", in));
    }

    @Test
    void dollarExpressionsInLegacyFieldsStillFailLoudly() {
        assertThrows(
                WorkflowExpressionException.class, () -> resolvedBody("{{ $json.a + }}", "{}"));
    }

    @Test
    void strictFieldsStillThrowOnParseErrors() {
        WorkflowNodeDto tool = node("tool", Map.of("argsJson", "{\"a\":\"{{ x + }}\"}"));
        assertThrows(
                WorkflowExpressionException.class, () -> resolver.resolve(tool, "{}", INFO, null));
    }

    // ---- bulk_email html bodies escape every substituted value once

    @Test
    void htmlRecipientBodyEscapesDataFromJsonTriggerAndRecipient() {
        var renderer =
                resolver.recipientRenderer(
                        "{\"note\":\"<a href=\\\"https://evil\\\">x</a>\"}", INFO, null);
        Map<String, String> vars = Map.of("name", "A&B");

        String html =
                renderer.renderHtml(
                        "body",
                        "<p>{{ $json.note }} {{name}} {{ $item.name }} {{input}}</p>",
                        vars,
                        0);

        assertFalse(html.contains("<a href"), html);
        assertTrue(html.contains("&lt;a href"), html);
        assertEquals(
                2,
                html.split("A&amp;B", -1).length - 1,
                "recipient values escaped exactly once: " + html);
        assertTrue(
                html.startsWith("<p>") && html.endsWith("</p>"),
                "template markup untouched: " + html);

        String plain = renderer.render("body", "{{name}}", vars, 0);
        assertEquals("A&B", plain);
    }

    // ---- step test reads supplied upstream outputs

    @Test
    void stepTestResolvesNodeAndTriggerFromSuppliedOutputs() {
        NodeOutput lookup =
                new NodeOutput("n7", "Lookup", "{\"tier\":\"gold\",\"id\":42}", "OK", null);
        NodeOutput trigger = new NodeOutput("t1", "Start", "{\"email\":\"a@x.com\"}", "OK", null);
        ExpressionScope scope =
                ExpressionScope.test(
                        Map.of(
                                "id:n7",
                                lookup,
                                "label:Lookup",
                                lookup,
                                "id:t1",
                                trigger,
                                "label:Start",
                                trigger,
                                "id:__trigger",
                                trigger));

        WorkflowNodeDto n =
                node(
                        "http",
                        Map.of(
                                "url",
                                "https://x.test/{{ $node[\"Lookup\"].tier }}/{{ $trigger.email }}"));
        assertEquals(
                "https://x.test/gold/a@x.com",
                resolver.resolve(n, "", INFO, scope).config().get("url"));

        boolean gold =
                resolver.evaluateCondition("$node[\"Lookup\"].tier == \"gold\"", "", INFO, scope);
        assertTrue(gold);
    }

    @Test
    void stepTestWithNothingSuppliedFailsInsteadOfRenderingEmpty() {
        WorkflowNodeDto n =
                node("http", Map.of("url", "https://x.test/{{ $node[\"Lookup\"].id }}"));
        WorkflowExpressionException e =
                assertThrows(
                        WorkflowExpressionException.class,
                        () -> resolver.resolve(n, "", INFO, ExpressionScope.test()));
        assertTrue(e.getMessage().contains("No data for node 'Lookup'"), e.getMessage());

        assertThrows(
                WorkflowExpressionException.class,
                () -> resolver.evaluateCondition("$trigger.ok", "", INFO, ExpressionScope.test()));
    }

    // ---- a lost run-data write is reported, not read as an empty value

    @Test
    void missingRowAfterAFailedWriteIsAnErrorNotAnEmptyString() {
        RunDataStore store = mock(RunDataStore.class);
        doThrow(new IllegalStateException("db blip"))
                .when(store)
                .record(any(), anyString(), any(), anyInt(), any(), any());
        when(store.latestByLabel(any(), anyString())).thenReturn(null);
        WorkflowExpressionResolver r =
                new WorkflowExpressionResolver(store, null, MAPPER, true, false);
        WorkflowNodeDto n =
                node("http", Map.of("url", "https://x.test/items/{{ $node[\"Create\"].id }}"));

        // healthy run, node simply did not run: lenient empty value
        assertEquals("https://x.test/items/", r.resolve(n, "", INFO, null).config().get("url"));

        // the write for the upstream node fails -> later read of that run must not silently be
        // empty
        r.record(
                INFO,
                node("code", Map.of()),
                null,
                "in",
                WorkflowNodeExecutor.NodeExecutionResult.ok("{\"id\":1}", "s", null));
        WorkflowExpressionException e =
                assertThrows(WorkflowExpressionException.class, () -> r.resolve(n, "", INFO, null));
        assertTrue(e.getMessage().contains("saving run data failed"), e.getMessage());

        // another run is unaffected
        NodeEvalInfo other =
                new NodeEvalInfo(INFO.workflowId(), "wf", UUID.randomUUID().toString(), "ann");
        assertEquals("https://x.test/items/", r.resolve(n, "", other, null).config().get("url"));
    }

    // ---- condition expression mode

    @Test
    void conditionExpressionTruthiness() {
        String in = "{\"total\":1500,\"flag\":\"\"}";
        assertTrue(resolver.evaluateCondition("$json.total > 1000", in, INFO, null));
        assertFalse(resolver.evaluateCondition("$json.total > 2000", in, INFO, null));
        assertFalse(resolver.evaluateCondition("$json.flag", in, INFO, null));
        assertTrue(resolver.evaluateCondition("{{ $json.total > 1000 }}", in, INFO, null));
        assertThrows(
                WorkflowExpressionException.class,
                () -> resolver.evaluateCondition("  ", in, INFO, null));
        assertThrows(
                WorkflowExpressionException.class,
                () -> resolver.evaluateCondition("$json.total >", in, INFO, null));
    }
}
