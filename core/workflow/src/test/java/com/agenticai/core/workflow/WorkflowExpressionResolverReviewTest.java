package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.workflow.WorkflowExpressionResolver.NodeEvalInfo;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Resolver behaviour pinned by the review of the expression feature: {{input}} in the agent and CRM fields,
 * the "too large" failure of a cut-off upstream output, typed values in JSON-text fields, CRM blanking and
 * the per-recipient skip of bulk_email.
 */
class WorkflowExpressionResolverReviewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RUN = UUID.randomUUID().toString();
    private static final NodeEvalInfo INFO = new NodeEvalInfo(UUID.randomUUID().toString(), "wf", RUN, "ann");

    private final WorkflowExpressionResolver resolver = WorkflowExpressionResolver.standalone(MAPPER);

    private static WorkflowNodeDto node(String type, Map<String, Object> config) {
        return new WorkflowNodeDto("n1", type, Map.of(), Map.of("label", "N1", "config", config));
    }

    // ---- {{input}} is the raw input in the fields that always meant that

    private static final String INPUT_WITH_INPUT_KEY = "{\"input\":\"x\",\"e\":\"y\"}";

    @Test
    @SuppressWarnings("unchecked")
    void agentQueryBareInputIsTheRawInputEvenWhenTheJsonHasAnInputKey() {
        WorkflowNodeDto out = resolver.resolve(
            node("agent", Map.of("inputMapping", Map.of("query", "{{input}}"))), INPUT_WITH_INPUT_KEY, INFO, null);
        assertEquals(INPUT_WITH_INPUT_KEY, ((Map<String, Object>) out.config().get("inputMapping")).get("query"));
    }

    @Test
    void crmFieldBareInputIsTheRawInputEvenWhenTheJsonHasAnInputKey() {
        WorkflowNodeDto out = resolver.resolve(node("crm_lead", Map.of("notes", "{{input}}")), INPUT_WITH_INPUT_KEY, INFO, null);
        assertEquals(INPUT_WITH_INPUT_KEY, out.config().get("notes"));
    }

    @Test
    void httpFieldsKeepTheirOldRuleWhereAnInputKeyOverridesTheRawInput() {
        WorkflowNodeDto out = resolver.resolve(
            node("http", Map.of("url", "https://x.test/{{input}}")), INPUT_WITH_INPUT_KEY, INFO, null);
        assertEquals("https://x.test/x", out.config().get("url"));
    }

    @Test
    void theKillSwitchPathAgreesWithTheEnabledPathForAgentAndCrm() {
        WorkflowExpressionResolver off = new WorkflowExpressionResolver((RunDataStore) null, null, MAPPER, false, false);
        WorkflowNodeDto crm = node("crm_lead", Map.of("notes", "{{input}}"));
        assertEquals(off.resolve(crm, INPUT_WITH_INPUT_KEY, INFO, null).config().get("notes"),
            resolver.resolve(crm, INPUT_WITH_INPUT_KEY, INFO, null).config().get("notes"));
    }

    // ---- a cut-off upstream output fails loudly instead of being read as a partial value

    @Test
    void readingATruncatedUpstreamOutputFailsWithTheTooLargeMessage() {
        RunDataStore store = mock(RunDataStore.class);
        when(store.latestByLabel(any(), eq("Big")))
            .thenReturn(new RunDataStore.Stored("n9", "Big", 0, "OK", "{\"a\":\"cut", true));
        WorkflowExpressionResolver r = new WorkflowExpressionResolver(store, null, MAPPER, true, false);

        WorkflowNodeDto n = node("http", Map.of("url", "https://x.test/{{ $node[\"Big\"].a }}"));
        WorkflowExpressionException e = assertThrows(WorkflowExpressionException.class, () -> r.resolve(n, "", INFO, null));
        assertTrue(e.getMessage().contains("too large"), e.getMessage());
    }

    @Test
    void readingATruncatedTriggerPayloadFailsWithTheTooLargeMessage() {
        RunDataStore store = mock(RunDataStore.class);
        when(store.latestById(any(), eq(RunDataStore.TRIGGER_NODE_ID)))
            .thenReturn(new RunDataStore.Stored(RunDataStore.TRIGGER_NODE_ID, "$trigger", 0, "OK", "{\"a\":\"cut", true));
        WorkflowExpressionResolver r = new WorkflowExpressionResolver(store, null, MAPPER, true, false);

        WorkflowNodeDto n = node("http", Map.of("url", "https://x.test/{{ $trigger.a }}"));
        WorkflowExpressionException e = assertThrows(WorkflowExpressionException.class, () -> r.resolve(n, "", INFO, null));
        assertTrue(e.getMessage().contains("too large"), e.getMessage());
    }

    // ---- JSON-text fields keep value types and stay valid JSON

    private JsonNode argsJson(String template, String input) throws Exception {
        WorkflowNodeDto out = resolver.resolve(node("tool", Map.of("argsJson", template)), input, INFO, null);
        return MAPPER.readTree(String.valueOf(out.config().get("argsJson")));
    }

    @Test
    void aSingleExpressionInJsonArgsKeepsItsNumberType() throws Exception {
        JsonNode args = argsJson("{\"qty\":\"{{ $json.n }}\",\"label\":\"n={{ $json.n }}\"}", "{\"n\":5}");
        assertTrue(args.get("qty").isNumber(), args.toString());
        assertEquals(5, args.get("qty").asInt());
        assertEquals("n=5", args.get("label").asText());
    }

    @Test
    void aSubstitutedValueWithQuotesAndNewlinesCannotBreakTheJsonArgs() throws Exception {
        JsonNode args = argsJson("{\"m\":\"{{ $json.s }}\"}", "{\"s\":\"he said \\\"hi\\\"\\nbye\"}");
        assertEquals("he said \"hi\"\nbye", args.get("m").asText());
    }

    // ---- CRM fields blank a key the input does not carry

    @Test
    void aCrmFieldForAnAbsentKeyRendersEmptyNotTheLiteralPlaceholder() {
        WorkflowNodeDto out = resolver.resolve(
            node("crm_lead", Map.of("email", "{{email}}", "name", "{{name}}")), "{\"name\":\"Ann\"}", INFO, null);
        assertEquals("", out.config().get("email"));
        assertEquals("Ann", out.config().get("name"));
    }

    // ---- bulk_email subject and body are left for the per-recipient pass

    @Test
    void bulkEmailSubjectAndBodyAreNotResolvedByTheNodeLevelPass() {
        WorkflowNodeDto out = resolver.resolve(
            node("bulk_email", Map.of("subject", "Hi {{name}}", "body", "Dear {{ $json.name }}", "replyTo", "{{reply}}")),
            "{\"name\":\"Zed\",\"reply\":\"r@x.test\"}", INFO, null);
        assertEquals("Hi {{name}}", out.config().get("subject"));
        assertEquals("Dear {{ $json.name }}", out.config().get("body"));
        assertEquals("r@x.test", out.config().get("replyTo"));
    }
}
