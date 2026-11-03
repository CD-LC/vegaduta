package com.agenticai.core.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Memories are about people, so VIEWER on the agent covers only the caller's own; anything about
 * someone else takes EDITOR.
 */
class AgentMemoryControllerTest {

    private final UUID agentId = UUID.randomUUID();
    private final UUID memoryId = UUID.randomUUID();
    private final Principal alice = () -> "alice";
    private AgentService agentService;
    private AgentMemoryService memoryService;
    private AgentMemoryController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        agentService = mock(AgentService.class);
        memoryService = mock(AgentMemoryService.class);
        ObjectProvider<AgentMemoryService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(memoryService);
        when(memoryService.listAll(any(), any())).thenReturn(List.of());
        controller = new AgentMemoryController(agentService, provider);
    }

    private void viewerOnly() {
        doThrow(new ResourceAccessDeniedException())
                .when(agentService)
                .requireAccess(agentId, PermissionLevel.EDITOR);
    }

    @Test
    void aViewerReadsTheirOwnMemories() {
        viewerOnly();
        assertThat(controller.memories(agentId, "alice", alice)).isEmpty();
        verify(memoryService).listAll(agentId, "alice");
    }

    @Test
    void aViewerCannotReadSomeoneElsesMemories() {
        viewerOnly();
        assertThatThrownBy(() -> controller.memories(agentId, "bob", alice))
                .isInstanceOf(ResourceAccessDeniedException.class);
        verify(memoryService, never()).listAll(any(), any());
    }

    @Test
    void anEditorReadsAnySubjectsMemories() {
        controller.memories(agentId, "bob", alice);
        verify(memoryService).listAll(agentId, "bob");
    }

    @Test
    void aViewersDeleteIsLimitedToTheirOwnMemories() {
        viewerOnly();
        assertThat(controller.forgetMemory(agentId, memoryId, alice).getStatusCode().value())
                .isEqualTo(204);
        verify(memoryService).forget(agentId, memoryId, "alice");
        verify(memoryService, never()).forget(agentId, memoryId);
    }

    @Test
    void anEditorDeletesAnyMemoryOfTheAgent() {
        controller.forgetMemory(agentId, memoryId, alice);
        verify(memoryService).forget(agentId, memoryId);
    }

    @Test
    void aViewerApprovesOnlyTheirOwnQuarantinedMemory() {
        viewerOnly();
        when(memoryService.approve(agentId, memoryId, "alice")).thenReturn(true);

        assertThat(controller.approveMemory(agentId, memoryId, alice).getStatusCode().value()).isEqualTo(204);
        verify(memoryService, never()).approve(agentId, memoryId);
    }

    @Test
    void aViewerCannotApproveSomeoneElsesMemory() {
        viewerOnly();
        when(memoryService.approve(agentId, memoryId, "alice")).thenReturn(false);

        assertThat(controller.approveMemory(agentId, memoryId, alice).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void anEditorApprovesAnyQuarantinedMemory() {
        when(memoryService.approve(agentId, memoryId)).thenReturn(true);

        assertThat(controller.approveMemory(agentId, memoryId, alice).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void rejectDeletesTheMemory() {
        controller.rejectMemory(agentId, memoryId, alice);
        verify(memoryService).forget(agentId, memoryId);
    }

    @Test
    void theSharedPoolIsReviewedByEditorsOnly() {
        viewerOnly();
        assertThatThrownBy(() -> controller.sharedMemories(agentId, "alice"))
                .isInstanceOf(ResourceAccessDeniedException.class);
        assertThatThrownBy(() -> controller.approveSharedMemory(agentId, memoryId, "alice"))
                .isInstanceOf(ResourceAccessDeniedException.class);
    }
}
