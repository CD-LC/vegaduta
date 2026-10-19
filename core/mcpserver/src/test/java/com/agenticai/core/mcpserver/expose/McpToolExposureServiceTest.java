package com.agenticai.core.mcpserver.expose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The per-resource switch: default off, EDITOR to change, stable tool names, collision-safe. */
class McpToolExposureServiceTest {

    private McpToolExposureRepository repository;
    private AgentService agentService;
    private WorkflowService workflowService;
    private AuditLogService auditLogService;
    private McpToolExposureService service;

    private final UUID agentId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(McpToolExposureRepository.class);
        agentService = mock(AgentService.class);
        workflowService = mock(WorkflowService.class);
        auditLogService = mock(AuditLogService.class);
        service = new McpToolExposureService(repository, agentService, workflowService, auditLogService);
        when(repository.save(any(McpToolExposure.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findAll()).thenReturn(List.of());
        when(repository.findByKindAndResourceId(any(), any())).thenReturn(Optional.empty());
    }

    private void agentNamed(String name) {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(name);
        when(agentService.requireAccess(eq(agentId), any(PermissionLevel.class))).thenReturn(agent);
    }

    @Test
    void aResourceThatWasNeverExposedReadsAsNotExposed() {
        agentNamed("Helper");

        McpToolExposureService.State state = service.get(McpExposureKind.AGENT, agentId);

        assertFalse(state.exposed());
        assertNull(state.toolName());
        verify(agentService).requireAccess(agentId, PermissionLevel.VIEWER);
    }

    @Test
    void enablingAllocatesAStableSluggedNameAndAuditsIt() {
        agentNamed("Support Bot");

        McpToolExposureService.State state = service.setExposed(McpExposureKind.AGENT, agentId, true);

        assertTrue(state.exposed());
        assertEquals("agent_support_bot", state.toolName());
        verify(agentService).requireAccess(agentId, PermissionLevel.EDITOR);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(any(), eq("mcpserver.exposure.enabled"), eq("Agent"), eq(agentId.toString()),
            eq(AuditOutcome.SUCCESS), detail.capture());
        assertTrue(detail.getValue().contains("[channel=mcp]"));
    }

    @Test
    void aSecondResourceWithTheSameNameGetsADistinctToolName() {
        agentNamed("Helper");
        when(repository.findAll()).thenReturn(List.of(
            new McpToolExposure(McpExposureKind.AGENT, UUID.randomUUID(), "agent_helper", true, "someone")));

        assertEquals("agent_helper_2", service.setExposed(McpExposureKind.AGENT, agentId, true).toolName());
    }

    @Test
    void anAgentAndAWorkflowWithTheSameNameDoNotCollide() {
        WorkflowDefinition workflow = mock(WorkflowDefinition.class);
        when(workflow.getName()).thenReturn("Helper");
        when(workflowService.requireEditorAccess(workflowId)).thenReturn(workflow);
        when(repository.findAll()).thenReturn(List.of(
            new McpToolExposure(McpExposureKind.AGENT, UUID.randomUUID(), "agent_helper", true, "someone")));

        assertEquals("workflow_helper", service.setExposed(McpExposureKind.WORKFLOW, workflowId, true).toolName());
        verify(workflowService).requireEditorAccess(workflowId);
    }

    @Test
    void disablingKeepsTheNameAndReEnablingReusesIt() {
        agentNamed("Renamed Since");
        McpToolExposure row = new McpToolExposure(McpExposureKind.AGENT, agentId, "agent_original_name", true, "me");
        when(repository.findByKindAndResourceId(McpExposureKind.AGENT, agentId)).thenReturn(Optional.of(row));

        McpToolExposureService.State off = service.setExposed(McpExposureKind.AGENT, agentId, false);
        assertFalse(off.exposed());
        assertEquals("agent_original_name", off.toolName());
        verify(auditLogService).record(any(), eq("mcpserver.exposure.disabled"), anyString(), anyString(),
            eq(AuditOutcome.SUCCESS), anyString());

        McpToolExposureService.State on = service.setExposed(McpExposureKind.AGENT, agentId, true);
        assertTrue(on.exposed());
        assertEquals("agent_original_name", on.toolName());
    }

    @Test
    void stayingNotExposedCreatesNoRow() {
        agentNamed("Helper");

        McpToolExposureService.State state = service.setExposed(McpExposureKind.AGENT, agentId, false);

        assertFalse(state.exposed());
        verify(repository, never()).save(any(McpToolExposure.class));
        verify(auditLogService, never()).record(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void settingTheCurrentValueAgainChangesAndAuditsNothing() {
        agentNamed("Helper");
        McpToolExposure row = new McpToolExposure(McpExposureKind.AGENT, agentId, "agent_helper", true, "me");
        when(repository.findByKindAndResourceId(McpExposureKind.AGENT, agentId)).thenReturn(Optional.of(row));

        assertTrue(service.setExposed(McpExposureKind.AGENT, agentId, true).exposed());

        verify(repository, never()).save(any(McpToolExposure.class));
        verify(auditLogService, never()).record(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void aCallerWithoutEditAccessChangesNothing() {
        when(agentService.requireAccess(eq(agentId), eq(PermissionLevel.EDITOR)))
            .thenThrow(new IllegalStateException("not found"));

        assertThrows(IllegalStateException.class, () -> service.setExposed(McpExposureKind.AGENT, agentId, true));

        verify(repository, never()).save(any(McpToolExposure.class));
        verify(auditLogService, never()).record(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void aCallerWhoCannotSeeTheResourceCannotReadItsState() {
        when(agentService.requireAccess(eq(agentId), eq(PermissionLevel.VIEWER)))
            .thenThrow(new IllegalStateException("not found"));

        assertThrows(IllegalStateException.class, () -> service.get(McpExposureKind.AGENT, agentId));
        verify(repository, never()).findByKindAndResourceId(any(), any());
    }

    @Test
    void listExposedReturnsOnlyEnabledRowsFromTheRepository() {
        McpToolExposure row = new McpToolExposure(McpExposureKind.AGENT, agentId, "agent_helper", true, "me");
        when(repository.findByExposedTrue()).thenReturn(List.of(row));

        assertEquals(List.of(row), service.listExposed());
    }

    @Test
    void pathSegmentsParseAndUnknownOnesAre400s() {
        assertEquals(McpExposureKind.AGENT, McpExposureKind.fromPathSegment("agents"));
        assertEquals(McpExposureKind.WORKFLOW, McpExposureKind.fromPathSegment("Workflows"));
        assertThrows(IllegalArgumentException.class, () -> McpExposureKind.fromPathSegment("tenants"));
        assertThrows(IllegalArgumentException.class, () -> McpExposureKind.fromPathSegment(null));
    }
}
