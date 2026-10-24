package com.agenticai.core.agents.promptdirectives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The directory behind the edge prompt bundle asks AgentService for VIEWER access, the same gate agent reads use. */
class AgentServicePromptAgentDirectoryTest {

    private final AgentService agents = mock(AgentService.class);
    private final AgentServicePromptAgentDirectory directory = new AgentServicePromptAgentDirectory(agents);
    private final UUID agent = UUID.randomUUID();

    @Test
    void aCallerWhoMayViewTheAgentCanView() {
        when(agents.requireAccess(agent, PermissionLevel.VIEWER)).thenReturn(mock(Agent.class));

        assertThat(directory.canViewInCurrentTenant(agent)).isTrue();
        verify(agents).requireAccess(eq(agent), eq(PermissionLevel.VIEWER));
    }

    @Test
    void aTenantMemberWithoutAGrantCannotEvenThoughTheAgentExists() {
        when(agents.requireAccess(agent, PermissionLevel.VIEWER)).thenThrow(new ResourceAccessDeniedException());

        assertThat(directory.canViewInCurrentTenant(agent)).isFalse();
    }

    @Test
    void anAgentOfAnotherTenantOrNoAgentCannotBeViewed() {
        when(agents.requireAccess(any(), any())).thenThrow(new AgentNotFoundException(agent));

        assertThat(directory.canViewInCurrentTenant(agent)).isFalse();
        assertThat(directory.canViewInCurrentTenant(null)).isFalse();
    }

    @Test
    void existenceStaysTheTenantOnlyCheckOtherCallersUse() {
        doThrow(new AgentNotFoundException(agent)).when(agents).requireExists(agent);

        assertThat(directory.existsInCurrentTenant(agent)).isFalse();
    }
}
