package com.agenticai.core.mcpserver.expose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpWorkflowRunResultTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static NodeTraceEntry step(String label, String output, String cost) {
        NodeTraceEntry entry =
                new NodeTraceEntry(
                                "id-" + label,
                                "agent",
                                label,
                                "COMPLETED",
                                "summary " + label,
                                null,
                                null)
                        .withPayloads("{\"secret\":\"input\"}", false, output, false);
        return cost == null ? entry : entry.withUsage(1L, 1L, new BigDecimal(cost));
    }

    private static WorkflowRunResponse run(WorkflowRunStatus status, List<NodeTraceEntry> trace) {
        return new WorkflowRunResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Flow",
                status,
                "x",
                null,
                trace,
                Instant.now(),
                null,
                null,
                1);
    }

    @Test
    void onlyAwaitingStatesNeedAHuman() {
        assertTrue(McpWorkflowRunResult.needsHuman(WorkflowRunStatus.AWAITING_APPROVAL));
        assertTrue(McpWorkflowRunResult.needsHuman(WorkflowRunStatus.AWAITING_INPUT));
        assertTrue(McpWorkflowRunResult.needsHuman(WorkflowRunStatus.AWAITING_VOICE_INPUT));
        assertFalse(McpWorkflowRunResult.needsHuman(WorkflowRunStatus.RUNNING));
        assertFalse(McpWorkflowRunResult.needsHuman(WorkflowRunStatus.PAUSED));
        assertFalse(McpWorkflowRunResult.needsHuman(WorkflowRunStatus.COMPLETED));
        assertFalse(McpWorkflowRunResult.needsHuman(null));
    }

    @Test
    void costIsTheSumOfRecordedStepCostsOrAbsent() {
        WorkflowRunResponse run =
                run(
                        WorkflowRunStatus.COMPLETED,
                        List.of(
                                step("a", "\"1\"", "0.01"),
                                step("b", "\"2\"", null),
                                step("c", "\"3\"", "0.02")));
        assertEquals(new BigDecimal("0.03"), McpWorkflowRunResult.totalCostUsd(run));
        assertNull(
                McpWorkflowRunResult.totalCostUsd(
                        run(WorkflowRunStatus.COMPLETED, List.of(step("a", "\"1\"", null)))));
    }

    @Test
    void jsonCarriesStatusLastOutputAndTrimmedStepsButNeverNodeInputs() throws Exception {
        WorkflowRunResponse run =
                run(
                        WorkflowRunStatus.COMPLETED,
                        List.of(step("first", "\"one\"", null), step("last", "\"final\"", "0.5")));

        String json = McpWorkflowRunResult.toJson(run, 1000, null);
        JsonNode body = MAPPER.readTree(json);

        assertEquals("COMPLETED", body.get("status").asText());
        assertEquals("\"final\"", body.get("output").asText());
        assertEquals(2, body.get("steps").size());
        assertEquals("first", body.get("steps").get(0).get("node").asText());
        assertFalse(body.has("hint"));
        assertFalse(json.contains("secret"), "node inputs must not be returned to an MCP caller");
    }

    @Test
    void outputsAreTruncatedAndAHintIsCarriedWhenGiven() throws Exception {
        WorkflowRunResponse run =
                run(WorkflowRunStatus.RUNNING, List.of(step("a", "x".repeat(500), null)));

        JsonNode body = MAPPER.readTree(McpWorkflowRunResult.toJson(run, 100, "poll me"));

        assertTrue(body.get("output").asText().endsWith("[truncated]"));
        assertTrue(body.get("output").asText().length() < 130);
        assertEquals("poll me", body.get("hint").asText());
    }

    @Test
    void aRunWithNoTraceStillMapsCleanly() throws Exception {
        JsonNode body =
                MAPPER.readTree(
                        McpWorkflowRunResult.toJson(
                                run(WorkflowRunStatus.RUNNING, List.of()), 100, null));

        assertEquals("RUNNING", body.get("status").asText());
        assertFalse(body.has("output"));
        assertFalse(body.has("steps"));
    }
}
