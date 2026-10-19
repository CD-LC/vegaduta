package com.agenticai.core.mcpserver.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcpserver.McpCallerContextTestAccess;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.WorkflowStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Workflow-side mirror of {@code McpListAgentsToolCallbackTest} - same
 * leak-prevention stakes (the listing must be the grant's exposed set,
 * nothing more), plus the status field: a caller deciding whether to
 * trigger a workflow needs to see DRAFT vs ACTIVE, since triggering a
 * draft is a legitimate-but-surprising thing to let an external key do.
 */
class McpListMyWorkflowsToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowService workflowService;
    private McpListMyWorkflowsToolCallback callback;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        callback = new McpListMyWorkflowsToolCallback(workflowService);
    }

    @AfterEach
    void tearDown() {
        McpCallerContextTestAccess.clear();
    }

    private WorkflowDefinition workflowNamed(UUID id, String name, WorkflowStatus status) {
        WorkflowDefinition workflow = new WorkflowDefinition(name, "desc", "{}", status);
        ReflectionTestUtils.setField(workflow, "id", id);
        return workflow;
    }

    @Test
    void listsExactlyTheGrantsExposedWorkflowsWithIdNameAndStatus() throws Exception {
        UUID workflowId = UUID.randomUUID();
        McpCallerContextTestAccess.set(UUID.randomUUID(), Set.of(), Set.of(workflowId), Set.of());
        when(workflowService.get(workflowId)).thenReturn(workflowNamed(workflowId, "Invoice Chaser", WorkflowStatus.ACTIVE));

        JsonNode result = MAPPER.readTree(callback.call("{}"));

        assertEquals(1, result.size());
        assertEquals(workflowId.toString(), result.get(0).get("id").asText());
        assertEquals("Invoice Chaser", result.get(0).get("name").asText());
        assertEquals("ACTIVE", result.get(0).get("status").asText());
        // id/name/status only - the graph JSON (which can embed prompts,
        // webhook URLs, connector details) stays inside the platform.
        assertEquals(3, result.get(0).size());
    }

    @Test
    void anEmptyExposedSetListsNothingAndNeverQueriesTheWorkflowService() throws Exception {
        McpCallerContextTestAccess.set(UUID.randomUUID(), Set.of(), Set.of(), Set.of());

        JsonNode result = MAPPER.readTree(callback.call("{}"));

        assertTrue(result.isArray());
        assertEquals(0, result.size());
        verify(workflowService, never()).get(any(UUID.class));
    }

    @Test
    void missingCallerContextDegradesToAnEmptyListNotAnException() throws Exception {
        JsonNode result = MAPPER.readTree(callback.call("{}"));
        assertTrue(result.isArray());
        assertEquals(0, result.size());
    }

    @Test
    void aDeletedWorkflowIsOmittedWhileTheRestOfTheListingSurvives() throws Exception {
        UUID liveId = UUID.randomUUID();
        UUID deletedId = UUID.randomUUID();
        McpCallerContextTestAccess.set(UUID.randomUUID(), Set.of(), new HashSet<>(Set.of(liveId, deletedId)), Set.of());
        when(workflowService.get(liveId)).thenReturn(workflowNamed(liveId, "Still Here", WorkflowStatus.DRAFT));
        when(workflowService.get(deletedId)).thenThrow(new RuntimeException("workflow not found"));

        JsonNode result = MAPPER.readTree(callback.call("{}"));

        assertEquals(1, result.size());
        assertEquals("Still Here", result.get(0).get("name").asText());
        assertEquals("DRAFT", result.get(0).get("status").asText());
    }
}
