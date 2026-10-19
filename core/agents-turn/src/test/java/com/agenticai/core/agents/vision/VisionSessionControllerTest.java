package com.agenticai.core.agents.vision;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Frames go through the agent's chat path, so starting a session takes chat's RUNNER access. */
class VisionSessionControllerTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void startingASessionNeedsRunnerAccessToTheAgent() {
        VisionSessionService sessions = mock(VisionSessionService.class);
        AgentService agents = mock(AgentService.class);
        UUID agentId = UUID.randomUUID();
        doThrow(new ResourceAccessDeniedException())
                .when(agents)
                .requireAccess(agentId, PermissionLevel.RUNNER);
        TenantContext.set(TenantContext.of("acme", "alice", Set.of()));
        VisionSessionController controller = new VisionSessionController(sessions, agents);

        assertThatThrownBy(
                        () ->
                                controller.start(
                                        new VisionSessionController.VisionSessionStartRequest(
                                                agentId,
                                                VisionSessionMode.WATCH,
                                                VisionSource.CAMERA),
                                        () -> "alice"))
                .isInstanceOf(ResourceAccessDeniedException.class);
        verify(sessions, never()).start(any(), any(), any(), any(), any());
    }
}
