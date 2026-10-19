package com.agenticai.core.common.studioapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.share.AgentShare;
import com.agenticai.core.agents.share.AgentShareService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.share.WorkflowShareService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 at the published-app entry: {@code vd.call(alias)} on an agent connection builds
 * the turn context from the tenant bound on the calling thread (the app owner's schema, set by the
 * app-runtime controller).
 */
class StudioAppInvokeServiceTurnContextTest {

    private final UUID agentId = UUID.randomUUID();
    private final UUID appId = UUID.randomUUID();
    private AgentChatService chat;
    private StudioAppInvokeService service;
    private StudioApp app;
    private StudioAppConnection connection;

    @BeforeEach
    void setUp() {
        chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("hello"));
        AgentShareService shares = mock(AgentShareService.class);
        when(shares.resolveByShareId("share-1"))
                .thenReturn(Optional.of(new AgentShare(agentId, "share-1")));
        when(shares.loadSharedAgent(agentId)).thenReturn(Optional.of(mock(Agent.class)));
        service =
                new StudioAppInvokeService(
                        shares,
                        chat,
                        mock(WorkflowShareService.class),
                        mock(WorkflowService.class),
                        null,
                        mock(TenantLimits.class));
        app = mock(StudioApp.class);
        when(app.getId()).thenReturn(appId);
        when(app.getSlug()).thenReturn("demo-app");
        connection = mock(StudioAppConnection.class);
        when(connection.getKind()).thenReturn(StudioAppConnection.Kind.AGENT);
        when(connection.getShareId()).thenReturn("share-1");
        when(connection.getTargetId()).thenReturn(agentId);
        when(connection.getAlias()).thenReturn("helper");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theTurnContextCarriesTheCallingThreadsTenantInstance() {
        TenantContext bound = TenantContext.of("tenant_owner", "app-runtime", Set.of());
        TenantContext.set(bound);

        StudioAppInvokeService.InvokeResult result =
                service.call(app, connection, "hi", Map.of(), null, "10.0.0.1");

        assertInstanceOf(StudioAppInvokeService.InvokeResult.Ok.class, result);
        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat).chat(context.capture(), request.capture());
        assertSame(bound, context.getValue().tenant());
        assertEquals("tenant_owner", context.getValue().tenantId());
        assertEquals(agentId, context.getValue().requestedAgentId());
        assertEquals(request.getValue().sessionId(), context.getValue().sessionId());
        assertEquals("studio-app:" + appId, request.getValue().subjectKey());
        assertNotNull(context.getValue().requestId());
    }

    @Test
    void aThreadWithNoTenantStillRunsWithATenantLessContext() {
        TenantContext.clear();

        service.call(app, connection, "hi", Map.of(), null, "10.0.0.1");

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        verify(chat).chat(context.capture(), any(ChatTurnRequest.class));
        assertNull(context.getValue().tenant());
    }
}
