package com.agenticai.core.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/** A session's prompts and costs: VIEWER on the agent plus owning the session, or EDITOR. */
class AgentSessionAccessGateTest {

    private final UUID agentId = UUID.randomUUID();
    private AgentService agentService;
    private SessionMemoryService sessions;
    private AgentSessionAccessGate gate;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        sessions = mock(SessionMemoryService.class);
        gate = new AgentSessionAccessGate(agentService, sessions);
        TenantContext.set(TenantContext.of("acme", "alice", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockHttpServletRequest request(String sessionId) {
        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "GET",
                        "/api/agents/" + agentId + "/sessions/" + sessionId + "/llm-requests");
        request.setAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("agentId", agentId.toString(), "sessionId", sessionId));
        request.setUserPrincipal(() -> "alice");
        return request;
    }

    private void viewerOnly() {
        doThrow(new ResourceAccessDeniedException())
                .when(agentService)
                .requireAccess(agentId, PermissionLevel.EDITOR);
    }

    @Test
    void theSessionOwnerWithViewerAccessGetsThrough() {
        viewerOnly();
        String scoped = SessionMemoryService.scopedSessionId("acme", agentId, "alice", "s1");
        when(sessions.isOwnedBy(scoped, "acme", "alice")).thenReturn(true);

        assertThat(gate.preHandle(request("s1"), new MockHttpServletResponse(), null)).isTrue();
        verify(agentService).requireAccess(agentId, PermissionLevel.VIEWER);
        verify(agentService, never()).requireAccess(agentId, PermissionLevel.EDITOR);
    }

    @Test
    void someoneElsesSessionNeedsEditor() {
        viewerOnly();
        when(sessions.isOwnedBy(anyString(), eq("acme"), eq("alice"))).thenReturn(false);

        assertThatThrownBy(() -> gate.preHandle(request("s1"), new MockHttpServletResponse(), null))
                .isInstanceOf(ResourceAccessDeniedException.class);
    }

    @Test
    void anAgentEditorReadsAnySession() {
        when(sessions.isOwnedBy(any(), any(), any())).thenReturn(false);
        assertThat(gate.preHandle(request("s1"), new MockHttpServletResponse(), null)).isTrue();
        verify(agentService).requireAccess(agentId, PermissionLevel.EDITOR);
    }

    @Test
    void noViewerAccessStopsEveryone() {
        doThrow(new ResourceAccessDeniedException())
                .when(agentService)
                .requireAccess(agentId, PermissionLevel.VIEWER);
        assertThatThrownBy(() -> gate.preHandle(request("s1"), new MockHttpServletResponse(), null))
                .isInstanceOf(ResourceAccessDeniedException.class);
        verify(sessions, never()).isOwnedBy(any(), any(), any());
    }
}
