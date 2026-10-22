package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link AiExtractorHandler} used directly: repair retry, error codes, missing/invalid bookkeeping.
 */
class AiExtractorHandlerTest {
    private static final ObjectMapper M = com.agenticai.core.json.JsonMappers.plain();

    @SuppressWarnings("unchecked")
    private static AiExtractorHandler handler(WorkflowAiModel model, boolean enabled) {
        ObjectProvider<WorkflowAiModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return new AiExtractorHandler(provider, M, enabled, 12_000);
    }

    private static WorkflowNodeDto node() {
        Map<String, Object> amount =
                new HashMap<>(
                        Map.of(
                                "name",
                                "amount",
                                "type",
                                "number",
                                "description",
                                "d",
                                "required",
                                true));
        Map<String, Object> who =
                new HashMap<>(
                        Map.of(
                                "name",
                                "who",
                                "type",
                                "string",
                                "description",
                                "d",
                                "required",
                                true));
        return new WorkflowNodeDto(
                "n1",
                "parameter_extractor",
                Map.of(),
                Map.of("config", Map.of("parameters", List.of(amount, who))));
    }

    private static WorkflowAiModel scripted(List<WorkflowAiModel.Request> seen, String... replies) {
        LinkedList<String> q = new LinkedList<>(List.of(replies));
        return req -> {
            seen.add(req);
            return q.poll();
        };
    }

    @Test
    void validFirstReplyMakesOneCallAndListsMissingRequired() throws Exception {
        List<WorkflowAiModel.Request> seen = new java.util.ArrayList<>();
        NodeExecutionResult r =
                handler(scripted(seen, "{\"amount\":5,\"who\":null}"), true)
                        .execute(node(), "text");
        JsonNode out = M.readTree(r.output());
        assertEquals(1, seen.size());
        assertEquals(5, out.get("amount").asInt());
        assertEquals(M.readTree("[\"who\"]"), out.get("_missing"));
        assertNull(out.get("_error"));
    }

    @Test
    void illTypedReplyIsRepairedOnceAndTheBetterReplyWins() throws Exception {
        List<WorkflowAiModel.Request> seen = new java.util.ArrayList<>();
        NodeExecutionResult r =
                handler(
                                scripted(
                                        seen,
                                        "{\"amount\":\"lots\",\"who\":\"Asha\"}",
                                        "{\"amount\":9,\"who\":\"Asha\"}"),
                                true)
                        .execute(node(), "text");
        JsonNode out = M.readTree(r.output());
        assertEquals(2, seen.size());
        assertTrue(
                seen.get(1)
                        .user()
                        .contains(
                                "Your previous reply could not be used: amount: expected a number"));
        assertEquals(9, out.get("amount").asInt());
        assertNull(out.get("_invalid"));
    }

    @Test
    void stillIllTypedAfterRepairIsReportedAsInvalidAndNoThirdCall() throws Exception {
        List<WorkflowAiModel.Request> seen = new java.util.ArrayList<>();
        NodeExecutionResult r =
                handler(
                                scripted(
                                        seen,
                                        "{\"amount\":\"x\",\"who\":\"A\"}",
                                        "{\"amount\":\"y\",\"who\":\"A\"}",
                                        "{\"amount\":1}"),
                                true)
                        .execute(node(), "text");
        assertEquals(2, seen.size());
        assertEquals(M.readTree("[\"amount\"]"), M.readTree(r.output()).get("_invalid"));
    }

    @Test
    void errorCodesForUnparseableProviderFailureAndEmptyInput() throws Exception {
        assertEquals(
                "unusable_model_reply",
                M.readTree(
                                handler(scripted(new java.util.ArrayList<>(), "nope", "nope"), true)
                                        .execute(node(), "text")
                                        .output())
                        .get("_error")
                        .asString());
        assertEquals(
                "model_call_failed",
                M.readTree(
                                handler(
                                                req -> {
                                                    throw new IllegalStateException("x");
                                                },
                                                true)
                                        .execute(node(), "text")
                                        .output())
                        .get("_error")
                        .asString());
        assertEquals(
                "empty_input",
                M.readTree(
                                handler(scripted(new java.util.ArrayList<>(), "{}"), true)
                                        .execute(node(), "  ")
                                        .output())
                        .get("_error")
                        .asString());
    }

    @Test
    void disabledOrNoModelIsSkippedWithoutACall() {
        assertEquals(
                "SKIPPED",
                handler(
                                req -> {
                                    throw new AssertionError("no call");
                                },
                                false)
                        .execute(node(), "t")
                        .status());
        assertEquals("SKIPPED", handler(null, true).execute(node(), "t").status());
    }
}
