package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/** Rating a turn takes the same RUNNER access as chatting with the agent. */
class TurnFeedbackAccessGateTest {

    @Test
    void ratingATurnNeedsRunnerAccessToTheAgent() throws Exception {
        AgentService agents = mock(AgentService.class);
        UUID agentId = UUID.randomUUID();
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/agents/" + agentId + "/turns/feedback");
        request.setAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("agentId", agentId.toString()));
        TurnFeedbackAccessGate gate = new TurnFeedbackAccessGate(agents);

        gate.preHandle(request, new MockHttpServletResponse(), null);
        verify(agents).requireAccess(agentId, PermissionLevel.RUNNER);

        doThrow(new ResourceAccessDeniedException())
                .when(agents)
                .requireAccess(agentId, PermissionLevel.RUNNER);
        assertThatThrownBy(() -> gate.preHandle(request, new MockHttpServletResponse(), null))
                .isInstanceOf(ResourceAccessDeniedException.class);
    }
}
