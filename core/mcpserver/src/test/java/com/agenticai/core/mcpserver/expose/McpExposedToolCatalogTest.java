package com.agenticai.core.mcpserver.expose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.mcpserver.McpCallerContext;
import com.agenticai.core.mcpserver.McpCallerContextTestAccess;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.WorkflowStatus;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The catalog is the "no cross-tenant / no cross-grant listing" gate: a tool is visible only when
 * the resource's own switch is on AND the caller's grant lists it AND it still resolves.
 */
class McpExposedToolCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpToolExposureService exposureService;
    private AgentService agentService;
    private WorkflowService workflowService;
    private McpExposedToolCatalog catalog;

    private final UUID agentA = UUID.randomUUID();
    private final UUID agentB = UUID.randomUUID();
    private final UUID workflowW = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        exposureService = mock(McpToolExposureService.class);
        agentService = mock(AgentService.class);
        workflowService = mock(WorkflowService.class);
        catalog = new McpExposedToolCatalog(exposureService, agentService, workflowService);
    }

    @AfterEach
    void tearDown() {
        McpCallerContextTestAccess.clear();
    }

    private McpCallerContext caller(Set<UUID> agents, Set<UUID> workflows) {
        McpCallerContextTestAccess.set(UUID.randomUUID(), agents, workflows, Set.of());
        return McpCallerContext.current().orElseThrow();
    }

    private static McpToolExposure row(McpExposureKind kind, UUID id, String toolName) {
        return new McpToolExposure(kind, id, toolName, true, "tester");
    }

    private void agentExists(UUID id, String name) {
        AgentResponse response = mock(AgentResponse.class);
        when(response.name()).thenReturn(name);
        when(agentService.getGrantedAgent(id)).thenReturn(response);
    }

    private void workflowExists(UUID id, String name, WorkflowStatus status, WorkflowGraphDto graph) {
        WorkflowDefinition definition = mock(WorkflowDefinition.class);
        when(definition.getId()).thenReturn(id);
        when(definition.getName()).thenReturn(name);
        when(definition.getStatus()).thenReturn(status);
        when(workflowService.get(id)).thenReturn(definition);
        when(workflowService.resolveRunGraph(definition)).thenReturn(graph);
    }

    @Test
    void listsOnlyResourcesThatAreExposedAndGrantedToThisCaller() {
        when(exposureService.listExposed()).thenReturn(List.of(
            row(McpExposureKind.AGENT, agentA, "agent_alpha"),
            row(McpExposureKind.AGENT, agentB, "agent_beta")));
        agentExists(agentA, "Alpha");
        agentExists(agentB, "Beta");

        // The grant lists only agent A: agent B is exposed by its owner but not granted to this key.
        List<McpExposedTool> tools = catalog.forCaller(caller(Set.of(agentA), Set.of()));

        assertEquals(List.of("agent_alpha"), tools.stream().map(McpExposedTool::name).toList());
    }

    @Test
    void aGrantedButNotExposedResourceIsNotListed() {
        // No exposure rows at all: the grant alone (today's call_agent allow-list) never creates a dynamic tool.
        when(exposureService.listExposed()).thenReturn(List.of());
        agentExists(agentA, "Alpha");

        assertTrue(catalog.forCaller(caller(Set.of(agentA), Set.of(workflowW))).isEmpty());
    }

    @Test
    void anotherGrantSeesNothingOfThisOnesTools() {
        when(exposureService.listExposed()).thenReturn(List.of(row(McpExposureKind.AGENT, agentA, "agent_alpha")));
        agentExists(agentA, "Alpha");

        assertTrue(catalog.forCaller(caller(Set.of(agentB), Set.of())).isEmpty());
    }

    @Test
    void noCallerContextMeansNoTools() {
        when(exposureService.listExposed()).thenReturn(List.of(row(McpExposureKind.AGENT, agentA, "agent_alpha")));
        assertTrue(catalog.forCaller(null).isEmpty());
    }

    @Test
    void aDeletedAgentOrArchivedWorkflowDropsOutInsteadOfBreakingTheListing() {
        when(exposureService.listExposed()).thenReturn(List.of(
            row(McpExposureKind.AGENT, agentA, "agent_alpha"),
            row(McpExposureKind.AGENT, agentB, "agent_gone"),
            row(McpExposureKind.WORKFLOW, workflowW, "workflow_old")));
        agentExists(agentA, "Alpha");
        when(agentService.getGrantedAgent(agentB)).thenThrow(new IllegalStateException("deleted"));
        workflowExists(workflowW, "Old", WorkflowStatus.ARCHIVED, new WorkflowGraphDto(List.of(), List.of()));

        List<McpExposedTool> tools = catalog.forCaller(caller(Set.of(agentA, agentB), Set.of(workflowW)));

        assertEquals(List.of("agent_alpha"), tools.stream().map(McpExposedTool::name).toList());
    }

    @Test
    void getRunIsAdvertisedOnlyAlongsideAtLeastOneWorkflowTool() {
        when(exposureService.listExposed()).thenReturn(List.of(
            row(McpExposureKind.AGENT, agentA, "agent_alpha"),
            row(McpExposureKind.WORKFLOW, workflowW, "workflow_flow")));
        agentExists(agentA, "Alpha");
        workflowExists(workflowW, "Flow", WorkflowStatus.ACTIVE, new WorkflowGraphDto(List.of(), List.of()));

        assertEquals(List.of("agent_alpha"),
            catalog.forCaller(caller(Set.of(agentA), Set.of())).stream().map(McpExposedTool::name).toList());
        assertEquals(List.of("agent_alpha", "get_run", "workflow_flow"),
            catalog.forCaller(caller(Set.of(agentA), Set.of(workflowW))).stream().map(McpExposedTool::name).toList());
    }

    @Test
    void agentToolSchemaTakesAMessageAndAnOptionalConversationId() throws Exception {
        when(exposureService.listExposed()).thenReturn(List.of(row(McpExposureKind.AGENT, agentA, "agent_alpha")));
        agentExists(agentA, "Alpha");

        McpExposedTool tool = catalog.forCaller(caller(Set.of(agentA), Set.of())).get(0);
        JsonNode schema = MAPPER.readTree(tool.inputSchemaJson());

        assertEquals("message", schema.get("required").get(0).asText());
        assertEquals(1, schema.get("required").size());
        assertTrue(schema.get("properties").has("conversationId"));
        // The model-facing description names the agent but never carries its prompt or config.
        assertTrue(tool.description().contains("Alpha"));
        assertEquals(McpExposedTool.Target.AGENT, tool.target());
        assertEquals(agentA, tool.resourceId());
    }

    @Test
    void workflowSchemaIsDerivedFromTheTriggerNodeWhenItDeclaresInputs() throws Exception {
        Map<String, Object> fieldCustomer = new HashMap<>();
        fieldCustomer.put("key", "customer");
        fieldCustomer.put("type", "string");
        fieldCustomer.put("required", Boolean.TRUE);
        Map<String, Object> config = new HashMap<>();
        config.put("fields", List.of(fieldCustomer));
        Map<String, Object> data = new HashMap<>();
        data.put("config", config);
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(new WorkflowNodeDto("t1", "trigger", Map.of(), data)), List.of());
        when(exposureService.listExposed()).thenReturn(List.of(row(McpExposureKind.WORKFLOW, workflowW, "workflow_flow")));
        workflowExists(workflowW, "Flow", WorkflowStatus.ACTIVE, graph);

        McpExposedTool tool = catalog.forCaller(caller(Set.of(), Set.of(workflowW))).stream()
            .filter(t -> t.target() == McpExposedTool.Target.WORKFLOW).findFirst().orElseThrow();
        JsonNode schema = MAPPER.readTree(tool.inputSchemaJson());

        assertEquals("string", schema.get("properties").get("customer").get("type").asText());
        assertEquals("customer", schema.get("required").get(0).asText());
    }

    @Test
    void workflowWithoutDeclaredInputsGetsAnOpenObject() throws Exception {
        when(exposureService.listExposed()).thenReturn(List.of(row(McpExposureKind.WORKFLOW, workflowW, "workflow_flow")));
        workflowExists(workflowW, "Flow", WorkflowStatus.ACTIVE, new WorkflowGraphDto(List.of(), List.of()));

        McpExposedTool tool = catalog.forCaller(caller(Set.of(), Set.of(workflowW))).stream()
            .filter(t -> t.target() == McpExposedTool.Target.WORKFLOW).findFirst().orElseThrow();

        assertTrue(MAPPER.readTree(tool.inputSchemaJson()).get("additionalProperties").asBoolean());
    }

    @Test
    void findResolvesOnlyNamesInTheCallersOwnListing() {
        when(exposureService.listExposed()).thenReturn(List.of(
            row(McpExposureKind.AGENT, agentA, "agent_alpha"),
            row(McpExposureKind.AGENT, agentB, "agent_beta")));
        agentExists(agentA, "Alpha");
        agentExists(agentB, "Beta");
        McpCallerContext caller = caller(Set.of(agentA), Set.of());

        assertTrue(catalog.find(caller, "agent_alpha").isPresent());
        assertFalse(catalog.find(caller, "agent_beta").isPresent());
        assertFalse(catalog.find(caller, "agent_nope").isPresent());
        assertFalse(catalog.find(caller, null).isPresent());
    }
}
