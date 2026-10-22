package com.agenticai.core.agents.agui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.TextNode;

/**
 * ADR-0022 stage 4 at the AG-UI entry: {@code POST /api/agents/{agentId}/agui/run} builds the turn
 * context from the tenant the filter bound to the request thread. The chat call happens when {@code
 * run} returns, before anything subscribes.
 */
class AgUiRunControllerTurnContextTest {

    private final UUID agentId = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theTurnContextCarriesTheRequestThreadsTenantInstance() {
        AgentChatService chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("ok"));
        AgUiRunController controller =
                new AgUiRunController(
                        mock(AgentService.class), chat, null, mock(AgUiRunEventBuffer.class));
        TenantContext bound = TenantContext.of("tenant_acme", "user-1", Set.of("USER"));
        TenantContext.set(bound);
        RunAgentInput input =
                new RunAgentInput(
                        "thread-1",
                        "run-1",
                        null,
                        null,
                        List.of(
                                new RunAgentInput.InputMessage(
                                        "m1", "user", new TextNode("hello"), null, null, null)),
                        List.of(),
                        List.of(),
                        null);

        controller.run(agentId, input, () -> "user-1");

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat).chat(context.capture(), request.capture());
        assertSame(bound, context.getValue().tenant());
        assertEquals("tenant_acme", context.getValue().tenantId());
        assertEquals(agentId, context.getValue().requestedAgentId());
        assertEquals("thread-1", context.getValue().sessionId());
        assertEquals("user-1", request.getValue().subjectKey());
        assertNotNull(context.getValue().requestId());
    }
}
