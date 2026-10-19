package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code AgentResponse.accessLevel} comes from probing the SAME {@code requireAccess} rules the endpoints
 * enforce (owner / admin / grant), top down, never from re-implemented grant logic.
 */
class AgentAccessLevelTest {

    private ResourceAccessService access;
    private Agent agent;

    @BeforeEach
    void setUp() {
        access = mock(ResourceAccessService.class);
        agent = mock(Agent.class);
        org.mockito.Mockito.when(agent.getId()).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.when(agent.getCreatedBy()).thenReturn("owner");
    }

    /** Everything above {@code highest} is refused. */
    private void allow(PermissionLevel highest) {
        for (PermissionLevel level : PermissionLevel.values()) {
            if (level.compareTo(highest) > 0) {
                doThrow(new ResourceAccessDeniedException()).when(access)
                    .requireAccess(eq(ResourceType.AGENT), any(), any(), eq(level));
            }
        }
    }

    @Test
    void ownerOrAdminOrEditorGrantIsEditor() {
        allow(PermissionLevel.EDITOR);
        assertEquals("EDITOR", AgentAccessLevel.of(access, agent));
    }

    @Test
    void runnerGrantIsRunner() {
        allow(PermissionLevel.RUNNER);
        assertEquals("RUNNER", AgentAccessLevel.of(access, agent));
    }

    @Test
    void viewerGrantIsViewer() {
        allow(PermissionLevel.VIEWER);
        assertEquals("VIEWER", AgentAccessLevel.of(access, agent));
    }

    @Test
    void anUnresolvableContextYieldsNullInsteadOfFailingTheRead() {
        doThrow(new IllegalStateException("no tenant context")).when(access)
            .requireAccess(any(), any(), any(), any());
        assertNull(AgentAccessLevel.of(access, agent));
    }
}
