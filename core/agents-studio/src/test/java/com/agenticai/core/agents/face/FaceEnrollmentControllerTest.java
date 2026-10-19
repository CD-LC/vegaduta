package com.agenticai.core.agents.face;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The consent flag is policy, not access: listing and enrolling need a grant on the agent, so a
 * member who merely knows a consent-enabled agent's id gets nothing (authorization review
 * 2026-10-10).
 */
class FaceEnrollmentControllerTest {

    private final FaceEnrollmentService service = mock(FaceEnrollmentService.class);
    private final AgentService agents = mock(AgentService.class);
    private final FaceEnrollmentController controller =
            new FaceEnrollmentController(service, agents);
    private final UUID agentId = UUID.randomUUID();
    private final UUID collectionId = UUID.randomUUID();

    @Test
    void listingRequiresRunnerOnTheAgentBeforeAnyTemplateIsRead() {
        when(agents.requireAccess(agentId, PermissionLevel.RUNNER))
                .thenThrow(new ResourceAccessDeniedException());

        assertThrows(
                ResourceAccessDeniedException.class, () -> controller.list(agentId, collectionId, null, null));
        verify(service, never()).listForCollection(any(), any());
    }

    @Test
    void aRunnerListsTheCollection() {
        when(service.listForCollection(agentId, collectionId)).thenReturn(List.of());

        assertEquals(List.of(), controller.list(agentId, collectionId, null, null).getBody());
        verify(agents).requireAccess(agentId, PermissionLevel.RUNNER);
    }

    @Test
    void enrollingRequiresEditorOnTheAgent() {
        when(agents.requireAccess(agentId, PermissionLevel.EDITOR))
                .thenThrow(new ResourceAccessDeniedException());
        var request =
                new FaceEnrollmentController.FaceEnrollmentRequest(
                        agentId, collectionId, null, "Ada", Collections.nCopies(512, 0.1), true);

        assertThrows(ResourceAccessDeniedException.class, () -> controller.enroll(request));
        verify(service, never()).enroll(any(), any(), any(), anyString(), anyList(), anyBoolean());
    }

    @Test
    void anEditorEnrolls() {
        when(service.enroll(eq(agentId), eq(collectionId), any(), eq("Ada"), anyList(), eq(true)))
                .thenReturn(UUID.randomUUID());
        var request =
                new FaceEnrollmentController.FaceEnrollmentRequest(
                        agentId, collectionId, null, "Ada", Collections.nCopies(512, 0.1), true);

        assertEquals(201, controller.enroll(request).getStatusCode().value());
        verify(agents).requireAccess(agentId, PermissionLevel.EDITOR);
    }
}
