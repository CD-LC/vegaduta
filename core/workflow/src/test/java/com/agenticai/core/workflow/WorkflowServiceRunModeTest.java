package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.agenticai.core.workflow.temporal.TemporalWorkflowExecutionEngine;
import com.agenticai.core.workflow.version.WorkflowVersion;
import com.agenticai.core.workflow.version.WorkflowVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The canvas "Run" graph question and the entry-point guarantee. Verified 2026-10-01: before draft
 * runs, canvas Run executed the PRODUCTION snapshot ({@code resolveRunGraph}), not the graph on the
 * canvas. These tests pin down: the live graph runs only for DRAFT; every mode-less overload of
 * {@code WorkflowService.run} (the ones the webhook, cron, channel, MCP, share-link, sub-workflow
 * and agent-schedule paths use) is PRODUCTION and reaches the engine through the same mode-less
 * call it always made; and a graph save prunes the pins of removed nodes.
 */
class WorkflowServiceRunModeTest {

    private static final String SNAPSHOT_GRAPH =
            "{\"nodes\":[{\"id\":\"snap\",\"type\":\"trigger\",\"data\":{\"label\":\"Snap\"}}],\"edges\":[]}";
    private static final String LIVE_GRAPH =
            "{\"nodes\":[{\"id\":\"live\",\"type\":\"trigger\",\"data\":{\"label\":\"Live\"}}],\"edges\":[]}";

    private WorkflowDefinitionRepository definitionRepository;
    private WorkflowExecutionEngine engine;
    private TemporalWorkflowExecutionEngine temporal;
    private WorkflowVersionService versionService;
    private WorkflowPinStore pinStore;
    private WorkflowService service;
    private WorkflowDefinition definition;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        definitionRepository = mock(WorkflowDefinitionRepository.class);
        engine = mock(WorkflowExecutionEngine.class);
        temporal = mock(TemporalWorkflowExecutionEngine.class);
        versionService = mock(WorkflowVersionService.class);
        pinStore = mock(WorkflowPinStore.class);
        ObjectProvider<TemporalWorkflowExecutionEngine> temporalProvider =
                mock(ObjectProvider.class);
        when(temporalProvider.getIfAvailable()).thenReturn(temporal);
        service =
                WorkflowServiceFixture.of(
                        definitionRepository,
                        mock(WorkflowRunRepository.class),
                        mock(OrchestratorService.class),
                        engine,
                        new ObjectMapper(),
                        mock(TenantResolutionService.class),
                        mock(TenantSchemaResolver.class),
                        mock(TenantLimits.class),
                        mock(WorkflowTriggerNodeService.class),
                        temporalProvider,
                        mock(EncryptionService.class),
                        mock(WorkflowRunCancellationService.class),
                        mock(WorkflowRunPauseService.class),
                        mock(ResourceAccessService.class),
                        mock(WorkflowExecutionParamsInferenceService.class),
                        versionService);
        service.setPinStore(pinStore);

        definition = new WorkflowDefinition("WF", "d", LIVE_GRAPH, WorkflowStatus.DRAFT);
        java.lang.reflect.Field id = WorkflowDefinition.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(definition, UUID.randomUUID());
        when(definitionRepository.findById(definition.getId())).thenReturn(Optional.of(definition));
        when(versionService.resolveRunGraphJson(definition)).thenReturn(SNAPSHOT_GRAPH);
        WorkflowRun row = new WorkflowRun(UUID.randomUUID(), definition.getId(), "WF", "u");
        when(engine.execute(any(), any(), any(), any(), any(), any(), anyList())).thenReturn(row);
        WorkflowRun draftRow = new WorkflowRun(UUID.randomUUID(), definition.getId(), "WF", "u");
        draftRow.setRunMode(RunMode.DRAFT);
        when(engine.execute(any(), any(), any(), any(), any(), any(), anyList(), eq(RunMode.DRAFT)))
                .thenReturn(draftRow);
        when(temporal.execute(any(), any(), any(), any(), any(), any())).thenReturn(row);
        when(temporal.execute(any(), any(), any(), any(), any(), any(), eq(RunMode.DRAFT)))
                .thenReturn(draftRow);
    }

    private static String onlyNodeId(WorkflowGraphDto graph) {
        return graph.nodes().get(0).id();
    }

    private WorkflowGraphDto capturedEngineGraph() {
        ArgumentCaptor<WorkflowGraphDto> graph = ArgumentCaptor.forClass(WorkflowGraphDto.class);
        verify(engine)
                .execute(same(definition), graph.capture(), any(), any(), any(), any(), anyList());
        return graph.getValue();
    }

    @Test
    void everyModeLessRunOverloadIsProductionAndRunsTheSnapshot() {
        UUID id = definition.getId();
        service.run(id, "in", "u");
        service.run(id, "in", "u", UUID.randomUUID());
        service.run(id, "start", "in", "u");
        service.run(id, "start", "in", "u", UUID.randomUUID());
        service.run(id, "start", "in", "u", UUID.randomUUID(), List.of());

        ArgumentCaptor<WorkflowGraphDto> graphs = ArgumentCaptor.forClass(WorkflowGraphDto.class);
        verify(engine, org.mockito.Mockito.times(5))
                .execute(same(definition), graphs.capture(), any(), any(), any(), any(), anyList());
        assertTrue(
                graphs.getAllValues().stream().allMatch(g -> "snap".equals(onlyNodeId(g))),
                "production runs execute the PRODUCTION snapshot, never the live canvas graph");
        verify(engine, never())
                .execute(any(), any(), any(), any(), any(), any(), anyList(), any(RunMode.class));
    }

    @Test
    void theScheduledWorkflowPathIsProduction() {
        service.runScheduledWorkflow(definition.getId());
        assertEquals("snap", onlyNodeId(capturedEngineGraph()));
        verify(engine, never())
                .execute(any(), any(), any(), any(), any(), any(), anyList(), any(RunMode.class));
    }

    @Test
    void anExplicitProductionModeIsTheSameAsNone() {
        service.run(definition.getId(), null, "in", "u", null, List.of(), RunMode.PRODUCTION);
        service.run(definition.getId(), null, "in", "u", null, List.of(), null);
        ArgumentCaptor<WorkflowGraphDto> graphs = ArgumentCaptor.forClass(WorkflowGraphDto.class);
        verify(engine, org.mockito.Mockito.times(2))
                .execute(same(definition), graphs.capture(), any(), any(), any(), any(), anyList());
        assertTrue(graphs.getAllValues().stream().allMatch(g -> "snap".equals(onlyNodeId(g))));
    }

    @Test
    void aDraftRunExecutesTheLiveGraphAndCarriesTheMode() {
        WorkflowRunResponse response =
                service.run(definition.getId(), null, "in", "u", null, List.of(), RunMode.DRAFT);

        ArgumentCaptor<WorkflowGraphDto> graph = ArgumentCaptor.forClass(WorkflowGraphDto.class);
        verify(engine)
                .execute(
                        same(definition),
                        graph.capture(),
                        isNull(),
                        any(),
                        any(),
                        any(),
                        anyList(),
                        eq(RunMode.DRAFT));
        assertEquals(
                "live",
                onlyNodeId(graph.getValue()),
                "draft runs the graph as edited, not the snapshot");
        verify(engine, never()).execute(any(), any(), any(), any(), any(), any(), anyList());
        assertEquals("draft", response.mode());
        verify(versionService, never()).resolveRunGraphJson(definition);
    }

    @Test
    void theTemporalEngineGetsTheSameSplit() throws Exception {
        java.lang.reflect.Field runsField = WorkflowService.class.getDeclaredField("runs");
        runsField.setAccessible(true);
        java.lang.reflect.Field mode =
                WorkflowRunService.class.getDeclaredField("executionEngineMode");
        mode.setAccessible(true);
        mode.set(runsField.get(service), "temporal");

        service.run(definition.getId(), null, "in", "u", null, List.of());
        verify(temporal).execute(same(definition), any(), any(), any(), any(), any());
        verify(temporal, never())
                .execute(any(), any(), any(), any(), any(), any(), any(RunMode.class));

        service.run(definition.getId(), null, "in", "u", null, List.of(), RunMode.DRAFT);
        verify(temporal)
                .execute(same(definition), any(), any(), any(), any(), any(), eq(RunMode.DRAFT));
    }

    // ---- pins are pruned when a save removes their node

    private static WorkflowNodeDto node(String id) {
        return new WorkflowNodeDto(id, "code", Map.of(), Map.of("label", id));
    }

    @Test
    @SuppressWarnings("unchecked")
    void savingAGraphDeletesThePinsOfNodesItNoLongerContains() {
        service.update(
                definition.getId(),
                "WF",
                "d",
                new WorkflowGraphDto(List.of(node("a"), node("b")), List.of()),
                WorkflowStatus.DRAFT);

        ArgumentCaptor<java.util.Collection<String>> live =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(pinStore).deleteForRemovedNodes(eq(definition.getId()), live.capture());
        assertEquals(java.util.Set.of("a", "b"), new java.util.HashSet<>(live.getValue()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void restoringAVersionPrunesAgainstTheRestoredGraph() {
        WorkflowVersion version = mock(WorkflowVersion.class);
        when(version.getGraphJson()).thenReturn(SNAPSHOT_GRAPH);
        when(version.getWorkflowName()).thenReturn("WF");
        when(version.getDescription()).thenReturn("d");
        when(versionService.get(definition.getId(), 1)).thenReturn(version);

        service.restoreVersion(definition.getId(), 1);

        ArgumentCaptor<java.util.Collection<String>> live =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(pinStore).deleteForRemovedNodes(eq(definition.getId()), live.capture());
        assertEquals(List.of("snap"), List.copyOf(live.getValue()));
    }
}
