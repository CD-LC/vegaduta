package com.agenticai.core.mcpserver.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.mcpserver.McpCallerContextTestAccess;
import com.agenticai.core.security.TenantContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 at the MCP {@code call_agent} entry: the turn context carries the tenant the MCP
 * API-key filter bound to the request thread (the grant's owning tenant), with the synthetic {@code
 * mcp-external:<grantId>} subject kept on the chat request.
 */
class McpCallAgentToolCallbackTurnContextTest {

    private final UUID grantId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private AgentChatService chat;
    private McpCallAgentToolCallback callback;

    @BeforeEach
    void setUp() {
        chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("pong"));
        callback = new McpCallAgentToolCallback(chat, mock(AuditLogService.class));
        McpCallerContextTestAccess.set(grantId, Set.of(agentId), Set.of(), Set.of());
    }

    @AfterEach
    void tearDown() {
        McpCallerContextTestAccess.clear();
        TenantContext.clear();
    }

    private String input() {
        return "{\"agent_id\":\"" + agentId + "\",\"message\":\"ping\",\"session_id\":\"s-1\"}";
    }

    @Test
    void theTurnContextCarriesTheGrantsTenantInstance() {
        TenantContext bound =
                TenantContext.of("tenant_acme", "mcp-external:" + grantId, Set.of("USER"));
        TenantContext.set(bound);

        callback.call(input());

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat).chat(context.capture(), request.capture());
        assertSame(bound, context.getValue().tenant());
        assertEquals("tenant_acme", context.getValue().tenantId());
        assertEquals(agentId, context.getValue().requestedAgentId());
        assertEquals("s-1", context.getValue().sessionId());
        assertEquals("mcp-external:" + grantId, request.getValue().subjectKey());
        assertNotNull(context.getValue().requestId());
    }

    @Test
    void aThreadWithNoTenantStillRunsWithATenantLessContext() {
        TenantContext.clear();

        callback.call(input());

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        verify(chat).chat(context.capture(), any(ChatTurnRequest.class));
        assertNull(context.getValue().tenant());
    }
}
