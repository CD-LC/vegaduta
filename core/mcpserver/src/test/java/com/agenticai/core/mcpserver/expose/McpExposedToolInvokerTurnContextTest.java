package com.agenticai.core.mcpserver.expose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.mcpserver.McpCallerContext;
import com.agenticai.core.mcpserver.McpCallerContextTestAccess;
import com.agenticai.core.mcpserver.McpServerRateLimiter;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowBackgroundRunService;
import com.agenticai.core.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 at the exposed-tool entry: an {@code agent_*} MCP tool call builds the turn
 * context from the tenant the MCP API-key filter bound to the request thread (the grant's owning
 * tenant, carrying the grant id as external trigger).
 */
class McpExposedToolInvokerTurnContextTest {

    private final UUID grantId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private AgentChatService chat;
    private McpExposedToolInvoker invoker;
    private McpExposedTool agentTool;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("pong"));
        McpServerRateLimiter limiter = mock(McpServerRateLimiter.class);
        when(limiter.allowExposedInvocation(any(), anyLong())).thenReturn(true);
        invoker =
                new McpExposedToolInvoker(
                        chat,
                        mock(WorkflowService.class),
                        mock(WorkflowBackgroundRunService.class),
                        mock(AuditLogService.class),
                        limiter,
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(McpExposedToolCatalog.class),
                        new McpExposedToolInvoker.Settings(5, 1, 1, 30, 1000, 5000));
        McpCallerContextTestAccess.set(grantId, Set.of(agentId), Set.of(), Set.of());
        agentTool =
                new McpExposedTool(
                        "agent_helper", "d", "{}", McpExposedTool.Target.AGENT, agentId, "Helper");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        McpCallerContextTestAccess.clear();
    }

    @Test
    void theTurnContextCarriesTheGrantsTenantInstance() throws Exception {
        TenantContext bound =
                TenantContext.of(
                        "tenant_a",
                        "mcp-external:" + grantId,
                        Set.of("ROLE_mcp-external"),
                        grantId.toString());
        TenantContext.set(bound);
        McpCallerContext caller = McpCallerContext.current().orElseThrow();

        invoker.invoke(
                caller,
                agentTool,
                new ObjectMapper().readTree("{\"message\":\"ping\",\"conversationId\":\"c1\"}"));

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat).chat(context.capture(), request.capture());
        assertSame(bound, context.getValue().tenant());
        assertEquals("tenant_a", context.getValue().tenantId());
        assertEquals(agentId, context.getValue().requestedAgentId());
        assertEquals("mcp-" + grantId + "-c1", context.getValue().sessionId());
        assertEquals("mcp-external:" + grantId, request.getValue().subjectKey());
        assertNotNull(context.getValue().requestId());
    }
}
