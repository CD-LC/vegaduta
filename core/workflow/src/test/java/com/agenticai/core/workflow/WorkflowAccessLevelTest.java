package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.agenticai.core.workflow.dto.WorkflowResponse;
import com.agenticai.core.workflow.temporal.TemporalWorkflowExecutionEngine;
import com.agenticai.core.workflow.version.WorkflowVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code WorkflowResponse.accessLevel} is the canvas's hint for hiding the Run controls from
 * callers the server would refuse (a draft run needs EDITOR). It must come from the SAME rules as
 * {@code requireAccess}, so it is derived by probing that call from the top down instead of
 * re-implementing owner/admin/grant logic.
 */
class WorkflowAccessLevelTest {

    private ResourceAccessService access;
    private WorkflowService service;
    private WorkflowDefinition definition;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        access = mock(ResourceAccessService.class);
        ObjectProvider<TemporalWorkflowExecutionEngine> temporalProvider =
                mock(ObjectProvider.class);
        service =
                WorkflowServiceFixture.of(
                        mock(WorkflowDefinitionRepository.class),
                        mock(WorkflowRunRepository.class),
                        mock(OrchestratorService.class),
                        mock(WorkflowExecutionEngine.class),
                        new ObjectMapper(),
                        mock(TenantResolutionService.class),
                        mock(TenantSchemaResolver.class),
                        mock(TenantLimits.class),
                        mock(WorkflowTriggerNodeService.class),
                        temporalProvider,
                        mock(EncryptionService.class),
                        mock(WorkflowRunCancellationService.class),
                        mock(WorkflowRunPauseService.class),
                        access,
                        mock(WorkflowExecutionParamsInferenceService.class),
                        mock(WorkflowVersionService.class));
        definition =
                new WorkflowDefinition(
                        "WF", "d", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.DRAFT);
        java.lang.reflect.Field id = WorkflowDefinition.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(definition, UUID.randomUUID());
    }

    private void allow(PermissionLevel highest) {
        for (PermissionLevel level : PermissionLevel.values()) {
            if (level.compareTo(highest) > 0) {
                doThrow(new ResourceAccessDeniedException())
                        .when(access)
                        .requireAccess(eq(ResourceType.WORKFLOW), any(), any(), eq(level));
            }
        }
    }

    @Test
    void editorLevelCallersAreEditors() {
        allow(PermissionLevel.EDITOR);
        assertEquals("EDITOR", service.accessLevelOf(definition));
    }

    @Test
    void runnerGrantIsRunnerNotEditor() {
        allow(PermissionLevel.RUNNER);
        assertEquals("RUNNER", service.accessLevelOf(definition));
    }

    @Test
    void viewerGrantIsViewer() {
        // A VIEWER grant also satisfies the RUNNER check (levels are cumulative), so only EDITOR is
        // refused.
        allow(PermissionLevel.VIEWER);
        assertEquals("VIEWER", service.accessLevelOf(definition));
    }

    @Test
    void responseCarriesTheLevelAndInternalCallersDefaultToEditor() {
        WorkflowResponse viaService =
                WorkflowResponse.from(definition, new ObjectMapper(), true, "RUNNER");
        assertEquals("RUNNER", viaService.accessLevel());
        assertEquals("EDITOR", WorkflowResponse.from(definition, new ObjectMapper()).accessLevel());
    }

    @Test
    void toResponseUsesTheProbedLevel() {
        allow(PermissionLevel.RUNNER);
        when(access.requireAccess(
                        eq(ResourceType.WORKFLOW), any(), any(), eq(PermissionLevel.RUNNER)))
                .thenReturn(com.agenticai.core.sharing.AccessCheckResult.GRANTED);
        assertEquals("RUNNER", service.toResponse(definition).accessLevel());
    }
}
