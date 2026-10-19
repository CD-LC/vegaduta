package com.agenticai.core.agents.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 at the public share entries. An anonymous visitor has no tenant of their own:
 * the controller (blocking {@code /chat}) or the share service (streaming {@code /chat/stream})
 * binds the OWNING tenant to the request thread, and the turn context must carry exactly that
 * tenant, not whatever the thread held before.
 */
class AgentShareControllerTurnContextTest {

    private static final String SHARE_ID = "abcdefghijklmnop";

    private final UUID agentId = UUID.randomUUID();
    private AgentShareService shareService;
    private AgentChatService chat;
    private AgentShareController controller;

    @BeforeEach
    void setUp() {
        shareService = mock(AgentShareService.class);
        chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("hello"));
        controller =
                new AgentShareController(
                        shareService,
                        chat,
                        mock(TenantLimits.class),
                        mock(ProviderModelCatalogService.class),
                        new ShareVisitorSessions("", "test-key"));
        when(shareService.tryAcquireVoiceTurn(anyString())).thenReturn(true);
        when(shareService.tryAcquireChat(anyString())).thenReturn(true);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void streamingTurnCarriesTheOwnerTenantTheShareServiceBound() {
        AgentShare share = new AgentShare(agentId, SHARE_ID);
        TenantContext owner =
                TenantContext.of("tenant_owner", "system:agent-share:" + SHARE_ID, Set.of());
        AtomicReference<TenantContext> boundBySpy = new AtomicReference<>();
        // resolveActiveShare leaves the owner's TenantContext on the thread (its javadoc).
        when(shareService.resolveActiveShare(SHARE_ID))
                .thenAnswer(
                        inv -> {
                            TenantContext.set(owner);
                            boundBySpy.set(owner);
                            return Optional.of(
                                    new AgentShareService.ResolvedShare(share, mock(Agent.class)));
                        });

        controller.chatStream(
                SHARE_ID,
                new AgentShareController.ShareChatStreamRequest("hi", null, null, null),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat).chat(context.capture(), request.capture());
        assertSame(owner, context.getValue().tenant());
        assertEquals(agentId, context.getValue().requestedAgentId());
        assertEquals(request.getValue().sessionId(), context.getValue().sessionId());
        assertNotNull(context.getValue().requestId());
    }

    @Test
    void blockingTurnCarriesTheTenantTheControllerResolvedFromTheShare() {
        // The visitor's thread holds a DIFFERENT tenant; the owner's must win for the turn.
        TenantContext.set(TenantContext.of("tenant_other", "someone", Set.of()));
        AgentShare share = new AgentShare(agentId, SHARE_ID);
        AtomicReference<TenantContext> seenByChat = new AtomicReference<>();
        when(shareService.resolveTenantSchema(SHARE_ID)).thenReturn(Optional.of("tenant_owner"));
        when(shareService.resolveByShareId(SHARE_ID)).thenReturn(Optional.of(share));
        when(shareService.isLive(share)).thenReturn(true);
        when(shareService.loadSharedAgent(agentId)).thenReturn(Optional.of(mock(Agent.class)));
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(
                        inv -> {
                            seenByChat.set(TenantContext.current().orElse(null));
                            return Flux.just("hello");
                        });

        controller.chat(
                SHARE_ID,
                new AgentShareController.ShareChatRequest("hi", null),
                new MockHttpServletRequest());

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        verify(chat).chat(context.capture(), any(ChatTurnRequest.class));
        assertEquals("tenant_owner", context.getValue().tenantId());
        assertEquals("system:agent-share:" + SHARE_ID, context.getValue().subject());
        assertSame(
                seenByChat.get(),
                context.getValue().tenant(),
                "on the request thread the context IS the ThreadLocal value");
        assertEquals(agentId, context.getValue().requestedAgentId());
    }
}
