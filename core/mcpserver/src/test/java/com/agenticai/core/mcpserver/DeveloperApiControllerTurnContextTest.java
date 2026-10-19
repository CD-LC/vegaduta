package com.agenticai.core.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 at the developer REST API ({@code POST /api/dev/v1/agents/{id}/chat}): the turn
 * context carries the tenant the API-key filter bound to the request thread.
 */
class DeveloperApiControllerTurnContextTest {

    private final UUID grantId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private AgentChatService chat;
    private DeveloperApiController controller;

    @BeforeEach
    void setUp() {
        chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("pong"));
        controller =
                new DeveloperApiController(
                        chat,
                        mock(AgentService.class),
                        mock(WorkflowService.class),
                        mock(AuditLogService.class));
        McpCallerContext.set(grantId, Set.of(agentId), Set.of(), Set.of());
    }

    @AfterEach
    void tearDown() {
        McpCallerContext.clear();
        TenantContext.clear();
    }

    @Test
    void theTurnContextCarriesTheGrantsTenantInstance() {
        TenantContext bound = TenantContext.of("tenant_acme", "dev-api:" + grantId, Set.of("USER"));
        TenantContext.set(bound);

        controller.chat(agentId, new DeveloperApiController.ChatRequest("ping", "s-1"));

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat).chat(context.capture(), request.capture());
        assertSame(bound, context.getValue().tenant());
        assertEquals("tenant_acme", context.getValue().tenantId());
        assertEquals(agentId, context.getValue().requestedAgentId());
        assertEquals("s-1", context.getValue().sessionId());
        assertEquals("dev-api:" + grantId, request.getValue().subjectKey());
        assertNotNull(context.getValue().requestId());
    }

    @Test
    void aThreadWithNoTenantStillRunsWithATenantLessContext() {
        TenantContext.clear();

        controller.chat(agentId, new DeveloperApiController.ChatRequest("ping", null));

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        verify(chat).chat(context.capture(), any(ChatTurnRequest.class));
        assertNull(context.getValue().tenant());
    }
}
