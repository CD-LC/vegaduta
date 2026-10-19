package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The cancel flag is keyed by the caller-scoped session id the turn watches (AgentChatService), so
 * a runner who knows another user's raw session id cannot stop that user's turn.
 */
class AgentChatSessionControllerChatCancelTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void cancelUsesTheCallersScopedSessionId() {
        AgentService agentService = mock(AgentService.class);
        ChatTurnCancelService cancels = mock(ChatTurnCancelService.class);
        AgentChatSessionController controller =
                new AgentChatSessionController(agentService, null, null, null, cancels, null);
        UUID agentId = UUID.randomUUID();
        TenantContext.set(TenantContext.of("acme", "bob", Set.of()));

        controller.cancelChatTurn(agentId, "s1", () -> "bob");

        verify(agentService).requireAccess(agentId, PermissionLevel.RUNNER);
        String bobs = SessionMemoryService.scopedSessionId("acme", agentId, "bob", "s1");
        verify(cancels).requestCancellation(agentId, bobs);
        assertThat(bobs)
                .isNotEqualTo(SessionMemoryService.scopedSessionId("acme", agentId, "alice", "s1"));
    }
}
