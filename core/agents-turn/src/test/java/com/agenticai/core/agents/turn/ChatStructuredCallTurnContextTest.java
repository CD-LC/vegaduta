package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.AgentChatService.StructuredAgentInput;
import com.agenticai.core.security.TenantContext;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 at the structured entry: {@code chatWithParams} (workflow structured agent
 * nodes, evals) builds the turn context from the calling thread's tenant and passes it to {@code
 * AgentChatService#chat(TurnContext, ChatTurnRequest)}.
 */
class ChatStructuredCallTurnContextTest {

    private final UUID agentId = UUID.randomUUID();
    private AgentChatService service;
    private ChatStructuredCall call;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(AgentChatService.class);
        ChatAgentRouting routing = mock(ChatAgentRouting.class);
        when(routing.resolveExperimentAgentId(any(), any())).thenReturn(agentId);
        // agentRouting is a final field a mock leaves null; a null versionResolver is the
        // documented "hand-built fixture" path of chatWithParams.
        Field f = AgentChatService.class.getDeclaredField("agentRouting");
        f.setAccessible(true);
        f.set(service, routing);
        AgentService agentService = mock(AgentService.class);
        when(agentService.getEntity(agentId)).thenReturn(mock(Agent.class));
        ToolCallLogService logs = mock(ToolCallLogService.class);
        when(logs.findByAgentIdAndSessionId(any(), anyString())).thenReturn(List.of());
        when(service.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("answer"));
        call = new ChatStructuredCall(service, agentService, logs);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private TurnContext captured() {
        ArgumentCaptor<TurnContext> ctx = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> req = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(service).chat(ctx.capture(), req.capture());
        assertEquals(req.getValue().sessionId(), ctx.getValue().sessionId());
        return ctx.getValue();
    }

    private void invoke() {
        var out =
                call.chatWithParams(
                        agentId,
                        "run-1",
                        "run-1",
                        new StructuredAgentInput("question", null, null, false),
                        "node-1",
                        Duration.ofSeconds(5));
        assertEquals("answer", out.resultText());
    }

    @Test
    void theContextIsTheCallingThreadsTenant() {
        TenantContext tenant = TenantContext.of("tenant_acme", "runner", Set.of("USER"));
        TenantContext.set(tenant);

        invoke();

        TurnContext context = captured();
        assertSame(tenant, context.tenant());
        assertEquals("tenant_acme", context.tenantId());
        assertEquals(agentId, context.requestedAgentId());
        assertEquals("run-1", context.sessionId());
        assertNotNull(context.requestId());
    }

    @Test
    void aThreadWithoutATenantBuildsATenantlessContext() {
        invoke();

        TurnContext context = captured();
        assertNull(context.tenant());
        assertEquals(agentId, context.requestedAgentId());
    }
}
